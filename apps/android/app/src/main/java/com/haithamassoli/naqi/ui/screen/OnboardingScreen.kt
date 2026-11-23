package com.haithamassoli.naqi.ui.screen

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.data.Prefs
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.ui.NaqiBottomAction
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.NaqiIcons
import com.haithamassoli.naqi.ui.NaqiRowDivider
import com.haithamassoli.naqi.ui.SelectDot
import com.haithamassoli.naqi.ui.ToggleTile
import com.haithamassoli.naqi.ui.theme.NaqiTokens

private enum class Page { Language, Welcome, Blur, Music, Ready }

/** Both names in their own script: this page is read before anything is translated. */
private val LANGUAGES = listOf("ar" to "العربية", "en" to "English")

/**
 * First launch only: language, what the app does, then the two choices that matter — blur and music.
 * Everything finer (whole frame, scenes, strictness, stems) stays on Options, where there is a video
 * to apply it to. The choices become the saved defaults and the ops the first pick screen opens with.
 *
 * ponytail: the language page needs the platform per-app locale API (33+); older devices skip it and
 * follow the system language, same as the overflow menu. Add appcompat if pre-33 users ask.
 */
@Composable
fun OnboardingScreen(onDone: (FilterOps) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pages = remember { Page.entries.filter { it != Page.Language || Build.VERSION.SDK_INT >= 33 } }
    // Saveable: picking a language recreates the activity, and the tour must carry on where it was.
    var index by rememberSaveable { mutableIntStateOf(0) }
    var ops by rememberSaveable { mutableStateOf(Prefs.ops(context).copy(censorWho = Prefs.lastWho(context))) }
    val last = index == pages.lastIndex

    fun finish() {
        Prefs.save(context, ops, Prefs.quality(context))
        Prefs.saveWho(context, ops.censorWho)
        Prefs.markOnboarded(context)
        onDone(ops)
    }

    BackHandler(enabled = index > 0) { index-- }

    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.statusBarsPadding().height(56.dp).padding(horizontal = NaqiTokens.space1)) {
            if (index > 0) {
                IconButton(onClick = { index-- }, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(NaqiIcons.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        // Forward slides in from the reading direction's end; backward just fades.
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                val forward = targetState > initialState
                (if (forward) slideInHorizontally { if (rtl) -it / 3 else it / 3 } + fadeIn() else fadeIn())
                    .togetherWith(fadeOut())
            },
            label = "onboardingPage",
            modifier = Modifier.weight(1f),
        ) { i ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = NaqiTokens.gutter)
                    .padding(bottom = NaqiTokens.space5),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (pages[i]) {
                    Page.Language -> LanguagePage()
                    Page.Welcome -> TourPage(R.drawable.onboarding_import, R.string.onb_welcome_title, R.string.onb_welcome_body) {
                        NaqiCard(contentPadding = 0.dp) {
                            Feature(NaqiIcons.Shield, R.string.onb_feat_blur)
                            NaqiRowDivider()
                            Feature(NaqiIcons.MusicOff, R.string.onb_feat_music)
                            NaqiRowDivider()
                            Feature(NaqiIcons.Check, R.string.onb_feat_private)
                        }
                    }
                    Page.Blur -> TourPage(R.drawable.onboarding_privacy, R.string.onb_blur_title, R.string.onb_blur_body) {
                        NaqiCard(contentPadding = 0.dp) {
                            ToggleTile(
                                title = stringResource(R.string.pick_op_faces_title),
                                desc = stringResource(R.string.pick_op_faces_desc_off),
                                icon = NaqiIcons.Shield,
                                checked = ops.censorFaces,
                                onCheckedChange = {
                                    ops = ops.copy(censorWho = if (it) Prefs.lastWho(context) else FilterOps.NONE)
                                },
                            )
                            if (ops.censorFaces) {
                                NaqiRowDivider()
                                WhoRow(ops.censorWho) { Prefs.saveWho(context, it); ops = ops.copy(censorWho = it) }
                                NaqiRowDivider()
                                CensorStyleRow(ops.solidColor) { ops = ops.copy(solidColor = it) }
                            }
                        }
                    }
                    Page.Music -> TourPage(R.drawable.onboarding_music, R.string.onb_music_title, R.string.onb_music_body) {
                        NaqiCard(contentPadding = 0.dp) {
                            ToggleTile(
                                title = stringResource(R.string.pick_op_music_title),
                                desc = stringResource(R.string.pick_op_music_desc),
                                icon = NaqiIcons.MusicOff,
                                checked = ops.removeMusic,
                                onCheckedChange = { ops = ops.copy(removeMusic = it) },
                            )
                        }
                    }
                    Page.Ready -> TourPage(R.drawable.onboarding_ready, R.string.onb_ready_title, R.string.onb_ready_body) {
                        TrustSeal()
                    }
                }
            }
        }
        NaqiBottomAction(
            label = stringResource(if (last) R.string.onb_get_started else R.string.action_continue),
            enabled = true,
            onClick = { if (last) finish() else index++ },
            above = { Dots(count = pages.size, current = index) },
        )
    }
}

@Composable
private fun LanguagePage() {
    val context = LocalContext.current
    val current = LocalConfiguration.current.locales[0].language
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(NaqiTokens.space6))
    Icon(painterResource(R.drawable.ic_naqi_mark), null, tint = cs.primary, modifier = Modifier.size(88.dp))
    Spacer(Modifier.height(NaqiTokens.space5))
    Text("نقي · Naqi", style = MaterialTheme.typography.headlineMedium, color = cs.onSurface)
    Spacer(Modifier.height(NaqiTokens.space2))
    Text("اختر اللغة · Choose your language", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    Spacer(Modifier.height(NaqiTokens.space5))
    NaqiCard(contentPadding = 0.dp) {
        LANGUAGES.forEachIndexed { i, (code, name) ->
            if (i > 0) NaqiRowDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = current == code, role = Role.RadioButton) {
                        // Persisted by the platform (Settings › Apps › Naqi › Language shows it) and
                        // applied by recreating the activity; the saveable page index survives that.
                        if (Build.VERSION.SDK_INT >= 33) {
                            context.getSystemService(LocaleManager::class.java).applicationLocales =
                                LocaleList.forLanguageTags(code)
                        }
                    }
                    .padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
                SelectDot(current == code)
            }
        }
    }
}

/** One tour page: picture, headline, one sentence, then whatever it asks. */
@Composable
private fun ColumnScope.TourPage(
    @DrawableRes image: Int,
    title: Int,
    body: Int,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Image(
        painterResource(image),
        contentDescription = null,
        modifier = Modifier.heightIn(max = 220.dp).fillMaxWidth(),
    )
    Text(
        stringResource(title),
        style = MaterialTheme.typography.headlineSmall,
        color = cs.onSurface,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(NaqiTokens.space2))
    Text(
        stringResource(body),
        style = MaterialTheme.typography.bodyMedium,
        color = cs.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(NaqiTokens.space5))
    content()
}

@Composable
private fun Feature(icon: ImageVector, text: Int) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(NaqiTokens.shapeButton)
                .background(cs.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = cs.primary, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(NaqiTokens.space3))
        Text(stringResource(text), style = MaterialTheme.typography.titleSmall, color = cs.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Dots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = NaqiTokens.space2),
        horizontalArrangement = Arrangement.spacedBy(NaqiTokens.space2, Alignment.CenterHorizontally),
    ) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 22.dp else 7.dp, NaqiTokens.expressiveSpring(), label = "dot")
            Box(
                Modifier
                    .size(width = width, height = 7.dp)
                    .clip(NaqiTokens.shapePill)
                    .background(if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}
