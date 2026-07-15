package org.jellyfin.androidtv.ui.originals

import org.jellyfin.sdk.model.api.BaseItemDto

sealed interface OriginalsAvailability {
	data object Loading : OriginalsAvailability
	data object Hidden : OriginalsAvailability
	data class Visible(val topLevelCount: Int) : OriginalsAvailability
}

sealed interface PakflixOriginalsPageState {
	data object Loading : PakflixOriginalsPageState
	data class Ready(val data: PakflixOriginalsPageData) : PakflixOriginalsPageState
	data class Error(val message: String) : PakflixOriginalsPageState
}

data class PakflixOriginalsPageData(
	val top10: List<BaseItemDto>,
	val recentMovies: List<BaseItemDto>,
	val recentShows: List<BaseItemDto>,
	val genreRows: List<PakflixOriginalsGenreRow>,
	val snapshotTotal: Int,
)

data class PakflixOriginalsGenreRow(
	val exactName: String,
	val items: List<BaseItemDto>,
)
