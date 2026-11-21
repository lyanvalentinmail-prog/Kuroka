package app.kuroka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kuroka.api.Look
import app.kuroka.ui.look.LocalImmersive
import app.kuroka.ui.look.glass
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.theme.LocalLookKind

/*
 * The bars a screen is framed by: a top bar (a title, a back arrow, and whatever actions the page
 * needs on its right) and a search field. Both read the look's tokens, so the Immersive look gets
 * a glass bar floating over the page and the Classic look gets Material's own.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

/** A page's top bar. [actions] are drawn at its right, before nothing else. */
@Composable
fun KurokaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    floating: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    val look = LocalLookKind.current
    val tokens = LocalImmersive.current
    val barModifier = modifier
        .fillMaxWidth()
        .then(
            if (floating && look == Look.IMMERSIVE) Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                .glass(RoundedCornerShape(18.dp))
            else Modifier.background(MaterialTheme.colorScheme.background)
        )
        .statusBarsPadding()
    Row(
        modifier = barModifier.padding(start = if (onBack == null) 16.dp else 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = if (floating) tokens.text else MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Box(Modifier.weight(1f)) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (floating && look == Look.IMMERSIVE) tokens.text
                    else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (floating && look == Look.IMMERSIVE) tokens.textSecondary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        actions()
    }
}

/** An action of a top bar, as a round button. */
@Composable
fun BarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color? = null,
    badge: Boolean = false,
) {
    val tokens = LocalImmersive.current
    val accent = LocalAccent.current
    IconButton(onClick = onClick) {
        Box {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint ?: if (LocalLookKind.current == Look.IMMERSIVE) tokens.text
                else MaterialTheme.colorScheme.onBackground,
            )
            if (badge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
        }
    }
}

/** The search field: a pill with a glass in the Immersive look, and the keyboard's search key. */
@Composable
fun SearchField(
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "Search an anime",
    onSearch: (() -> Unit)? = null,
    autoFocus: Boolean = false,
) {
    val look = LocalLookKind.current
    val tokens = LocalImmersive.current
    val accent = LocalAccent.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(50))
            .then(
                if (look == Look.IMMERSIVE) Modifier.glass(RoundedCornerShape(50))
                else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
            )
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = if (look == Look.IMMERSIVE) tokens.textSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (look == Look.IMMERSIVE) tokens.textTertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (look == Look.IMMERSIVE) tokens.text else MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Clear",
                tint = if (look == Look.IMMERSIVE) tokens.textSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable { onValue("") },
            )
        }
    }
}

/** A row of a settings page: a title, a word under it, and a control at its right. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    control: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (control != null) {
            Spacer(Modifier.width(12.dp))
            control()
        }
    }
}

/** The heading of a group of settings ("Player", "Subtitles"). */
@Composable
fun SettingGroup(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = LocalAccent.current,
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp),
    )
}

/** A thin line between two settings rows. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(1.dp)
            .background(LocalImmersive.current.separator)
    )
}

/** A row of chips that scroll sideways (the genres of a filter sheet). */
@Composable
fun ChipRow(
    items: List<String>,
    selected: Set<String>,
    modifier: Modifier = Modifier,
    onToggle: (String) -> Unit,
    label: (String) -> String = { it },
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items.size) { index ->
            val item = items[index]
            Pill(text = label(item), selected = item in selected, onClick = { onToggle(item) })
        }
    }
}
