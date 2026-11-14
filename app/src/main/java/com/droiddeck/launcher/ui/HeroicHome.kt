package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.heroic.HeroicLauncher
import com.droiddeck.launcher.stores.launcher.BundledLaunchers

@Composable
internal fun HeroicHome(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val ctx = LocalContext.current
    val launcher = BundledLaunchers.heroic
    val status = HeroicLauncher.status
    LaunchedEffect(Unit) { HeroicLauncher.ensure(ctx) }
    val narrow = LocalNarrowPane.current
    val tabs = listOf("heroic" to stringResource(R.string.heroic_session_tab))
    LiftHero(
        key = "heroic", tabs = tabs,
        onOpen = {}, onPrimary = { a.onLauncher(launcher) },
        modifier = modifier.clipToBounds(),
        start = if (narrow) 20.dp else 44.dp, bottom = 44.dp,
        background = {
            TiltedWall(count = HEROIC_WALL, driftMs = 58_000) { i -> WallCapsule(artBrush(hueOf("heroic$i"))) }
        },
        scrim = { WallFade() },
        words = {
            val colors = MaterialTheme.colorScheme
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.heroic_wordmark),
                    fontSize = if (narrow) 30.sp else 40.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp,
                    color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (status.installed) status.version?.let { stringResource(R.string.heroic_version, it) } ?: stringResource(R.string.heroic_missing)
                    else stringResource(R.string.heroic_missing),
                    fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        button = { click ->
            PrimaryButton(
                stringResource(if (status.installed) R.string.heroic_play else R.string.heroic_install),
                enabled = !s.busy, main = true, large = true, icon = Icons.Filled.PlayArrow,
                modifier = Modifier.testTag("play-heroic"), onClick = click,
            )
        },
        rows = { tab ->
            if (tab == "heroic") Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Note(stringResource(R.string.heroic_wine_note))
                Note(stringResource(R.string.heroic_games_note))
                if (s.sessionRunning && com.droiddeck.launcher.session.SessionState.program == HeroicLauncher.PROGRAM) {
                    Note(stringResource(R.string.heroic_session_note))
                }
            }
        },
    )
}

private const val HEROIC_WALL = 26
