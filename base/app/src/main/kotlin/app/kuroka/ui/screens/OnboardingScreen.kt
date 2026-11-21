package app.kuroka.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.api.Accent
import app.kuroka.api.Look
import app.kuroka.ui.components.AccentButton
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.viewmodel.KurokaViewModels
import app.kuroka.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

/*
 * The setup, once: four pages that say what Kuroka is and let the user pick the two things that are
 * worth picking before the first search — the look, and whether to sign in to AniList now or later.
 *
 * It is a page of the graph rather than a dialog of the activity, so it goes away with a back stack
 * pop and never comes back: the choice is written as `onboardingDone`, and the home page is where
 * the graph lands from then on.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../LICENSE).
 */

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    model: SettingsViewModel = viewModel(factory = KurokaViewModels),
) {
    val settings by model.settings.collectAsState()
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { 4 })

    val finish = {
        model.onboardingDone()
        onDone()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> Page(
                    icon = Icons.Rounded.PlayCircle,
                    title = "Kuroka",
                    body = "Anime with no ads in it, no account of its own and nothing sent anywhere " +
                        "but the catalog and the source an episode comes from.",
                    lines = listOf(
                        "The catalog is AniList's: titles, posters, genres and airing times.",
                        "The episodes come from public sources, asked in the order you set.",
                        "Free software under the GPL-3.0, with its source in public.",
                    ),
                )

                1 -> Page(
                    icon = Icons.Rounded.Palette,
                    title = "How it looks",
                    body = "Two looks, and a colour. Both can be changed later, in Settings, Display.",
                    lines = emptyList(),
                )

                2 -> Page(
                    icon = Icons.Rounded.Download,
                    title = "Episodes on the phone",
                    body = "An episode can be taken down and played with no network at all — on a " +
                        "plane, on a phone connection you would rather not spend, or when a source is " +
                        "not answering.",
                    lines = listOf(
                        "The subtitles come down with it.",
                        "A finished download can be shared as a video file.",
                        "Wi-Fi only, or any network: your call, in Settings, Downloads.",
                    ),
                )

                else -> Page(
                    icon = Icons.Rounded.Sync,
                    title = "Your lists",
                    body = "Everything works signed out: the lists, the history and the favourites stay " +
                        "on the phone. Signing in to AniList brings your own lists down and pushes what " +
                        "you watch here up.",
                    lines = listOf(
                        "The sign-in is AniList's, in a browser; no secret is kept here.",
                        "Its token goes in the phone's encrypted storage.",
                        "You can sign in later, from the library page.",
                    ),
                )
            }
        }

        // The dots, and the two buttons.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 26.dp)
        ) {
            if (pager.currentPage == 1) {
                LookChoice(
                    look = settings.look,
                    onLook = model::setLook,
                    accent = settings.accent,
                    onAccent = model::setAccent,
                )
                Spacer(Modifier.height(18.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(4) { index ->
                    val on = index == pager.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .width(if (on) 22.dp else 8.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (on) accent else MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pager.currentPage > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 20.dp, vertical = 13.dp)
                            .clickable { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Back",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
                AccentButton(
                    text = if (pager.currentPage == 3) "Start watching" else "On",
                    onClick = {
                        if (pager.currentPage == 3) finish()
                        else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (pager.currentPage < 3) {
                Text(
                    text = "Skip the setup",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .clickable(onClick = finish),
                )
            }
        }
    }
}

/** One page of the setup: an icon, a title, what it says and the points under it. */
@Composable
private fun Page(
    icon: ImageVector,
    title: String,
    body: String,
    lines: List<String>,
) {
    val accent = LocalAccent.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .padding(bottom = 210.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (lines.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                lines.forEach { line ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }
    }
}

/** The two choices the second page offers: a look and a colour. */
@Composable
private fun LookChoice(
    look: Look,
    onLook: (Look) -> Unit,
    accent: Accent,
    onAccent: (Accent) -> Unit,
) {
    val current = LocalAccent.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Look.entries.forEach { option ->
                val on = option == look
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (on) current.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceContainer
                        )
                        .clickable { onLook(option) }
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = if (option == Look.IMMERSIVE) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                        contentDescription = null,
                        tint = if (on) current else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) current else MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = if (option == Look.IMMERSIVE) "Dark glass" else "Material",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Accent.entries.forEach { option ->
                val on = option == accent
                Box(
                    modifier = Modifier
                        .size(if (on) 36.dp else 30.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color(option.argb))
                        .clickable { onAccent(option) }
                )
            }
        }
    }
}
