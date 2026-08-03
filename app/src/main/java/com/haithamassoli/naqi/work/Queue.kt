package com.haithamassoli.naqi.work

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.haithamassoli.naqi.model.FilterOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The user-visible queue: what was shared, in what order, and how each item actually ended up.
 *
 * **Why this exists next to WorkManager rather than instead of it.** WorkManager is the *scheduler* —
 * the chains give FIFO ordering and survive process death for free, and nothing here re-implements
 * that. What it cannot express is the thing the PRD's scheduling rule forces: a queue-driven run must
 * always return `Result.success()`, because a chained failure fails every request behind it and one bad
 * download would kill the rest of the queue. That makes `WorkInfo.State` useless as an outcome — every
 * item reads SUCCEEDED, including the ones that failed. So the real outcome is recorded here.
 *
 * One JSON file, `@Synchronized`, written to a temp and renamed. Both workers live in the same process,
 * so a monitor plus an atomic rename is the whole concurrency story.
 *
 * ponytail: not Room. There is no query, no migration, and no cross-process reader — a list of a dozen
 * items that is rewritten whole on every change. Move to Room the moment any of those three stops being
 * true.
 */
internal object Queue {

    private const val TAG = "Queue"
    private const val FILE = "queue.json"

    enum class State {
        PENDING_DOWNLOAD, DOWNLOADING, PENDING_FILTER, FILTERING, DONE, FAILED;

        /** Terminal items are history: they no longer block a duplicate share of the same URL. */
        val isTerminal: Boolean get() = this == DONE || this == FAILED
    }

    /**
     * @param sourceUri the file being filtered — a `file://` quarantine path for a download, or the
     *   shared `content://` for a local file. Null until a download produces one.
     * @param error a `@StringRes` id, never a message: resource ids re-localize if the app language
     *   changes after the item failed, which a stored sentence cannot.
     */
    data class Item(
        val id: String = UUID.randomUUID().toString(),
        val url: String? = null,
        val sourceUri: String? = null,
        val title: String? = null,
        val state: State = State.PENDING_DOWNLOAD,
        val ops: FilterOps = FilterOps(),
        val quality: String? = null,
        @param:StringRes val error: Int? = null,
        val outputUri: String? = null,
    )

    private val _items = MutableStateFlow<List<Item>>(emptyList())

    /** The queue, for the UI. Populated on first [load]; every mutation below republishes it. */
    val items: StateFlow<List<Item>> get() = _items.asStateFlow()

    @Synchronized
    fun load(context: Context): List<Item> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList<Item>().also { _items.value = it }
        val parsed = runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }.getOrElse {
            // A truncated or hand-edited file must not brick the queue screen forever.
            Log.w(TAG, "queue.json unreadable, starting empty", it)
            emptyList()
        }
        _items.value = parsed
        return parsed
    }

    @Synchronized
    fun add(context: Context, item: Item) {
        write(context, load(context) + item)
    }

    /** Apply [transform] to one item. No-op if it is already gone (cancelled while a worker ran). */
    @Synchronized
    fun update(context: Context, id: String, transform: (Item) -> Item) {
        val current = load(context)
        if (current.none { it.id == id }) return
        write(context, current.map { if (it.id == id) transform(it) else it })
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        write(context, load(context).filterNot { it.id == id })
    }

    /** Drop every finished item; the queue screen's "clear" action. */
    @Synchronized
    fun clearTerminal(context: Context) {
        write(context, load(context).filterNot { it.state.isTerminal })
    }

    /** Is this URL already queued and not yet finished? The PRD's "share the same URL twice" rule. */
    fun isActive(context: Context, url: String): Boolean =
        load(context).any { it.url == url && !it.state.isTerminal }

    /** Items that have not started yet — what [cancel-repair] has to re-append. */
    fun pending(context: Context): List<Item> =
        load(context).filter { it.state == State.PENDING_DOWNLOAD || it.state == State.PENDING_FILTER }

    private fun write(context: Context, items: List<Item>) {
        val array = JSONArray().apply { items.forEach { put(toJson(it)) } }
        val dest = File(context.filesDir, FILE)
        val temp = File(context.filesDir, "$FILE.tmp")
        runCatching {
            temp.writeText(array.toString())
            // Rename, never write-in-place: a kill mid-write would otherwise leave a half-written file
            // under the real name, and the queue is the only record of what the user asked for.
            check(temp.renameTo(dest)) { "could not commit queue.json" }
        }.onFailure { Log.e(TAG, "queue write failed", it); runCatching { temp.delete() } }
        _items.value = items
    }

    private fun toJson(i: Item) = JSONObject().apply {
        put("id", i.id)
        put("url", i.url)
        put("sourceUri", i.sourceUri)
        put("title", i.title)
        put("state", i.state.name)
        put("quality", i.quality)
        put("error", i.error ?: JSONObject.NULL)
        put("outputUri", i.outputUri)
        put(
            "ops",
            JSONObject().apply {
                put("removeMusic", i.ops.removeMusic)
                put("censorWomen", i.ops.censorWomen)
                put("strictness", i.ops.strictness)
                put("blurAmount", i.ops.blurAmount)
                put("grayscale", i.ops.grayscale)
                put("solidColor", i.ops.solidColor)
                put("keepStems", i.ops.keepStems)
            },
        )
    }

    private fun fromJson(o: JSONObject): Item {
        val ops = o.optJSONObject("ops") ?: JSONObject()
        return Item(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            url = o.optStringOrNull("url"),
            sourceUri = o.optStringOrNull("sourceUri"),
            title = o.optStringOrNull("title"),
            // An unknown state name means a file written by a newer build; treat it as failed rather
            // than crashing the screen that is supposed to show the user what went wrong.
            state = runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.FAILED),
            quality = o.optStringOrNull("quality"),
            error = o.opt("error").let { if (it is Int) it else null },
            outputUri = o.optStringOrNull("outputUri"),
            ops = FilterOps(
                removeMusic = ops.optBoolean("removeMusic", false),
                censorWomen = ops.optBoolean("censorWomen", false),
                strictness = ops.optInt("strictness", 50),
                blurAmount = ops.optInt("blurAmount", 60),
                grayscale = ops.optBoolean("grayscale", false),
                solidColor = ops.optInt("solidColor", FilterOps.BLUR),
                // A "blurUnknownFaces" key written by an older build is simply ignored (plan-v2 §5.4).
                keepStems = ops.optString("keepStems").ifBlank { "vocals" },
            ),
        )
    }

    /** `optString` turns a JSON null into the string "null"; this does not. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
