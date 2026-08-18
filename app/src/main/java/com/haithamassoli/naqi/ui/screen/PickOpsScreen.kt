package com.haithamassoli.naqi.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.URL_IN_TEXT
import com.haithamassoli.naqi.media.displayName
import com.haithamassoli.naqi.media.isAudio
import com.haithamassoli.naqi.data.Prefs
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.ui.NaqiBottomAction
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.NaqiIcons
import com.haithamassoli.naqi.ui.NaqiRowDivider
import com.haithamassoli.naqi.ui.NaqiTopBar
import com.haithamassoli.naqi.ui.NoteLine
import com.haithamassoli.naqi.ui.SectionHeader
import com.haithamassoli.naqi.ui.ToggleTile
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import com.haithamassoli.naqi.work.JobController
import com.haithamassoli.naqi.work.Queue

/**
 * Step 1: choose the video and which operations to run. Tuning for those operations lives on the
 * options screen, so nothing here writes anything but [FilterOps.removeMusic]/[FilterOps.censorWho].
 *
 * Everything that is not one of those two decisions was pushed off the screen: language and about into
 * the overflow menu, the model smoke report into [AboutScreen]. What is left fits on a phone without
 * scrolling, and Continue is pinned so it never has to be scrolled to.
 */
@Composable
fun PickOpsScreen(
    pickedUri: Uri?,
    pickedName: String?,
    ops: FilterOps,
    onPicked: (Uri, String?) -> Unit,
    onOpsChange: (FilterOps) -> Unit,
    onContinue: () -> Unit,
    onJobs: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Off is [FilterOps.NONE], which erases *which* faces were picked, so the toggle remembers the last
    // real choice or off-then-on would silently change a "Women" run to something else. Seeded from
    // Prefs rather than a constant, so the pick survives the Options detour, the back button and the
    // process — a fresh install is the only thing that gets FilterOps.DEFAULT_WHO.
    val who = remember { mutableStateOf(Prefs.lastWho(context)) }
        .apply { if (ops.censorFaces) value = ops.censorWho }
        .value

    // An audio source has no video track, so music removal is the only op it can run — the pick sets it
    // rather than leaving the user to discover that "Censor faces" fails preflight with "no video track".
    val isAudio = remember(pickedUri) { pickedUri != null && context.isAudio(pickedUri) }

    // The other answer to "where does the video come from?". Everything past the URL — quality, the
    // filters, the queue — is what a shared link already lands in, so this only has to produce a URL and
    // open the same sheet. That sheet asks its own filter questions (seeded from Prefs); the toggles
    // below belong to the picked file.
    var link by rememberSaveable { mutableStateOf("") }
    var linkError by rememberSaveable { mutableStateOf<Int?>(null) }
    var linkSheet by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current

    fun submitLink() {
        val url = URL_IN_TEXT.find(link)?.value
        linkError = when {
            url == null -> R.string.share_no_url
            // Same rule as the share intent: the same link twice is one item, not two.
            Queue.isActive(context, url) || JobController.isQueued(context, url) ->
                R.string.share_already_queued

            else -> null
        }
        // Keep the keyboard up on a rejection — the text still needs fixing.
        if (linkError == null) {
            focus.clearFocus()
            linkSheet = url
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (context.isAudio(uri)) onOpsChange(ops.copy(removeMusic = true, censorWho = FilterOps.NONE))
            onPicked(uri, context.displayName(uri))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NaqiTopBar(
                title = stringResource(R.string.app_name),
                titleIcon = ImageVector.vectorResource(R.drawable.ic_naqi_mark),
                actions = {
                    TextButton(onClick = onJobs) { Text(stringResource(R.string.jobs_title)) }
                    OverflowMenu(onAbout = onAbout)
                },
            )
        },
        bottomBar = {
            NaqiBottomAction(
                label = stringResource(R.string.action_continue),
                // Both are required: a job without a video has nothing to filter, one without an op nothing to do.
                enabled = pickedUri != null && ops.any,
                onClick = onContinue,
                above = { NoteLine(NaqiIcons.Check, stringResource(R.string.pick_reassurance)) },
            )
        },
        modifier = modifier,
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NaqiTokens.gutter)
                .padding(top = NaqiTokens.space2, bottom = NaqiTokens.space5),
        ) {
            TrustSeal()
            Spacer(Modifier.height(NaqiTokens.space5))

            PickVideoCard(picked = pickedUri != null, fileName = pickedName) {
                picker.launch(arrayOf("video/*", "audio/*"))
            }
            // Tighter than the gap below: file and link are one decision with two answers.
            Spacer(Modifier.height(NaqiTokens.space3))
            LinkField(
                value = link,
                error = linkError,
                onValueChange = { link = it; linkError = null },
                onSubmit = ::submitLink,
            )
            Spacer(Modifier.height(NaqiTokens.space5))

            SectionHeader(stringResource(R.string.pick_eyebrow_choose))
            // One card, two rows: the pair is a single decision about what this run does.
            NaqiCard(contentPadding = 0.dp) {
                ToggleTile(
                    title = stringResource(R.string.pick_op_music_title),
                    desc = stringResource(R.string.pick_op_music_desc),
                    icon = NaqiIcons.MusicOff,
                    checked = ops.removeMusic,
                    onCheckedChange = { onOpsChange(ops.copy(removeMusic = it)) },
                )
                // Hidden, not disabled, for an audio source: there is no frame to censor, so the row
                // could only ever say no.
                if (!isAudio) {
                    NaqiRowDivider()
                    ToggleTile(
                        title = stringResource(R.string.pick_op_faces_title),
                        // The subtitle states the current choice instead of claiming "every face", which
                        // Women/Men would make a lie (plan-censor-who §2.1). Off gets the plain description
                        // of the op: ToggleTile renders `desc` at full emphasis either way, so an off row
                        // saying "Everyone · and flagged scenes." would assert censoring that is not running.
                        desc = if (ops.censorFaces)
                            stringResource(R.string.pick_op_faces_desc, stringResource(whoLabelRes(who)))
                        else stringResource(R.string.pick_op_faces_desc_off),
                        icon = NaqiIcons.Shield,
                        checked = ops.censorFaces,
                        onCheckedChange = {
                            onOpsChange(ops.copy(censorWho = if (it) who else FilterOps.NONE))
                        },
                    )
                }
            }
        }
    }

    linkSheet?.let { url ->
        ShareSheet(
            shared = Shared.Link(url),
            // The toggles sit under both sources, so a link honours what the user already set here
            // instead of asking the same question twice. Untouched toggles pass null and let the
            // sheet keep its own saved defaults.
            initialOps = ops.takeIf { it.any },
            onDismiss = { linkSheet = null },
            onQueued = { linkSheet = null; link = ""; onJobs() },
        )
    }
}

/**
 * The link half of the source decision: one field, one action, no header. The placeholder carries the
 * "or", and the trailing button is an [IconButton] because the pinned Continue is the screen's one
 * filled action and nothing else may compete with it.
 */
@Composable
private fun LinkField(
    value: String,
    error: Int?,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.pick_link_hint)) },
        trailingIcon = {
            IconButton(onClick = onSubmit, enabled = value.isNotBlank()) {
                Icon(NaqiIcons.Download, contentDescription = stringResource(R.string.pick_link_action))
            }
        },
        isError = error != null,
        supportingText = if (error != null) {
            { Text(stringResource(error)) }
        } else {
            null
        },
        singleLine = true,
        shape = NaqiTokens.shapeButton,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
    )
}

/**
 * Language and about. Both are things a user opens once and never again, so they cost an icon rather
 * than two full-width cards on the busiest screen.
 *
 * ponytail: system per-app language picker (API 33+); an in-app switcher needs appcompat, add when
 * pre-33 users complain.
 */
@Composable
private fun OverflowMenu(onAbout: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }

    IconButton(onClick = { open = true }) {
        Icon(NaqiIcons.More, contentDescription = stringResource(R.string.action_more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.opt_language)) },
                onClick = {
                    open = false
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APP_LOCALE_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }
                },
            )
        }
        // Attribution has to be reachable from the app itself, not only from the repository — GPL-3.0
        // and an AGPL-3.0 model are not obligations a README discharges.
        DropdownMenuItem(
            text = { Text(stringResource(R.string.about_open)) },
            onClick = { open = false; onAbout() },
        )
    }
}

@Composable
private fun TrustSeal() {
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NaqiTokens.space2),
            modifier = Modifier
                .clip(NaqiTokens.shapePill)
                .background(primary.copy(alpha = 0.08f))
                .border(1.dp, primary.copy(alpha = 0.22f), NaqiTokens.shapePill)
                .padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space2),
        ) {
            Text(
                stringResource(R.string.pick_seal_on_device),
                style = MaterialTheme.typography.labelMedium,
                color = primary,
            )
            Text("·", style = MaterialTheme.typography.labelMedium, color = primary.copy(alpha = 0.55f))
            Text(
                stringResource(R.string.pick_seal_private),
                style = MaterialTheme.typography.labelMedium,
                color = primary,
            )
        }
    }
}

/** The one thing this screen exists for: a wide, unmistakable target that also reports what is picked. */
@Composable
private fun PickVideoCard(picked: Boolean, fileName: String?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cardBgColor by animateColorAsState(
        targetValue = if (picked) cs.primary.copy(alpha = 0.08f) else cs.surfaceContainer,
        animationSpec = NaqiTokens.expressiveSpring(),
        label = "pickCardBg",
    )
    val cardBorderColor by animateColorAsState(
        targetValue = if (picked) cs.primary else cs.outlineVariant,
        animationSpec = NaqiTokens.expressiveSpring(),
        label = "pickCardBorder",
    )
    val iconBgColor by animateColorAsState(
        targetValue = if (picked) cs.primary else cs.surfaceContainerHighest,
        animationSpec = NaqiTokens.expressiveSpring(),
        label = "pickIconBg",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clip(NaqiTokens.shapeCard)
            .background(cardBgColor)
            .border(1.5.dp, cardBorderColor, NaqiTokens.shapeCard)
            .clickable(onClick = onClick)
            .padding(NaqiTokens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(NaqiTokens.shapeButton)
                .background(iconBgColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (picked) NaqiIcons.Check else NaqiIcons.Video,
                contentDescription = null,
                tint = if (picked) cs.onPrimary else cs.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(NaqiTokens.space4))
        Column(Modifier.weight(1f)) {
            Text(
                // A provider may not expose a display name; the video is still picked, so don't look unpicked.
                fileName ?: stringResource(if (picked) R.string.pick_video_selected else R.string.pick_video_none),
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(if (picked) R.string.pick_video_change else R.string.pick_video_formats),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

/** The mark, shown once on the about screen rather than above every visit to the pick screen. */
@Composable
internal fun Wordmark() {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The mark is the bowl of ن with a play triangle for its dot. It stands alone: the top bar
        // right above it already carries the name, so a wordmark here only said it twice.
        Icon(
            painterResource(R.drawable.ic_naqi_mark),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
    }
}
