package app.kuroka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.api.SourceState
import app.kuroka.ui.components.KurokaTopBar
import app.kuroka.ui.components.Pill
import app.kuroka.ui.components.SettingGroup
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.viewmodel.KurokaViewModels
import app.kuroka.ui.viewmodel.SettingsViewModel

/*
 * The sources: the ones an episode can come from, in the order the resolver asks them, each with a
 * switch, the languages it has, and a mark when it has stopped answering. A source that fails three
 * times in a row is left out for five minutes, so a page still loads while it recovers; "Try again"
 * forgets that count at once.
 *
 * The order matters: the first enabled source that lists an anime wins, and its bundle is what the
 * episodes and the servers come from (like Orpheon's source order, GPL-3.0, © kota-zo).
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../LICENSE).
 */

@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    model: SettingsViewModel = viewModel(factory = KurokaViewModels),
) {
    val sources by model.sources.collectAsState()
    val settings by model.settings.collectAsState()
    val accent = LocalAccent.current
    var server by remember(settings.kitsuneServer) { mutableStateOf(settings.kitsuneServer) }

    val ordered = remember(sources) { sources.sortedBy { it.order } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        KurokaTopBar(title = "Sources", onBack = onBack)

        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "The resolver asks these in order, and the first one that has an anime gives its " +
                    "episodes. A source that fails three times is left out for five minutes, so a page " +
                    "still loads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            SettingGroup("The sources")
            ordered.forEachIndexed { index, source ->
                SourceRow(
                    source = source,
                    first = index == 0,
                    last = index == ordered.size - 1,
                    onToggle = { model.setSourceEnabled(source.id, it) },
                    onUp = { model.moveSource(source.id, up = true) },
                    onDown = { model.moveSource(source.id, up = false) },
                    onReset = { model.resetSource(source.id) },
                )
            }

            SettingGroup("Your own Kitsune server")
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                label = { Text("Its address") },
                placeholder = { Text("https://kitsune.example.org") },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill(text = "Keep it", selected = server.isNotBlank(), onClick = { model.setKitsuneServer(server.trim()) })
                Pill(text = "Take it off", onClick = {
                    server = ""
                    model.setKitsuneServer("")
                })
            }
            Text(
                text = "A server of your own is asked before the built-in sources, and can carry anime " +
                    "they do not have. Nothing is sent to it but the title you search for.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            SettingGroup("How they are asked")
            Text(
                text = "${sources.count { it.enabled }} of ${sources.size} on" +
                    (sources.count { it.down }.let { if (it > 0) " · $it not answering right now" else "" }) +
                    " · the answers of a source are remembered for an hour, and a stream's for five minutes.",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(26.dp))
        }
    }
}

/** One source: its switch, its place in the order, what it has, and whether it is answering. */
@Composable
private fun SourceRow(
    source: SourceState,
    first: Boolean,
    last: Boolean,
    onToggle: (Boolean) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onReset: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (source.down) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (source.enabled) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(7.dp))
                if (source.hasDub) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(accent.copy(alpha = 0.16f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "DUB",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (source.down) {
                    Spacer(Modifier.width(7.dp))
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = "Not answering",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Text(
                text = listOfNotNull(
                    source.note.takeIf { it.isNotBlank() },
                    source.languages.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.uppercase() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (source.down) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Left out for a while",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Try again",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onReset)
                            .padding(vertical = 2.dp, horizontal = 2.dp),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onUp, enabled = !first) {
                Icon(
                    imageVector = Icons.Rounded.ArrowUpward,
                    contentDescription = "Ask it sooner",
                    tint = if (first) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(17.dp),
                )
            }
            IconButton(onClick = onDown, enabled = !last) {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = "Ask it later",
                    tint = if (last) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Switch(
            checked = source.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.surface,
                checkedTrackColor = accent,
            ),
        )
    }
}
