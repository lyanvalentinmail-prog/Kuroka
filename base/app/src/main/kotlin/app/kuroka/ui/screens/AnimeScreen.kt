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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.api.Anime
import app.kuroka.api.Audio
import app.kuroka.api.Episode
import app.kuroka.api.EpisodeBundle
import app.kuroka.api.ListStatus
import app.kuroka.api.SeasonEntry
import app.kuroka.di.Graph
import app.kuroka.ui.components.AccentButton
import app.kuroka.ui.components.EmptyState
import app.kuroka.ui.components.ErrorRetry
import app.kuroka.ui.components.Loading
import app.kuroka.ui.components.NetworkImage
import app.kuroka.ui.components.Notice
import app.kuroka.ui.components.Pill
import app.kuroka.ui.components.PosterShelf
import app.kuroka.ui.components.SectionHeader
import app.kuroka.ui.components.SettingGroup
import app.kuroka.ui.components.Stars
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.theme.PosterShape
import app.kuroka.ui.viewmodel.AnimeViewModel
import app.kuroka.ui.viewmodel.KurokaViewModels

/*
 * One anime's page: its banner and poster, what it is about, what the catalog says of it (its kind,
 * its season, its score, its studio, its genres), and what the user can do with it — play it, take
 * episodes down, put it on a list, give it stars, mark it a favourite, share its page.
 *
 * Under that, the episodes: the source that answered, the SUB/DUB switch, and the list itself with
 * a filler mark, a thumbnail and a download button on every row. A sheet switches the source, and
 * another one switches the season of the story.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeScreen(
    animeId: Int,
    onAnime: (Int) -> Unit,
    onWatch: (Double, Audio, String?, String?, String?) -> Unit,
    onBack: () -> Unit,
    model: AnimeViewModel = viewModel(factory = KurokaViewModels),
) {
    val anime by model.anime.collectAsState()
    val loading by model.loading.collectAsState()
    val error by model.error.collectAsState()
    val bundle by model.bundle.collectAsState()
    val bundles by model.bundles.collectAsState()
    val audio by model.audio.collectAsState()
    val seasons by model.seasons.collectAsState()
    val relations by model.relations.collectAsState()
    val recommendations by model.recommendations.collectAsState()
    val entry by model.entry.collectAsState()
    val favourite by model.favourite.collectAsState()
    val progress by model.progress.collectAsState()
    val notice by model.notice.collectAsState()
    val downloads = Graph.downloads
    val items by downloads.items.collectAsState()

    val context = LocalContext.current
    var sourcesSheet by remember { mutableStateOf(false) }
    var seasonsSheet by remember { mutableStateOf(false) }
    var listSheet by remember { mutableStateOf(false) }
    var descriptionOpen by remember { mutableStateOf(false) }

    LaunchedEffect(animeId) { model.load(animeId) }
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(3200)
            model.clearNotice()
        }
    }

    val episodes = remember(bundle, audio) { model.episodes() }
    val mine = remember(items, animeId) { items.filter { it.animeId == animeId } }

    Box(Modifier.fillMaxSize()) {
        when {
            loading && anime == null -> Loading(what = "Opening the page…")
            error != null && anime == null -> ErrorRetry(message = error ?: "", onRetry = { model.refresh() })
            anime == null -> EmptyState(title = "This anime is not there any more")
            else -> {
                val found = anime!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 30.dp),
                ) {
                    item {
                        Header(
                            anime = found,
                            favourite = favourite != null,
                            onBack = onBack,
                            onFavourite = { model.toggleFavourite() },
                            onShare = { share(context, found) },
                            onSeasons = if (seasons.hasSeveral) ({ seasonsSheet = true }) else null,
                        )
                    }
                    item {
                        Body(
                            anime = found,
                            entry = entry,
                            progress = progress,
                            descriptionOpen = descriptionOpen,
                            onDescription = { descriptionOpen = !descriptionOpen },
                            onList = { listSheet = true },
                            onRate = { model.setScore(it) },
                            onPlay = {
                                val next = nextEpisode(found, episodes, entry?.progress ?: 0, progress)
                                onWatch(next.first, audio, next.second?.id, null, bundle?.source)
                            },
                            onDownloadNext = { model.downloadNext(5) },
                        )
                    }

                    // The episodes: the source, the audio, and the list.
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Episodes",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            if (model.audios().size > 1) {
                                AudioSwitch(
                                    audio = audio,
                                    audios = model.audios(),
                                    onAudio = { model.setAudio(it) },
                                )
                            }
                            if (bundles.size > 1) {
                                IconButton(onClick = { sourcesSheet = true }) {
                                    Icon(Icons.Rounded.SwapHoriz, contentDescription = "Another source",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item {
                        val source = bundle?.source
                        Text(
                            text = when {
                                episodes.isEmpty() -> "No source has the episodes of this anime yet"
                                source.isNullOrBlank() -> "${episodes.size} episodes"
                                else -> "${episodes.size} episodes from $source"
                            } + (found.episodes?.let { if (it > 0 && it != episodes.size) " · the catalog says $it" else "" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }

                    if (episodes.isEmpty()) {
                        item {
                            EmptyState(
                                title = "No episodes yet",
                                body = "The sources Kuroka has do not list this anime. Another one may: " +
                                    "Settings, Sources.",
                                icon = Icons.Rounded.Tv,
                                modifier = Modifier.height(220.dp),
                            )
                        }
                    } else {
                        items(episodes.size) { index ->
                            val episode = episodes[index]
                            val downloaded = mine.any {
                                it.episodeNumber == episode.number &&
                                    it.state == app.kuroka.api.DownloadState.COMPLETED
                            }
                            val coming = mine.any {
                                it.episodeNumber == episode.number && it.state.isActive
                            }
                            val seen = (entry?.progress ?: 0) >= episode.number.toInt()
                            EpisodeRow(
                                episode = episode,
                                anime = found,
                                downloaded = downloaded,
                                coming = coming,
                                seen = seen,
                                at = progress?.takeIf { it.episodeNumber == episode.number }?.fraction ?: 0f,
                                onClick = { onWatch(episode.number, audio, episode.id, null, episode.source.ifBlank { bundle?.source }) },
                                onDownload = { model.download(episode) },
                            )
                        }
                    }

                    if (relations.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Related",
                                subtitle = "The side stories, the summaries and the other tellings",
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(relations.size) { index ->
                                    val relation = relations[index]
                                    Column(
                                        modifier = Modifier
                                            .width(120.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { onAnime(relation.id) }
                                    ) {
                                        NetworkImage(
                                            url = relation.cover,
                                            contentDescription = relation.title.display,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(2f / 3f)
                                                .clip(PosterShape),
                                            widthPx = 360,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = relation.type.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalAccent.current,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = relation.title.display,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (recommendations.isNotEmpty()) {
                        item {
                            PosterShelf(
                                title = "If you like this",
                                anime = recommendations,
                                onAnime = { onAnime(it.id) },
                            )
                        }
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.BottomCenter)) {
            Notice(text = notice, onDismiss = { model.clearNotice() })
        }
    }

    if (sourcesSheet) {
        SourceSheet(
            bundles = bundles,
            current = bundle,
            onPick = { model.selectBundle(it); sourcesSheet = false },
            onDismiss = { sourcesSheet = false },
        )
    }
    if (seasonsSheet) {
        SeasonSheet(
            entries = seasons.entries,
            currentId = seasons.currentId,
            onPick = { onAnime(it.id); seasonsSheet = false },
            onDismiss = { seasonsSheet = false },
        )
    }
    if (listSheet) {
        ListSheet(
            current = entry?.status,
            stars = entry?.score ?: 0,
            onStatus = { model.setStatus(it); listSheet = false },
            onRate = { model.setScore(it) },
            onRemove = { model.removeFromLibrary(); listSheet = false },
            onDismiss = { listSheet = false },
        )
    }
}

/** The banner, the poster over it, and the page's own buttons. */
@Composable
private fun Header(
    anime: Anime,
    favourite: Boolean,
    onBack: () -> Unit,
    onFavourite: () -> Unit,
    onShare: () -> Unit,
    onSeasons: (() -> Unit)?,
) {
    val accent = LocalAccent.current
    Box {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            NetworkImage(
                url = anime.banner ?: anime.cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                widthPx = 1000,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.55f),
                                Color.Transparent,
                                MaterialTheme.colorScheme.background,
                            )
                        )
                    )
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            if (onSeasons != null) {
                IconButton(onClick = onSeasons, modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))) {
                    Icon(Icons.Rounded.Tv, contentDescription = "The seasons of this story", tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onShare, modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))) {
                Icon(Icons.Rounded.Share, contentDescription = "Share", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onFavourite, modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))) {
                Icon(
                    imageVector = if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (favourite) "Take the heart off" else "Mark it a favourite",
                    tint = if (favourite) Color(0xFFFF4F7B) else Color.White,
                )
            }
        }
    }
}

/** The poster, the title, what it is, and the two buttons of the page. */
@Composable
private fun Body(
    anime: Anime,
    entry: app.kuroka.api.LibraryEntry?,
    progress: app.kuroka.api.WatchProgress?,
    descriptionOpen: Boolean,
    onDescription: () -> Unit,
    onList: () -> Unit,
    onRate: (Int) -> Unit,
    onPlay: () -> Unit,
    onDownloadNext: () -> Unit,
) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            NetworkImage(
                url = anime.cover,
                contentDescription = anime.title.display,
                modifier = Modifier
                    .width(112.dp)
                    .aspectRatio(2f / 3f)
                    .clip(PosterShape),
                widthPx = 400,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = anime.title.display,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (anime.title.romaji != null && anime.title.romaji != anime.title.display) {
                    Text(
                        text = anime.title.romaji ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    anime.averageScore?.let {
                        Pill(text = "★ ${(it / 10.0).let { value -> String.format("%.1f", value) }}")
                    }
                    anime.format.label.takeIf { it != "—" }?.let { Pill(text = it) }
                    anime.seasonLabel?.let { Pill(text = it) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = listOfNotNull(
                        anime.episodes?.let { if (it > 0) "$it episodes" else null },
                        anime.duration?.let { "$it min each" },
                        anime.status.label.takeIf { it != "—" },
                        anime.studios.firstOrNull(),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (anime.nextAiring != null) {
                    Text(
                        text = "Episode ${anime.nextAiring?.episode} airs in " +
                            daysLabel(anime.nextAiring?.timeUntilAiring ?: 0L),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val label = when {
                progress != null && !progress.completed -> "Go on · E${progress.episodeNumber.toInt()}"
                (entry?.progress ?: 0) > 0 -> "Episode ${(entry?.progress ?: 0) + 1}"
                else -> "Play"
            }
            AccentButton(text = label, onClick = onPlay, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onDownloadNext)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Rounded.Download, contentDescription = "Take the next 5 episodes down",
                    tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(Modifier.height(12.dp))

        // The list and the stars, side by side: what the user has done with this anime.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (entry != null) accent.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable(onClick = onList)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text(
                    text = entry?.status?.label ?: "Add to your library",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (entry != null) accent else MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Stars(stars = entry?.score ?: 0, onRate = onRate, size = 24)
        }

        anime.description?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(Modifier.height(14.dp))
            val text = description.replace(Regex("<[^>]*>"), "").replace("(Source:.*)$".toRegex(), "").trim()
            Text(
                text = if (descriptionOpen || text.length < 190) text else text.take(190) + "…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (descriptionOpen) Int.MAX_VALUE else 4,
                modifier = Modifier.clickable(onClick = onDescription),
            )
            if (text.length >= 190) {
                Text(
                    text = if (descriptionOpen) "Less" else "More",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onDescription).padding(top = 4.dp),
                )
            }
        }

        if (anime.genres.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                anime.genres.take(4).forEach { genre -> Pill(text = genre) }
            }
        }
    }
}

/** The episode to play when the page's big button is tapped. */
private fun nextEpisode(
    anime: Anime,
    episodes: List<Episode>,
    watched: Int,
    progress: app.kuroka.api.WatchProgress?,
): Pair<Double, Episode?> {
    if (progress != null && !progress.completed && progress.positionMs > 30_000L) {
        val episode = episodes.firstOrNull { it.number == progress.episodeNumber }
        if (episode != null) return progress.episodeNumber to episode
    }
    val next = episodes.sortedBy { it.number }.firstOrNull { it.number > watched }
        ?: episodes.firstOrNull()
    return (next?.number ?: 1.0) to next
}

/** SUB / DUB, as the switch an episode list carries. */
@Composable
private fun AudioSwitch(audio: Audio, audios: List<Audio>, onAudio: (Audio) -> Unit) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(2.dp)
    ) {
        audios.forEach { option ->
            val on = option == audio
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) accent else Color.Transparent)
                    .clickable { onAudio(option) }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (option == Audio.DUB) "Dub" else "Sub",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (on) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One episode of the list: its number, its title, its thumbnail, its marks and its download. */
@Composable
private fun EpisodeRow(
    episode: Episode,
    anime: Anime,
    downloaded: Boolean,
    coming: Boolean,
    seen: Boolean,
    at: Float,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    val accent = LocalAccent.current
    val meta = anime.toRef().meta(episode.number)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(104.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            NetworkImage(
                url = episode.image ?: meta?.image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                widthPx = 320,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(17.dp))
            }
            if (at > 0.02f) {
                Box(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Box(Modifier.fillMaxWidth(at).height(3.dp).background(accent))
                }
            }
            if (seen) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = "Watched", tint = accent,
                        modifier = Modifier.size(12.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = episode.label(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (seen) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val marks = buildList {
                meta?.title?.takeIf { it.isNotBlank() && it != episode.title }?.let { add(it) }
                episode.duration?.let { add("$it min") }
                episode.airDate?.let { add(it) }
                if (episode.filler || meta?.filler == true) add("Filler")
                if (episode.recap || meta?.recap == true) add("Recap")
                if (downloaded) add("On the phone")
                if (coming) add("Coming down")
            }
            if (marks.isNotEmpty()) {
                Text(
                    text = marks.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (episode.filler || episode.recap) accent
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDownload) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = if (downloaded) "On the phone" else "Take it down",
                tint = when {
                    downloaded -> accent
                    coming -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The sources that answered, for the sheet that switches between them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(
    bundles: List<EpisodeBundle>,
    current: EpisodeBundle?,
    onPick: (EpisodeBundle) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
            SettingGroup("The sources that have this anime")
            Text(
                text = "Each one lists its own episodes; a source that is down is left out for a while.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
            bundles.forEach { bundle ->
                val on = bundle.source == current?.source
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(bundle) }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = bundle.source,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) LocalAccent.current else MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = listOfNotNull(
                                "${bundle.sub.size} subtitled",
                                bundle.dub.size.takeIf { it > 0 }?.let { "$it dubbed" },
                                bundle.title?.takeIf { it.isNotBlank() && it != bundle.source },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (on) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null,
                            tint = LocalAccent.current, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
    }
}

/** The seasons of the story, oldest first, with the one being watched marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonSheet(
    entries: List<SeasonEntry>,
    currentId: Int,
    onPick: (SeasonEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
            SettingGroup("The seasons of this story")
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(entries.size) { index ->
                    val entry = entries[index]
                    val on = entry.id == currentId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(entry) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NetworkImage(
                            url = entry.cover,
                            contentDescription = entry.title.display,
                            modifier = Modifier
                                .width(52.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(7.dp)),
                            widthPx = 200,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = entry.title.display,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (on) LocalAccent.current else MaterialTheme.colorScheme.onBackground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = listOfNotNull(
                                    entry.relation,
                                    entry.format.label.takeIf { it != "—" },
                                    entry.episodes?.let { "$it ep" },
                                    entry.year?.toString(),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        if (on) {
                            Pill(text = "This one", selected = true)
                        }
                    }
                }
            }
        }
    }
}

/** The lists an anime can be on, and the stars it can be given. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListSheet(
    current: ListStatus?,
    stars: Int,
    onStatus: (ListStatus) -> Unit,
    onRate: (Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
            SettingGroup("Your library")
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListStatus.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { status ->
                            Pill(
                                text = status.label,
                                selected = status == current,
                                onClick = { onStatus(status) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            SettingGroup("Your stars")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stars(stars = stars, onRate = onRate, size = 30)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (stars == 0) "Not rated" else "$stars of 5",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Rated anime go on your completed list, as they would on AniList.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (current != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Take it off your library",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .clickable(onClick = onRemove),
                )
            }
        }
    }
}

/** "3 d", "5 h", "12 min". */
private fun daysLabel(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes <= 0 -> "moments"
        minutes < 60 -> "$minutes min"
        minutes < 60 * 24 -> "${minutes / 60} h"
        else -> "${minutes / (60 * 24)} d"
    }
}

/** The page's own link, shared (AniList's, since that is where the anime is from). */
private fun share(context: android.content.Context, anime: Anime) {
    runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, "https://anilist.co/anime/${anime.id}")
            putExtra(android.content.Intent.EXTRA_SUBJECT, anime.title.display)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share"))
    }
}
