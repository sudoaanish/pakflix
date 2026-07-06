package org.jellyfin.androidtv.ui.home

import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

data class HomeHeroItem(
	val id: UUID,
	val kind: BaseItemKind,
	val title: String,
	val overview: String?,
	val backdropUrl: String?,
	val backdropBlurHash: String?,
	val logoUrl: String?,
	val primaryImageUrl: String?,
	val badges: List<String>,
	val source: HomeHeroSource,
	val backgroundItem: BaseItemDto,
)

enum class HomeHeroSource {
	Movie,
	Series,
	EpisodeResolvedSeries,
}

sealed interface HomeHeroState {
	data object Loading : HomeHeroState
	data class Ready(val items: List<HomeHeroItem>, val selectedIndex: Int = 0) : HomeHeroState
	data object Empty : HomeHeroState
	data class Error(val message: String) : HomeHeroState
}
