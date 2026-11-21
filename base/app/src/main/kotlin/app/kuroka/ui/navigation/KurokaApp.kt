package app.kuroka.ui.navigation

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.kuroka.PipMode
import app.kuroka.api.AppSettings
import app.kuroka.api.Audio
import app.kuroka.di.NowPlaying
import app.kuroka.ui.components.MiniPlayer
import app.kuroka.ui.screens.AccountScreen
import app.kuroka.ui.screens.AnimeScreen
import app.kuroka.ui.screens.DownloadsScreen
import app.kuroka.ui.screens.HomeScreen
import app.kuroka.ui.screens.LibraryScreen
import app.kuroka.ui.screens.OnboardingScreen
import app.kuroka.ui.screens.PlayerScreen
import app.kuroka.ui.screens.ScheduleScreen
import app.kuroka.ui.screens.SearchScreen
import app.kuroka.ui.screens.SettingsScreen
import app.kuroka.ui.screens.SourcesScreen
import app.kuroka.ui.screens.settings.AboutPage
import app.kuroka.ui.screens.settings.DisplayPage
import app.kuroka.ui.screens.settings.DownloadSettingsPage
import app.kuroka.ui.screens.settings.PlayerSettingsPage
import app.kuroka.ui.screens.settings.StoragePage
import app.kuroka.ui.screens.settings.SubtitleSettingsPage
import app.kuroka.ui.screens.settings.UpdatesPage
import app.kuroka.ui.viewmodel.AccountViewModel
import app.kuroka.ui.viewmodel.KurokaViewModels

/*
 * The tree the activity draws: a scaffold with the five tabs at its foot, the mini player above
 * them while an episode is on, and the navigation graph inside — the home page, the search, an
 * anime's page, the player, the library, the downloads, the schedule, the account, the settings and
 * their pages, and the first-run setup.
 *
 * The player is a route of its own and draws edge to edge: the bottom bar hides while it is up, so
 * a phone in landscape has nothing but the picture, and picture-in-picture has nothing to hide.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

@Composable
fun KurokaApp(
    settings: AppSettings,
    link: Intent?,
    onLinkHandled: () -> Unit,
    onController: (NavHostController) -> Unit,
) {
    val nav = rememberNavController()
    val context = LocalContext.current
    LaunchedEffect(nav) { onController(nav) }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val onPlayer = route == Routes.playerPattern
    val onTabs = route in Routes.tabs
    val pip by PipMode.inPip.collectAsState()

    // The setup, once: a fresh install lands on it and never again.
    LaunchedEffect(settings.onboardingDone) {
        if (!settings.onboardingDone && route != Routes.ONBOARDING) {
            nav.navigate(Routes.ONBOARDING) { launchSingleTop = true }
        }
    }

    // What the activity was opened with: a sign-in coming back, or an AniList page.
    val account: AccountViewModel = viewModel(factory = KurokaViewModels)
    LaunchedEffect(link) {
        val data = link?.data
        if (data != null) {
            when {
                data.scheme == "kuroka" && data.host == "anilist" -> account.completeFromLink(data)
                data.host == "anilist.co" && data.pathSegments.firstOrNull() == "anime" -> {
                    data.pathSegments.getOrNull(1)?.toIntOrNull()?.let { id -> nav.navigate(Routes.anime(id)) }
                }

                link?.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH" -> {
                    val query = data?.getQueryParameter("query") ?: link.getStringExtra("query")
                    nav.navigate(Routes.SEARCH + (query?.let { "?q=$it" } ?: ""))
                }
            }
        }
        onLinkHandled()
    }

    val nowPlaying by NowPlaying.episode.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = {
            if (!onPlayer && !pip) {
                Column {
                    // The mini player sits on the bar, and only when an episode is loaded.
                    AnimatedVisibility(
                        visible = nowPlaying != null,
                        enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                        exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
                    ) {
                        val episode = nowPlaying
                        if (episode != null) {
                            MiniPlayer(
                                episode = episode,
                                onOpen = {
                                    nav.navigate(
                                        Routes.watch(episode.animeId, episode.number, episode.audio.code)
                                    )
                                },
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = onTabs,
                        enter = slideInVertically(tween(200)) { it } + fadeIn(tween(200)),
                        exit = slideOutVertically(tween(160)) { it } + fadeOut(tween(160)),
                    ) {
                        BottomBar(route = route, onTab = { tab -> nav.goTab(tab) })
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .then(if (onPlayer) Modifier else Modifier.padding(bottom = padding.calculateBottomPadding()))
        ) {
            KurokaNavHost(nav = nav, settings = settings, account = account)
        }
    }
}

/** The graph itself. */
@Composable
private fun KurokaNavHost(
    nav: NavHostController,
    settings: AppSettings,
    account: AccountViewModel,
) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onAnime = { id -> nav.navigate(Routes.anime(id)) },
                onWatch = { id, number, audio -> nav.navigate(Routes.watch(id, number, audio.code)) },
                onSearch = { query -> nav.navigate(Routes.SEARCH + (query?.let { "?q=$it" } ?: "")) },
                onGenre = { name -> nav.navigate(Routes.genre(name)) },
                onSchedule = { nav.navigate(Routes.SCHEDULE) },
            )
        }
        composable(
            route = Routes.SEARCH + "?q={q}",
            arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            SearchScreen(
                initialQuery = entry.arguments?.getString("q"),
                onAnime = { id -> nav.navigate(Routes.anime(id)) },
            )
        }
        composable(
            route = Routes.GENRE,
            arguments = listOf(navArgument("name") { type = NavType.StringType }),
        ) { entry ->
            val name = remember(entry) {
                java.net.URLDecoder.decode(entry.arguments?.getString("name") ?: "", "UTF-8")
            }
            SearchScreen(genre = name, onAnime = { id -> nav.navigate(Routes.anime(id)) }, onBack = { nav.popBackStack() })
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onAnime = { id -> nav.navigate(Routes.anime(id)) },
                onWatch = { id, number, audio -> nav.navigate(Routes.watch(id, number, audio.code)) },
                onAccount = { nav.navigate(Routes.ACCOUNT) },
                onDownloads = { nav.navigate(Routes.DOWNLOADS) },
            )
        }
        composable(Routes.DOWNLOADS) {
            DownloadsScreen(
                onAnime = { id -> nav.navigate(Routes.anime(id)) },
                // A finished download plays from the phone: the player finds it and needs no network.
                onOpen = { item ->
                    nav.navigate(Routes.watch(item.animeId, item.episodeNumber, item.audio.code))
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onDisplay = { nav.navigate(Routes.DISPLAY) },
                onPlayer = { nav.navigate(Routes.PLAYER_SETTINGS) },
                onSubtitles = { nav.navigate(Routes.SUBTITLES) },
                onDownloads = { nav.navigate(Routes.DOWNLOAD_SETTINGS) },
                onSources = { nav.navigate(Routes.SOURCES) },
                onAccount = { nav.navigate(Routes.ACCOUNT) },
                onStorage = { nav.navigate(Routes.STORAGE) },
                onUpdates = { nav.navigate(Routes.UPDATES) },
                onAbout = { nav.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.DISPLAY) { DisplayPage(onBack = { nav.popBackStack() }) }
        composable(Routes.PLAYER_SETTINGS) { PlayerSettingsPage(onBack = { nav.popBackStack() }) }
        composable(Routes.SUBTITLES) { SubtitleSettingsPage(onBack = { nav.popBackStack() }) }
        composable(Routes.DOWNLOAD_SETTINGS) { DownloadSettingsPage(onBack = { nav.popBackStack() }) }
        composable(Routes.STORAGE) { StoragePage(onBack = { nav.popBackStack() }) }
        composable(Routes.UPDATES) { UpdatesPage(onBack = { nav.popBackStack() }) }
        composable(Routes.ABOUT) { AboutPage(onBack = { nav.popBackStack() }) }
        composable(Routes.SOURCES) { SourcesScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ACCOUNT) {
            AccountScreen(account = account, onBack = { nav.popBackStack() })
        }
        composable(Routes.SCHEDULE) {
            ScheduleScreen(onAnime = { id -> nav.navigate(Routes.anime(id)) }, onBack = { nav.popBackStack() })
        }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = {
                nav.navigate(Routes.HOME) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            })
        }
        composable(
            route = Routes.ANIME,
            arguments = listOf(navArgument("animeId") { type = NavType.IntType }),
        ) { entry ->
            val id = entry.arguments?.getInt("animeId") ?: 0
            AnimeScreen(
                animeId = id,
                onAnime = { other -> nav.navigate(Routes.anime(other)) },
                onWatch = { number, audio, episodeId, server, source ->
                    nav.navigate(Routes.watch(id, number, audio.code, episodeId, server, source))
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = Routes.playerPattern,
            arguments = listOf(
                navArgument("animeId") { type = NavType.IntType },
                navArgument("number") { type = NavType.StringType },
                navArgument("audio") { type = NavType.StringType },
                navArgument("episodeId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("server") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val args = entry.arguments
            PlayerScreen(
                animeId = args?.getInt("animeId") ?: 0,
                number = args?.getString("number")?.toDoubleOrNull() ?: 1.0,
                audio = Audio.of(args?.getString("audio")),
                episodeId = args?.getString("episodeId"),
                server = args?.getString("server"),
                source = args?.getString("source"),
                onBack = { nav.popBackStack() },
                onOpenAnime = { id ->
                    nav.navigate(Routes.anime(id)) { launchSingleTop = true }
                },
                onOpenSettings = { nav.navigate(Routes.PLAYER_SETTINGS) },
            )
        }
    }
}

/** A tab tap: back to the tab's own copy of itself, without a stack of the same screen. */
private fun NavHostController.goTab(tab: String) {
    navigate(tab) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
