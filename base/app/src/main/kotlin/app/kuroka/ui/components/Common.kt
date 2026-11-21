package app.kuroka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kuroka.api.Anime
import app.kuroka.api.WatchProgress
import app.kuroka.ui.look.LocalImmersive
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.theme.PosterShape

/*
 * The pieces every screen is built from: a poster card, a shelf of them, a section's title, the
 * states of a page that is loading, empty or failed, and the stars a rating is given with. They
 * read the look's tokens rather than deciding colours of their own, so the same card is a grey
 * Material card in the Classic look and a glass one in the Immersive look.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

/** A poster's shape in a shelf: 2:3, as anime posters are. */
val PosterRatio = 2f / 3f

/**
 * One anime as a card: its poster, its title, the one line under it, and — when the library knows
 * it — the stars given and the bar of how far the last episode got.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnimeCard(
    anime: Anime,
    modifier: Modifier = Modifier,
    width: Int = 130,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    progress: WatchProgress? = null,
    stars: Int = 0,
    downloaded: Boolean = false,
    favourite: Boolean = false,
) {
    val accent = LocalAccent.current
    val longClick = onLongClick
    Column(
        modifier = modifier
            .width(width.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                // A long press is the card's own menu (a library row's editor, a shelf's "more").
                if (longClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = longClick)
                else Modifier.clickable(onClick = onClick)
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PosterRatio)
                .clip(PosterShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            NetworkImage(
                url = anime.cover,
                contentDescription = anime.title.display,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                widthPx = (width * 3),
            )
            if (progress != null && progress.fraction > 0f) {
                // How far the last episode got, as a thin bar along the poster's foot.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.fraction)
                            .height(3.dp)
                            .background(accent)
                    )
                }
            }
            if (downloaded || favourite) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (downloaded) BadgeIcon(Icons.Rounded.Download, accent)
                    if (favourite) BadgeIcon(Icons.Rounded.Favorite, Color(0xFFFF4F7B))
                }
            }
            if (progress != null) {
                // The episode it stopped in, so a shelf of posters says where each one is.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = progress.episodeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = anime.title.display,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground,
        )
        val line = anime.summaryLine
        if (line.isNotBlank() || stars > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stars > 0) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = "$stars of 5",
                        tint = accent,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = stars.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A small round badge over a poster (a download, a heart). */
@Composable
private fun BadgeIcon(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
    }
}

/** A shelf of posters, scrolling sideways, with the section's title above it. */
@Composable
fun PosterShelf(
    title: String,
    anime: List<Anime>,
    modifier: Modifier = Modifier,
    onAnime: (Anime) -> Unit,
    onMore: (() -> Unit)? = null,
    cardWidth: Int = 130,
    progressOf: (Anime) -> WatchProgress? = { null },
    starsOf: (Anime) -> Int = { 0 },
    downloadedOf: (Anime) -> Boolean = { false },
    favouriteOf: (Anime) -> Boolean = { false },
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = title, onMore = onMore)
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(anime.size) { index ->
                val item = anime[index]
                AnimeCard(
                    anime = item,
                    width = cardWidth,
                    onClick = { onAnime(item) },
                    progress = progressOf(item),
                    stars = starsOf(item),
                    downloaded = downloadedOf(item),
                    favourite = favouriteOf(item),
                )
            }
        }
    }
}

/** A section's title, with an optional "See all" at its right. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onMore != null) {
            TextButton(onClick = onMore) { Text("See all", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** A page that is asking: a spinner and, under it, what is being asked for. */
@Composable
fun Loading(modifier: Modifier = Modifier, what: String? = null) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = LocalAccent.current, strokeWidth = 2.5.dp)
            if (what != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = what,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A page that failed: what went wrong, and the button that asks again. */
@Composable
fun ErrorRetry(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    icon: ImageVector = Icons.Rounded.CloudOff,
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onRetry) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Try again")
                }
            }
        }
    }
}

/** A page with nothing in it. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector = Icons.Rounded.SearchOff,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = LocalImmersive.current.textTertiary,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            if (body != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(18.dp))
                action()
            }
        }
    }
}

/**
 * The stars a rating is given with: five, tapped to set 1 to 5, and the one being tapped again
 * takes the rating off (0 stars, as "not rated").
 */
@Composable
fun Stars(
    stars: Int,
    modifier: Modifier = Modifier,
    size: Int = 26,
    onRate: ((Int) -> Unit)? = null,
) {
    val accent = LocalAccent.current
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (index in 1..5) {
            val on = index <= stars
            val icon = Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = if (onRate == null) null else "Rate $index of 5",
                tint = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier
                    .size(size.dp)
                    .then(if (onRate == null) Modifier else Modifier.clickable {
                        onRate(if (index == stars) 0 else index)
                    }),
            )
            icon
        }
    }
}

/** A small pill of text (a genre, a format, a state). */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) accent.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** A round button with the accent on it: the play of an anime page, the download of an episode. */
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.PlayArrow,
    enabled: Boolean = true,
) {
    val accent = LocalAccent.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) accent else MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A one-line notice at the foot of a page ("Taking episode 3 down"). */
@Composable
fun Notice(
    text: String?,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    if (text.isNullOrBlank()) return
    val tokens = LocalImmersive.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.glassFill)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.text,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Close",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalAccent.current,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
        }
    }
}

/** "12:34", as a player's bar and a card's bar both want it. */
fun clockOf(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "${m.toString()}:${s.toString().padStart(2, '0')}"
}

/** A big number and its word, as the library's stats show them. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
