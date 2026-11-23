package com.haithamassoli.naqi.publish

import android.content.Context
import androidx.annotation.StringRes
import com.haithamassoli.naqi.R

/**
 * One platform's rules for "prepare to post". Every difference between platforms lives in these
 * fields: the splitter and the sheet never ask *which* platform it is, so a changed limit is a
 * one-line edit to [ALL], and a new platform is one more entry.
 */
data class PublishPreset(
    /** Stable; part files are named with it, which is how a prepared video is found again. */
    val id: String,
    @StringRes val labelRes: Int,
    /** Longest part the platform accepts. Null = no limit: the video is shared as-is, never copied. */
    val maxSegmentMs: Long?,
    /** Can one share carry every part (`ACTION_SEND_MULTIPLE`)? False = each part is shared on its own. */
    val supportsMultipleSegments: Boolean,
    /**
     * Where the share goes, tried in order; the first that opens wins, and none falls back to the system
     * chooser. Each entry is a package, or `package/Activity` to skip the app's own "which screen?" picker
     * (X shares into Chat or a new post). A list because one platform can be several apps (WhatsApp /
     * WhatsApp Business, TikTok's regional builds), and so a renamed activity can fall back to its package.
     */
    val targets: List<String> = emptyList(),
    /**
     * What the platform's share target is registered for; the share uses it when the file's own type is
     * unknown. Not enforced: nothing converts a whole file into it.
     */
    val mime: String = "video/mp4",
) {
    /** Derived rather than stored: a stored flag could contradict [maxSegmentMs]. */
    fun requiresSplitting(durationMs: Long): Boolean = maxSegmentMs != null && durationMs > maxSegmentMs

    /** Any of [targets] on the device. Needs the manifest's `<queries>` SEND entry to see them on API 30+. */
    fun isInstalled(context: Context): Boolean = installedPackage(context) != null

    /** The first of [targets]' packages on the device — the one whose icon stands for this preset. */
    fun installedPackage(context: Context): String? = targets.map { it.substringBefore('/') }.firstOrNull {
        runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
    }

    companion object {
        val ALL = listOf(
            PublishPreset(
                "whatsapp-status", R.string.preset_whatsapp_status, 90_000, true,
                listOf("com.whatsapp", "com.whatsapp.w4b"),
            ),
            PublishPreset(
                "x-free", R.string.preset_x_free, 140_000, false,
                listOf("com.twitter.android/com.twitter.composer.ComposerActivity", "com.twitter.android"),
            ),
            // Instagram splits long Stories itself, so Story is pass-through. Both name the exact screen: by
            // package alone, Android's picker offered only Instagram's DMs on a S23.
            PublishPreset(
                "instagram-story", R.string.preset_instagram_story, null, false,
                listOf("com.instagram.android/com.instagram.share.handleractivity.StoryShareHandlerActivity", "com.instagram.android"),
            ),
            PublishPreset(
                "instagram-reels", R.string.preset_instagram_reels, 180_000, false,
                listOf("com.instagram.android/com.instagram.share.handleractivity.ReelShareHandlerActivity", "com.instagram.android"),
            ),
            // 120 s measured, not documented: a 2:30 share got "Video must be under 120 seconds" on a S23.
            PublishPreset("snapchat-story", R.string.preset_snapchat_story, 120_000, true, listOf("com.snapchat.android")),
            // Telegram takes files up to 2 GB, so pass-through; `.web` is the build sold outside Play.
            PublishPreset(
                "telegram", R.string.preset_telegram, null, true,
                listOf("org.telegram.messenger", "org.telegram.messenger.web"),
            ),
            // Pass-through: its share screen took a 2:30 video and several at once on a S23.
            // Named screen: by package alone Android asked "Just once / Always" on a S23.
            PublishPreset(
                "messenger", R.string.preset_messenger, null, true,
                listOf("com.facebook.orca/com.facebook.messenger.intents.ShareIntentHandler", "com.facebook.orca"),
            ),
            // TikTok takes minutes, so it is pass-through today.
            PublishPreset(
                "tiktok", R.string.preset_tiktok, null, false,
                listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            ),
        )

        /** Custom's chip, counted and ordered with the platforms; its parts use [custom]'s own id. */
        const val CUSTOM = "custom"

        /** The user's own limit. The seconds are in the id so parts made at 60 s and at 30 s never mix. */
        fun custom(seconds: Int) = PublishPreset("custom-${seconds}s", R.string.preset_custom, seconds * 1000L, true)
    }
}
