package app.kuroka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kuroka.host.cast.CastDevice
import app.kuroka.host.cast.CastDiscovery
import app.kuroka.host.cast.CastPhase
import app.kuroka.host.cast.CastState
import app.kuroka.ui.look.LocalImmersive
import app.kuroka.ui.theme.LocalAccent

/*
 * The casting sheet: the Chromecasts of the network, found with the phone's own mDNS, and — once
 * one is playing — what is on it, with the controls of a TV that is not in the room.
 *
 * A cast needs no account, no Google Play services and no closed library: the sheet lists what
 * `CastDiscovery` hears, and the player behind it speaks the Cast protocol itself.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

/** The button a player and a mini player carry: a cast icon, tinted while something is on a TV. */
@Composable
fun CastButton(
    state: CastState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    val on = state.casting
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = if (on) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
            contentDescription = if (on) "Casting to ${state.device?.name ?: "the TV"}" else "Cast to a TV",
            tint = if (on) accent else Color.White.copy(alpha = 0.85f),
        )
    }
}

/**
 * The sheet: the devices found (or the search for them), and the state of the one that is playing.
 * [onCast] is called with the device the user tapped; [onStop] takes the episode back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSheet(
    open: Boolean,
    state: CastState,
    onDismiss: () -> Unit,
    onCast: (CastDevice) -> Unit,
    onStop: () -> Unit,
    onPlayPause: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
) {
    if (!open) return
    val context = LocalContext.current
    val devices by CastDiscovery.devices.collectAsState()
    val tokens = LocalImmersive.current
    val accent = LocalAccent.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Look for devices while the sheet is open, and stop when it goes.
    DisposableEffect(open) {
        CastDiscovery.start(context)
        onDispose { CastDiscovery.stop() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = tokens.page,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (state.casting) "On ${state.device?.name ?: "the TV"}" else "Cast to",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = tokens.text,
                    )
                    Text(
                        text = if (state.casting) "The episode is playing on the TV"
                        else "Chromecasts and TVs on this network",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.textSecondary,
                    )
                }
                IconButton(onClick = { CastDiscovery.start(context) }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Look again", tint = tokens.textSecondary)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = tokens.textSecondary)
                }
            }

            if (state.casting) {
                CastingNow(
                    state = state,
                    accent = accent,
                    tokens = tokens,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onStop = onStop,
                )
            }

            when {
                state.phase == CastPhase.CONNECTING -> Row(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Opening ${state.device?.name ?: "the TV"}…", color = tokens.textSecondary,
                        style = MaterialTheme.typography.bodySmall)
                }

                state.phase == CastPhase.ERROR -> Text(
                    text = state.error ?: "The TV would not take the stream",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.destructive,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                )

                devices.isEmpty() -> Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.Tv, contentDescription = null, tint = tokens.textTertiary,
                        modifier = Modifier.size(30.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Looking for a TV…", style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "The phone and the Chromecast have to be on the same network. Some routers keep " +
                            "devices apart (\"client isolation\"); if nothing shows up, that is usually why.",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.textTertiary,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(devices.size) { index ->
                        val device = devices[index]
                        DeviceRow(
                            device = device,
                            selected = state.device?.id == device.id,
                            accent = accent,
                            onClick = { onCast(device) },
                        )
                    }
                }
            }
        }
    }
}

/** One device of the list: its name, its model, and the accent when it is the one playing. */
@Composable
private fun DeviceRow(
    device: CastDevice,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val look = LocalImmersive.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(look.glassFill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (selected) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                contentDescription = null,
                tint = if (selected) accent else look.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) accent else look.text,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(device.model.takeIf { it.isNotBlank() }, device.host.hostAddress)
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = look.textTertiary,
                maxLines = 1,
            )
        }
    }
}

/** What is on the TV: its title, the bar, and the three controls that matter from a phone. */
@Composable
private fun CastingNow(
    state: CastState,
    accent: Color,
    tokens: app.kuroka.ui.look.ImmersiveTokens,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.raised)
            .padding(14.dp)
    ) {
        Text(
            text = state.item?.title ?: "The episode",
            style = MaterialTheme.typography.titleSmall,
            color = tokens.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = state.item?.subtitle ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clockOf(state.positionMs), style = MaterialTheme.typography.labelSmall, color = tokens.textTertiary)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.12f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(state.fraction)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(accent)
                )
            }
            Text(clockOf(state.durationMs), style = MaterialTheme.typography.labelSmall, color = tokens.textTertiary)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSeek(state.positionMs - 10_000L) }) {
                Text("-10s", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(52.dp)) {
                Box(
                    modifier = Modifier.size(46.dp).clip(CircleShape).background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            IconButton(onClick = { onSeek(state.positionMs + 10_000L) }) {
                Text("+10s", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Take it back",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onStop)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
