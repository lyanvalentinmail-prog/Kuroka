package app.kuroka.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/*
 * The factory every screen asks for, and the page sizes the catalog is read with.
 *
 * It is written by hand rather than reflected into: a view model's dependencies have defaults from
 * `di/Graph`, so the factory has nothing to inject — it only has to say which class to build. That
 * keeps a screen's `viewModel(factory = KurokaViewModels)` working in a preview and in a test, and
 * keeps the app free of a dependency-injection framework (like Orpheon's own graph, GPL-3.0,
 * © kota-zo).
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

/** How much of the catalog a page asks for. */
object Paging {
    /** A page of search answers, or of the schedule. */
    const val PAGE = 24

    /** A row of the home page: enough to fill a shelf on a tablet, not enough to waste a request. */
    const val ROW = 14
}

/** The view models of the app, built with the graph's own objects. */
object KurokaViewModels : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel()
        modelClass.isAssignableFrom(SearchViewModel::class.java) -> SearchViewModel()
        modelClass.isAssignableFrom(AnimeViewModel::class.java) -> AnimeViewModel()
        modelClass.isAssignableFrom(PlayerViewModel::class.java) -> PlayerViewModel()
        modelClass.isAssignableFrom(LibraryViewModel::class.java) -> LibraryViewModel()
        modelClass.isAssignableFrom(DownloadsViewModel::class.java) -> DownloadsViewModel()
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel()
        modelClass.isAssignableFrom(AccountViewModel::class.java) -> AccountViewModel()
        modelClass.isAssignableFrom(ScheduleViewModel::class.java) -> ScheduleViewModel()
        else -> error("Kuroka has no view model called ${modelClass.name}")
    } as T
}
