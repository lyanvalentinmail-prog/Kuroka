package app.kuroka.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.api.DownloadItem
import app.kuroka.ui.components.AccentButton
import app.kuroka.ui.components.SettingGroup
import app.kuroka.ui.components.SettingRow
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.viewmodel.KurokaViewModels
import app.kuroka.ui.viewmodel.SettingsViewModel
import app.kuroka.update.UpdateState

/*
 * The updates: Kuroka is not on a store, so a release is asked of its own GitHub page. The app reads
 * the newest release, compares its version, and offers the APK that matches this phone's ABI —
 * arm64, armv7 or the universal one — which Android then installs over the old one.
 *
 * A build with no release page (a local one, or a debug one) says so rather than pretending to look.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../LICENSE).
 */

@Composable
fun UpdatesPage(
    onBack: () -> Unit,
    model: SettingsViewModel = viewModel(factory = KurokaViewModels),
) {
    val settings by model.settings.collectAsState()
    val state by model.updateState.collectAsState()
    val accent = LocalAccent.current

    SettingsPage(
        title = "Updates",
        onBack = onBack,
        actions = {
            if (model.canCheckUpdates) {
                app.kuroka.ui.components.BarAction(
                    icon = Icons.Rounded.SystemUpdate,
                    contentDescription = "Ask again",
                    onClick = { model.checkForUpdates() },
                )
            }
        },
    ) {
        SettingGroup("This install")
        SettingRow(
            title = "Version ${model.version}",
            subtitle = if (model.canCheckUpdates) "Releases are read from github.com/${model.updateRepo}"
            else "This build does not know its release page, so it cannot ask for one",
        )
        SwitchRow(
            title = "Ask for a release",
            subtitle = "Once a day, when the app is opened. A beta install is offered betas too.",
            on = settings.updateCheck,
            onToggle = model::setUpdateCheck,
            enabled = model.canCheckUpdates,
        )

        SettingGroup("What the asking found")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            when (val found = state) {
                UpdateState.None -> Text(
                    text = "Nothing asked yet, or this install is the newest there is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Asking GitHub for the newest release…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is UpdateState.Available -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${found.version} is there" + if (found.beta) " (a beta)" else "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                    if (found.notes.isNotBlank()) {
                        Text(
                            text = found.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Text(
                        text = found.assets.joinToString("\n") { asset ->
                            "${asset.name} · ${DownloadItem.size(asset.bytes)} · ${asset.abi}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AccentButton(
                            text = "Take it down",
                            onClick = { model.downloadUpdate() },
                            icon = Icons.Rounded.Download,
                            modifier = Modifier.weight(1f),
                        )
                        AccentButton(text = "The release page", onClick = { model.openUpdatePage() })
                    }
                }

                is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${found.version} is coming down · ${DownloadItem.size(found.bytes)} of " +
                            DownloadItem.size(found.total),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    LinearProgressIndicator(
                        progress = { found.fraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = accent,
                    )
                }

                is UpdateState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${found.version} is on the phone",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                    Text(
                        text = "Android installs it over this one; the library, the downloads and the " +
                            "settings all stay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AccentButton(text = "Install it", onClick = { model.installUpdate() })
                }

                is UpdateState.Failed -> Text(
                    text = found.why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SettingRow(
            title = "Ask now",
            subtitle = "The newest release is read again, betas included.",
            onClick = { model.checkForUpdates() },
        )
        SettingRow(
            title = "The releases page",
            subtitle = "Every version and its APKs, in the browser.",
            onClick = { model.openUpdatePage() },
        )
        SettingNote(
            "An APK is signed with the same key as this install, so Android takes it as an update " +
                "rather than another app. A debug build cannot be updated by a release one."
        )
    }
}
