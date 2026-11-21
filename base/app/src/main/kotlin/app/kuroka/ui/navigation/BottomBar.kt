package app.kuroka.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kuroka.api.Look
import app.kuroka.di.Graph
import app.kuroka.host.downloads.DownloadRepo
import app.kuroka.ui.look.LocalImmersive
import app.kuroka.ui.look.glass
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.theme.LocalLookKind

/*
 * The five tabs at the foot of the app: home, search, library, downloads and settings. In the
 * Immersive look the bar is a floating glass pill with the tab you are on tinted by the accent; in
 * the Classic look it is Material's own navigation bar over the page's grey, its tabs drawn here.
 *
 * The downloads tab carries the number of episodes coming down, so a user who left the app knows
 * something is still happening without opening the page.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Rounded.Home),
    Tab(Routes.SEARCH, "Search", Icons.Rounded.Search),
    Tab(Routes.LIBRARY, "Library", Icons.Rounded.VideoLibrary),
    Tab(Routes.DOWNLOADS, "Downloads", Icons.Rounded.Download),
    Tab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
)

@Composable
fun BottomBar(
    route: String?,
    onTab: (String) -> Unit,
    downloads: DownloadRepo = Graph.downloads,
) {
    val look = LocalLookKind.current
    val active by downloads.active.collectAsState()
    if (look == Look.IMMERSIVE) {
        val tokens = LocalImmersive.current
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(22.dp))
                    .navigationBarsPadding()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    ImmersiveTab(
                        tab = tab,
                        selected = route == tab.route,
                        badge = if (tab.route == Routes.DOWNLOADS) active else 0,
                        accent = tokens.accent,
                        text = tokens.text,
                        muted = tokens.textSecondary,
                        onClick = { onTab(tab.route) },
                    )
                }
            }
        }
    } else {
        androidx.compose.material3.NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
            modifier = Modifier.navigationBarsPadding(),
        ) {
            tabs.forEach { tab ->
                ClassicTab(
                    tab = tab,
                    selected = route == tab.route,
                    badge = if (tab.route == Routes.DOWNLOADS) active else 0,
                    onClick = { onTab(tab.route) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One tab of the Classic bar: its icon on the pill of the one you are on, its word, and the dot of
 * the downloads. Material's own item would draw the same thing, but this version of Material3 does
 * not offer it by the name its bar and its colours use, so the tab is drawn here — over the same
 * tokens, and the two looks of the app stay two drawings of one bar rather than one borrowed and one
 * hand-made. The bar's row is what gives a tab its share of the width, so the tab takes a
 * modifier and the bar passes the weight.
 */
@Composable
private fun ClassicTab(
    tab: Tab,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
                .padding(horizontal = 18.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = if (selected) accent else muted,
                modifier = Modifier.size(23.dp),
            )
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(accent)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) accent else muted,
        )
    }
}

/** One tab of the Immersive bar: its icon, its word, and the dot of the downloads. */
@Composable
private fun ImmersiveTab(
    tab: Tab,
    selected: Boolean,
    badge: Int,
    accent: Color,
    text: Color,
    muted: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = if (selected) accent else muted,
                modifier = Modifier.size(22.dp),
            )
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(start = 2.dp)
                        .size(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(accent)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) text else muted,
        )
        // The tab you are on carries a short bar under its word, as the look's own tabs do.
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .width(16.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) accent else Color.Transparent)
        )
    }
}
