package app.kuroka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.di.Graph
import app.kuroka.ui.components.EmptyState
import app.kuroka.ui.components.ErrorRetry
import app.kuroka.ui.components.KurokaTopBar
import app.kuroka.ui.components.Loading
import app.kuroka.ui.components.NetworkImage
import app.kuroka.ui.components.SectionHeader
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.theme.PosterShape
import app.kuroka.ui.viewmodel.KurokaViewModels
import app.kuroka.ui.viewmodel.ScheduleEntry
import app.kuroka.ui.viewmodel.ScheduleViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The schedule: what airs next, grouped by day, with the hour each episode comes at in the phone's
 * own time zone and how far away that is.
 *
 * The catalog gives the next hundred airing episodes; this screen pages through them and asks for
 * more when the list runs out, so a week of anime is a scroll rather than a search.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

@Composable
fun ScheduleScreen(
    onAnime: (Int) -> Unit,
    onBack: () -> Unit,
    model: ScheduleViewModel = viewModel(factory = KurokaViewModels),
) {
    val days by model.days.collectAsState()
    val loading by model.loading.collectAsState()
    val error by model.error.collectAsState()
    val library = Graph.library

    LaunchedEffect(Unit) { model.load() }

    Column(Modifier.fillMaxSize()) {
        KurokaTopBar(title = "What airs next", onBack = onBack, floating = false)
        Box(Modifier.fillMaxSize()) {
            when {
                loading && days.isEmpty() -> Loading(what = "Asking the catalog for the week…")
                error != null && days.isEmpty() -> ErrorRetry(message = error ?: "", onRetry = { model.load(force = true) })
                days.isEmpty() -> EmptyState(
                    title = "Nothing airing soon",
                    body = "The catalog has no episode coming up in the next few days.",
                    icon = Icons.Rounded.CalendarMonth,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 26.dp),
                ) {
                    days.forEach { day ->
                        item {
                            SectionHeader(
                                title = day.label,
                                subtitle = "${day.anime.size} episodes",
                            )
                        }
                        items(day.anime.size) { index ->
                            val entry = day.anime[index]
                            ScheduleRow(
                                entry = entry,
                                inLibrary = library.entryOf(entry.anime.id) != null,
                                onClick = { onAnime(entry.anime.id) },
                            )
                        }
                    }
                    item {
                        if (loading) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(22.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    color = LocalAccent.current,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else if (error != null) {
                            Text(
                                text = error ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(20.dp),
                            )
                        } else {
                            LaunchedEffect(Unit) { model.more() }
                        }
                    }
                }
            }
        }
    }
}

/** One airing episode: its poster, the hour it comes at, and how far away that is. */
@Composable
private fun ScheduleRow(entry: ScheduleEntry, inLibrary: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetworkImage(
            url = entry.anime.cover,
            contentDescription = entry.anime.title.display,
            modifier = Modifier
                .width(54.dp)
                .aspectRatio(2f / 3f)
                .clip(PosterShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            widthPx = 200,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.anime.title.display,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Episode ${entry.episode}" +
                    (entry.anime.format.label.takeIf { it != "—" }?.let { " · $it" } ?: "") +
                    (if (inLibrary) " · on your library" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (inLibrary) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = hourOf(entry.airingAt),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = entry.inLabel,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
        }
    }
}

/** The hour an episode comes at, in the phone's own time zone. */
private fun hourOf(at: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
