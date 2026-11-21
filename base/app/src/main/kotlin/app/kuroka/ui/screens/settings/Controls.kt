package app.kuroka.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kuroka.ui.components.KurokaTopBar
import app.kuroka.ui.components.SettingGroup
import app.kuroka.ui.components.SettingRow
import app.kuroka.ui.theme.LocalAccent

/*
 * The pieces every settings page is built from: the page's own scaffold (a bar, a scroll), a row
 * with a switch, a row of choices, a row with a slider and a row with − and + around a number.
 *
 * Keeping them in one file is what makes the seven pages read alike: a page is a list of rows, and
 * nothing in it decides how a row looks.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../LICENSE).
 */

/** A settings page: its bar, and the rows scrolling under it. */
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        KurokaTopBar(title = title, onBack = onBack, actions = actions)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            content()
            Spacer(Modifier.height(34.dp))
        }
    }
}

/** A row with a switch at its right. */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val accent = LocalAccent.current
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = { if (enabled) onToggle(!on) },
        control = {
            Switch(
                checked = on,
                onCheckedChange = { if (enabled) onToggle(it) },
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.surface,
                    checkedTrackColor = accent,
                ),
            )
        },
    )
}

/** A row of choices, as pills (a look, a quality, a fit mode). */
@Composable
fun ChoiceRow(
    title: String,
    subtitle: String? = null,
    options: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    perRow: Int = 3,
) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth()) {
        SettingRow(title = title, subtitle = subtitle)
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { option ->
                        val on = option == selected
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (on) accent.copy(alpha = 0.18f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .clickable { onPick(option) }
                                .padding(horizontal = 6.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = option,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                color = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A row with a slider under it (a size, an offset, a budget). */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValue: (Float) -> Unit,
    steps: Int = 0,
    subtitle: String? = null,
) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth()) {
        SettingRow(
            title = title,
            subtitle = subtitle,
            control = {
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            },
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValue,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        )
    }
}

/** A row with − and + around a number (how many downloads at once, how many days to keep them). */
@Composable
fun StepperRow(
    title: String,
    subtitle: String? = null,
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    val accent = LocalAccent.current
    SettingRow(
        title = title,
        subtitle = subtitle,
        control = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StepButton("−", enabled = value > min) { onChange((value - step).coerceAtLeast(min)) }
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.width(34.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                StepButton("+", enabled = value < max) { onChange((value + step).coerceAtMost(max)) }
            }
        },
    )
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (enabled) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

/** A word of explanation under a group, for the settings that need one. */
@Composable
fun SettingNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    )
}
