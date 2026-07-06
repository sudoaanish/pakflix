package org.jellyfin.androidtv.ui.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.androidtv.util.apiclient.seriesPrimaryImage
import org.jellyfin.androidtv.util.apiclient.seriesThumbImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration

class HomeHeroRepository(
	private val api: ApiClient,
	private val userViewsRepository: UserViewsRepository,
) {
	suspend fun loadHeroItems(): List<HomeHeroItem> = withContext(Dispatchers.IO) {
		Timber.i("HomeHero load start")

		val userViews = userViewsRepository.views.first()
		val heroLibraries = userViews.filter { view ->
			view.collectionType == CollectionType.MOVIES || view.collectionType == CollectionType.TVSHOWS
		}

		Timber.i(
			"HomeHero user views considered=%s heroLibraries=%s collectionTypes=%s",
			userViews.size,
			heroLibraries.size,
			heroLibraries.map { it.collectionType?.serialName ?: "unknown" },
		)

		if (heroLibraries.isEmpty()) {
			Timber.i("HomeHero hidden: no movie or TV libraries")
			return@withContext emptyList()
		}

		val latestByLibrary = coroutineScope {
			heroLibraries.map { library ->
				async {
					val items = runCatching { loadLatestForLibrary(library) }
						.onFailure { error ->
							Timber.w(error, "HomeHero latest request failed for collectionType=%s", library.collectionType?.serialName)
						}
						.getOrDefault(emptyList())

					Timber.i(
						"HomeHero latest response collectionType=%s count=%s kinds=%s",
						library.collectionType?.serialName ?: "unknown",
						items.size,
						items.groupingBy { it.type?.serialName ?: "unknown" }.eachCount(),
					)
					LatestLibraryResult(library.collectionType, items)
				}
			}.awaitAll()
		}

		val movieRawLatest = latestByLibrary
			.filter { it.collectionType == CollectionType.MOVIES }
			.flatMap { it.items }
			.take(RAW_LATEST_LIMIT_PER_LIBRARY)
		val tvRawLatest = latestByLibrary
			.filter { it.collectionType == CollectionType.TVSHOWS }
			.flatMap { it.items }
			.take(RAW_LATEST_LIMIT_PER_LIBRARY)

		val movieTypeCounts = movieRawLatest.groupingBy { it.type }.eachCount()
		val tvTypeCounts = tvRawLatest.groupingBy { it.type }.eachCount()
		val episodeCount = tvTypeCounts[BaseItemKind.EPISODE] ?: 0
		val episodesWithSeriesId = tvRawLatest.count { it.type == BaseItemKind.EPISODE && it.seriesId != null }
		val episodesWithoutSeriesId = episodeCount - episodesWithSeriesId

		Timber.i(
			"HomeHero movieRawLatest count=%s kinds=%s",
			movieRawLatest.size,
			movieTypeCounts.mapKeys { it.key?.serialName ?: "unknown" },
		)
		Timber.i(
			"HomeHero tvRawLatest count=%s kinds=%s directSeries=%s episodes=%s episodesWithSeriesId=%s episodesSkippedMissingSeriesId=%s",
			tvRawLatest.size,
			tvTypeCounts.mapKeys { it.key?.serialName ?: "unknown" },
			tvTypeCounts[BaseItemKind.SERIES] ?: 0,
			episodeCount,
			episodesWithSeriesId,
			episodesWithoutSeriesId,
		)

		val movieCandidates = movieRawLatest
			.mapNotNull(::normalizeCandidate)
			.filter { it.source == HomeHeroSource.Movie }
			.distinctBy { it.id }
			.take(PRE_DETAIL_LIMIT_PER_TYPE)

		val seriesCandidates = tvRawLatest
			.mapNotNull(::normalizeCandidate)
			.filter { it.source == HomeHeroSource.Series || it.source == HomeHeroSource.EpisodeResolvedSeries }
			.distinctBy { it.id }
			.take(PRE_DETAIL_LIMIT_PER_TYPE)

		Timber.i("HomeHero movie candidates before detail movies=%s", movieCandidates.size)
		Timber.i("HomeHero tv candidates before detail series=%s", seriesCandidates.size)

		if (movieCandidates.isEmpty() && seriesCandidates.isEmpty()) {
			Timber.i("HomeHero hidden: no usable movie or series candidates")
			return@withContext emptyList()
		}

		val movieDetailsById = fetchDetailsById("movie", movieCandidates)
		val seriesDetailsById = fetchDetailsById("series", seriesCandidates)
		val movieItems = buildHeroItemsForBucket("movie", movieCandidates, movieDetailsById)
		val seriesItems = buildHeroItemsForBucket("series", seriesCandidates, seriesDetailsById)
		val heroItems = balanceHeroItems(movieItems, seriesItems)
		val finalMovieCount = heroItems.count { it.kind == BaseItemKind.MOVIE }
		val finalSeriesCount = heroItems.count { it.kind == BaseItemKind.SERIES }

		Timber.i(
			"HomeHero final candidate count=%s movieCount=%s seriesCount=%s default=%s max=%s",
			heroItems.size,
			finalMovieCount,
			finalSeriesCount,
			DEFAULT_HERO_ITEMS,
			MAX_HERO_ITEMS,
		)
		if (finalSeriesCount == 0) {
			Timber.i(
				"HomeHero no final TV series candidates: latestSeries=%s episodesWithSeriesId=%s seriesCandidates=%s acceptedSeries=%s",
				tvTypeCounts[BaseItemKind.SERIES] ?: 0,
				episodesWithSeriesId,
				seriesCandidates.size,
				seriesItems.size,
			)
		}
		Timber.i(
			"HomeHero final order=%s",
			heroItems.joinToString(prefix = "[", postfix = "]") { "${it.kind.serialName}:${it.title}" },
		)
		if (heroItems.isEmpty()) Timber.i("HomeHero hidden: candidates lacked usable artwork")

		heroItems
	}

	private suspend fun fetchDetailsById(
		label: String,
		candidates: List<Candidate>,
	): Map<UUID, BaseItemDto> {
		Timber.i("HomeHero %sDetailFetch count=%s", label, candidates.size)
		if (candidates.isEmpty()) return emptyMap()

		return api.itemsApi.getItems(
			ids = candidates.map { it.id },
			fields = ItemRepository.itemFields,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
		).content.items.orEmpty().associateBy { it.id }
	}

	private fun buildHeroItemsForBucket(
		label: String,
		candidates: List<Candidate>,
		detailsById: Map<UUID, BaseItemDto>,
	): List<HomeHeroItem> {
		var missingDetail = 0
		var unsupportedKind = 0
		var missingArtwork = 0
		val accepted = candidates.mapNotNull { candidate ->
			val detail = detailsById[candidate.id]
			if (detail == null) {
				missingDetail++
				Timber.i("HomeHero rejecting %s candidate reason=missing-detail source=%s id=%s", label, candidate.source, candidate.id)
				return@mapNotNull null
			}

			if (detail.type != BaseItemKind.MOVIE && detail.type != BaseItemKind.SERIES) {
				unsupportedKind++
				Timber.i(
					"HomeHero rejecting %s candidate reason=unsupported-detail-kind kind=%s id=%s source=%s",
					label,
					detail.type?.serialName ?: "unknown",
					detail.id,
					candidate.source,
				)
				return@mapNotNull null
			}

			val item = buildHeroItem(detail, candidate) ?: return@mapNotNull null
			if (!hasUsableArtwork(item, label)) {
				missingArtwork++
				return@mapNotNull null
			}

			item
		}

		Timber.i(
			"HomeHero %sAccepted count=%s rejectedMissingDetail=%s rejectedUnsupportedKind=%s rejectedMissingArtwork=%s",
			label,
			accepted.size,
			missingDetail,
			unsupportedKind,
			missingArtwork,
		)

		return accepted
	}

	private suspend fun loadLatestForLibrary(library: BaseItemDto): List<BaseItemDto> {
		val request = GetLatestMediaRequest(
			fields = ItemRepository.browseFields,
			imageTypeLimit = 1,
			parentId = library.id,
			groupItems = true,
			limit = RAW_LATEST_LIMIT_PER_LIBRARY,
		)

		Timber.i(
			"HomeHero latest request source=existing-row-mirrored collectionType=%s limit=%s includeItemTypes=default groupItems=true fields=browseFields imageTypeLimit=1",
			library.collectionType?.serialName ?: "unknown",
			RAW_LATEST_LIMIT_PER_LIBRARY,
		)

		return api.userLibraryApi.getLatestMedia(request).content
	}

	private fun normalizeCandidate(item: BaseItemDto): Candidate? = when (item.type) {
		BaseItemKind.MOVIE -> Candidate(item.id, item, HomeHeroSource.Movie)
		BaseItemKind.SERIES -> Candidate(item.id, item, HomeHeroSource.Series)
		BaseItemKind.EPISODE -> {
			val seriesId = item.seriesId
			if (seriesId == null) {
				Timber.i("HomeHero skipping episode without seriesId id=%s", item.id)
				null
			} else Candidate(seriesId, item, HomeHeroSource.EpisodeResolvedSeries)
		}

		else -> {
			Timber.i("HomeHero skipping unsupported item kind=%s id=%s", item.type?.serialName ?: "unknown", item.id)
			null
		}
	}

	private fun buildHeroItem(detail: BaseItemDto, candidate: Candidate): HomeHeroItem? {
		val sourceItem = candidate.sourceItem
		val backdrop = detail.itemBackdropImages.firstOrNull()
			?: sourceItem.parentBackdropImages.firstOrNull()
		val primary = detail.itemImages[ImageType.PRIMARY]
			?: sourceItem.parentImages[ImageType.PRIMARY]
			?: sourceItem.parentImages[ImageType.THUMB]
			?: sourceItem.seriesPrimaryImage
			?: sourceItem.seriesThumbImage
		val logo = detail.itemImages[ImageType.LOGO]
			?: sourceItem.parentImages[ImageType.LOGO]

		return HomeHeroItem(
			id = detail.id,
			kind = requireNotNull(detail.type),
			title = detail.name.orEmpty().ifBlank { sourceItem.seriesName ?: sourceItem.name.orEmpty() },
			overview = detail.overview?.takeIf { it.isNotBlank() },
			backdropUrl = backdrop?.getUrl(api, fillWidth = 1280, fillHeight = 720),
			backdropBlurHash = backdrop?.blurHash,
			logoUrl = logo?.getUrl(api, maxWidth = 420),
			primaryImageUrl = primary?.getUrl(api, fillWidth = 640, fillHeight = 360),
			badges = buildBadges(detail),
			source = candidate.source,
			backgroundItem = detail,
		)
	}

	private fun hasUsableArtwork(item: HomeHeroItem, bucket: String): Boolean {
		val hasArtwork = item.backdropUrl != null || item.primaryImageUrl != null
		if (!hasArtwork) {
			Timber.i(
				"HomeHero rejecting %s candidate reason=missing-artwork title=%s kind=%s source=%s",
				bucket,
				item.title,
				item.kind.serialName,
				item.source,
			)
		}

		return hasArtwork
	}

	private fun buildBadges(item: BaseItemDto): List<String> = buildList {
		item.productionYear?.let { add(it.toString()) }

		when (item.type) {
			BaseItemKind.MOVIE -> item.runTimeTicks?.ticks?.let(::formatRuntime)?.let(::add)
			BaseItemKind.SERIES -> item.childCount?.takeIf { it > 0 }?.let { add("$it seasons") }
			else -> Unit
		}

		item.officialRating?.takeIf { it.isNotBlank() }?.let(::add)
		item.communityRating?.takeIf { it > 0.0 }?.let { add(String.format("%.1f", it)) }
		item.genres.orEmpty().take(2).forEach(::add)

		val mediaSource = item.mediaSources?.firstOrNull()
		val videoStream = mediaSource?.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }
		val audioStream = mediaSource?.mediaStreams?.firstOrNull { it.type == MediaStreamType.AUDIO }
		val subtitleCount = mediaSource?.mediaStreams?.count { it.type == MediaStreamType.SUBTITLE } ?: 0

		resolutionBadge(videoStream?.width, videoStream?.height)?.let(::add)

		when (videoStream?.videoRangeType) {
			VideoRangeType.HDR10,
			VideoRangeType.HDR10_PLUS,
			VideoRangeType.HLG,
			VideoRangeType.DOVI,
			VideoRangeType.DOVI_WITH_HDR10,
			VideoRangeType.DOVI_WITH_HLG,
			VideoRangeType.DOVI_WITH_EL,
			VideoRangeType.DOVI_WITH_HDR10_PLUS,
			VideoRangeType.DOVI_WITH_ELHDR10_PLUS -> add("HDR")

			VideoRangeType.SDR -> add("SDR")
			else -> Unit
		}

		audioStream?.codec?.uppercase()?.takeIf { it.isNotBlank() }?.let(::add)
		audioStream?.channelLayout?.uppercase()?.takeIf { it.isNotBlank() }?.let(::add)
		if (subtitleCount > 0) add("CC")
	}.distinct().take(MAX_BADGES)

	private fun formatRuntime(duration: Duration): String? {
		val millis = duration.inWholeMilliseconds
		if (millis <= 0) return null
		return TimeUtils.formatMillis(millis)
	}

	private fun resolutionBadge(width: Int?, height: Int?): String? {
		if (width == null && height == null) return null

		val safeWidth = width ?: 0
		val safeHeight = height ?: 0
		val label = when {
			safeWidth >= 3840 || safeHeight >= 2160 -> "4K"
			safeWidth >= 2560 || safeHeight >= 1440 -> "1440p"
			safeWidth >= 1920 || safeHeight >= 1000 -> "1080p"
			safeWidth >= 1280 || safeHeight >= 700 -> "720p"
			safeHeight >= 470 -> "480p"
			safeHeight > 0 -> "SD"
			else -> null
		}

		Timber.i("HomeHero resolution badge normalized width=%s height=%s label=%s", width, height, label)
		return label
	}

	private fun balanceHeroItems(
		movies: List<HomeHeroItem>,
		series: List<HomeHeroItem>,
	): List<HomeHeroItem> {
		val targetPerType = DEFAULT_HERO_ITEMS / 2

		val selectedIds = mutableSetOf<UUID>()
		val selectedMovies = movies.take(targetPerType).also { selectedIds.addAll(it.map { item -> item.id }) }
		val selectedSeries = series.take(targetPerType).also { selectedIds.addAll(it.map { item -> item.id }) }
		val fillItems = (movies + series)
			.filterNot { it.id in selectedIds }
			.take(DEFAULT_HERO_ITEMS - selectedMovies.size - selectedSeries.size)

		return interleave(selectedMovies, selectedSeries)
			.plus(fillItems)
			.take(MAX_HERO_ITEMS)
			.take(DEFAULT_HERO_ITEMS)
	}

	private fun interleave(
		movies: List<HomeHeroItem>,
		series: List<HomeHeroItem>,
	): List<HomeHeroItem> = buildList {
		for (index in 0 until maxOf(movies.size, series.size)) {
			movies.getOrNull(index)?.let(::add)
			series.getOrNull(index)?.let(::add)
		}
	}

	private fun qualityScore(item: HomeHeroItem): Int {
		var score = 0
		if (item.backdropUrl != null) score += 4
		if (item.logoUrl != null) score += 2
		if (!item.overview.isNullOrBlank()) score += 2
		score += item.badges.size.coerceAtMost(3)
		return score
	}

	private data class Candidate(
		val id: UUID,
		val sourceItem: BaseItemDto,
		val source: HomeHeroSource,
	)

	private data class LatestLibraryResult(
		val collectionType: CollectionType?,
		val items: List<BaseItemDto>,
	)

	companion object {
		const val DEFAULT_HERO_ITEMS = 6
		const val MAX_HERO_ITEMS = 8
		private const val RAW_LATEST_LIMIT_PER_LIBRARY = 40
		private const val PRE_DETAIL_LIMIT_PER_TYPE = 6
		private const val MAX_BADGES = 8
	}
}
