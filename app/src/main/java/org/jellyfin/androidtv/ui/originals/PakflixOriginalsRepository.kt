package org.jellyfin.androidtv.ui.originals

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.Session
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.Locale
import java.util.UUID

class PakflixOriginalsRepository(
	private val api: ApiClient,
	private val sessionRepository: SessionRepository,
) {
	private val _availability = MutableStateFlow<OriginalsAvailability>(OriginalsAvailability.Loading)
	val availability: StateFlow<OriginalsAvailability> = _availability.asStateFlow()

	private val availabilityMutex = Mutex()
	@Volatile
	private var availabilitySessionKey: SessionKey? = null

	suspend fun ensureAvailability() = availabilityMutex.withLock {
		val session = sessionRepository.currentSession.value
		val sessionKey = session?.toKey()
		if (sessionKey == null) {
			availabilitySessionKey = null
			_availability.value = OriginalsAvailability.Hidden
			return@withLock
		}

		if (availabilitySessionKey == sessionKey && _availability.value !is OriginalsAvailability.Loading) return@withLock

		val previous = _availability.value
		val previousKey = availabilitySessionKey
		availabilitySessionKey = sessionKey
		_availability.value = OriginalsAvailability.Loading

		val count = loadAvailabilityCount()
		if (count == null) {
			val retainVisible = previousKey == sessionKey && previous is OriginalsAvailability.Visible
			_availability.value = if (retainVisible) previous else OriginalsAvailability.Hidden
			Timber.w("Pakflix Originals availability failed retainedVisible=%s", retainVisible)
			return@withLock
		}

		val visible = count >= PakflixOriginalsConfig.NAV_THRESHOLD
		_availability.value = if (visible) OriginalsAvailability.Visible(count) else OriginalsAvailability.Hidden
		Timber.i("Pakflix Originals availability result topLevelCount=%d visible=%s", count, visible)
	}

	fun isAvailabilityCurrent(): Boolean = availabilitySessionKey == sessionRepository.currentSession.value?.toKey()

	suspend fun loadPageData(): PakflixOriginalsPageData = withContext(Dispatchers.IO) {
		Timber.i("Pakflix Originals page load start hero=false maxPageCalls=8")
		val (snapshotResponse, favoriteResponse) = coroutineScope {
			val snapshot = async { loadSnapshot() }
			val favorites = async { loadFavorites() }
			snapshot.await() to favorites.await()
		}

		val snapshot = normalizeTopLevel(snapshotResponse.items, "snapshot")
		val favorites = normalizeTopLevel(favoriteResponse.items, "favorites")
		if (snapshotResponse.totalRecordCount > snapshot.size) {
			Timber.i(
				"Pakflix Originals snapshot truncated total=%d returnedAccepted=%d limit=%d",
				snapshotResponse.totalRecordCount,
				snapshot.size,
				PakflixOriginalsConfig.SNAPSHOT_LIMIT,
			)
		}

		val top10 = mergeDistinct(favorites, snapshot).take(PakflixOriginalsConfig.TOP_10_LIMIT)
		val favoriteIds = favorites.mapTo(hashSetOf()) { it.id }
		val backfillCount = top10.count { it.id !in favoriteIds }
		Timber.i(
			"Pakflix Originals Top 10 favorites=%d backfill=%d final=%d movieCount=%d seriesCount=%d",
			favorites.size.coerceAtMost(PakflixOriginalsConfig.TOP_10_LIMIT),
			backfillCount,
			top10.size,
			top10.count { it.type == BaseItemKind.MOVIE },
			top10.count { it.type == BaseItemKind.SERIES },
		)

		val recentMovies = snapshot.filter { it.type == BaseItemKind.MOVIE }.take(PakflixOriginalsConfig.RECENT_ITEM_LIMIT)
		val recentShows = snapshot.filter { it.type == BaseItemKind.SERIES }.take(PakflixOriginalsConfig.RECENT_ITEM_LIMIT)
		val genreCandidates = discoverGenres(snapshot)
		val genreRows = coroutineScope {
			genreCandidates.map { candidate -> async { loadGenreRow(candidate) } }
				.awaitAll()
				.filterNotNull()
		}

		Timber.i(
			"Pakflix Originals page loaded without hero snapshot=%d movies=%d series=%d top10=%d recentMovies=%d recentShows=%d genreRows=%d initialCalls=%d maxPageCalls=8",
			snapshot.size,
			snapshot.count { it.type == BaseItemKind.MOVIE },
			snapshot.count { it.type == BaseItemKind.SERIES },
			top10.size,
			recentMovies.size,
			recentShows.size,
			genreRows.size,
			2 + genreCandidates.size,
		)

		PakflixOriginalsPageData(
			top10 = top10,
			recentMovies = recentMovies,
			recentShows = recentShows,
			genreRows = genreRows,
			snapshotTotal = snapshotResponse.totalRecordCount,
		)
	}

	private suspend fun loadAvailabilityCount(): Int? = withContext(Dispatchers.IO) {
		Timber.i(
			"Pakflix Originals availability request start threshold=%d thread=%s isMainThread=%s",
			PakflixOriginalsConfig.NAV_THRESHOLD,
			Thread.currentThread().name,
			Looper.myLooper() == Looper.getMainLooper(),
		)
		val primary = runCatching {
			api.itemsApi.getItems(
				baseRequest().copy(
					fields = emptySet(),
					enableImages = false,
					limit = 1,
					enableTotalRecordCount = true,
				)
			).content
		}.onFailure { error ->
			Timber.w(
				error,
				"Pakflix Originals availability count request failed type=%s; trying bounded fallback",
				error.javaClass.simpleName,
			)
		}
			.getOrNull()

		val primaryAccepted = primary?.items?.count(::isTopLevelOriginal) ?: 0
		if (primary != null && primary.totalRecordCount >= primaryAccepted) return@withContext primary.totalRecordCount

		runCatching {
			api.itemsApi.getItems(
				baseRequest().copy(
					fields = emptySet(),
					enableImages = false,
					limit = PakflixOriginalsConfig.NAV_THRESHOLD,
					enableTotalRecordCount = false,
				)
			).content.items.count(::isTopLevelOriginal)
		}.onFailure { error ->
			Timber.w(
				error,
				"Pakflix Originals availability fallback request failed type=%s",
				error.javaClass.simpleName,
			)
		}
			.getOrNull()
	}

	private suspend fun loadSnapshot(): ItemResponse = runCatching {
		val response = api.itemsApi.getItems(
			baseRequest().copy(
				fields = ItemRepository.browseFields,
				imageTypeLimit = 1,
				sortBy = setOf(ItemSortBy.DATE_CREATED),
				sortOrder = setOf(SortOrder.DESCENDING),
				startIndex = 0,
				limit = PakflixOriginalsConfig.SNAPSHOT_LIMIT,
				enableTotalRecordCount = true,
			)
		).content
		ItemResponse(response.items, response.totalRecordCount)
	}.onFailure { error -> Timber.w(error, "Pakflix Originals snapshot request failed") }
		.getOrDefault(ItemResponse(emptyList(), 0))

	private suspend fun loadFavorites(): ItemResponse = runCatching {
		val response = api.itemsApi.getItems(
			baseRequest().copy(
				filters = setOf(ItemFilter.IS_FAVORITE),
				fields = ItemRepository.browseFields,
				imageTypeLimit = 1,
				sortBy = setOf(ItemSortBy.DATE_CREATED),
				sortOrder = setOf(SortOrder.DESCENDING),
				startIndex = 0,
				limit = PakflixOriginalsConfig.TOP_10_LIMIT,
				enableTotalRecordCount = true,
			)
		).content
		ItemResponse(response.items, response.totalRecordCount)
	}.onFailure { error -> Timber.w(error, "Pakflix Originals favorite request failed") }
		.getOrDefault(ItemResponse(emptyList(), 0))

	private fun discoverGenres(snapshot: List<BaseItemDto>): List<GenreCandidate> {
		val genres = linkedMapOf<String, GenreAccumulator>()
		snapshot.forEachIndexed { itemIndex, item ->
			val itemGenres = linkedMapOf<String, String>()
			item.genres.orEmpty().forEach { label ->
				val exact = label.trim()
				if (exact.isNotEmpty()) itemGenres.putIfAbsent(normalizeGenre(exact), exact)
			}
			itemGenres.forEach { (key, exact) ->
				val current = genres[key]
				genres[key] = if (current == null) GenreAccumulator(exact, 1, itemIndex) else current.copy(count = current.count + 1)
			}
		}

		val selected = genres.entries
			.asSequence()
			.filter { it.value.count >= PakflixOriginalsConfig.GENRE_MINIMUM }
			.map { (key, value) -> GenreCandidate(key, value.exactName, value.count, value.firstIndex) }
			.sortedWith(compareBy<GenreCandidate>({ genrePriority(it.normalizedKey) }, { it.firstIndex }))
			.take(PakflixOriginalsConfig.GENRE_CANDIDATE_LIMIT)
			.take(PakflixOriginalsConfig.GENRE_ROW_LIMIT)
			.toList()

		Timber.i(
			"Pakflix Originals genre discovery discovered=%d selected=%s",
			genres.size,
			selected.joinToString { "${it.exactName}:${it.count}" },
		)
		return selected
	}

	private suspend fun loadGenreRow(candidate: GenreCandidate): PakflixOriginalsGenreRow? = runCatching {
		val response = api.itemsApi.getItems(
			baseRequest().copy(
				genres = setOf(candidate.exactName),
				fields = ItemRepository.browseFields,
				imageTypeLimit = 1,
				sortBy = setOf(ItemSortBy.DATE_CREATED),
				sortOrder = setOf(SortOrder.DESCENDING),
				startIndex = 0,
				limit = PakflixOriginalsConfig.GENRE_ITEM_LIMIT,
				enableTotalRecordCount = true,
			)
		).content
		val accepted = normalizeTopLevel(response.items, "genre:${candidate.exactName}")
		if (accepted.size < PakflixOriginalsConfig.GENRE_MINIMUM || response.totalRecordCount < PakflixOriginalsConfig.GENRE_MINIMUM) {
			Timber.i(
				"Pakflix Originals genre row removed name=%s returned=%d total=%d minimum=%d",
				candidate.exactName,
				accepted.size,
				response.totalRecordCount,
				PakflixOriginalsConfig.GENRE_MINIMUM,
			)
			null
		} else {
			PakflixOriginalsGenreRow(candidate.exactName, accepted)
		}
	}.onFailure { error -> Timber.w(error, "Pakflix Originals genre row request failed name=%s", candidate.exactName) }
		.getOrNull()

	private fun baseRequest() = GetItemsRequest(
		tags = setOf(PakflixOriginalsConfig.TAG),
		includeItemTypes = TOP_LEVEL_TYPES,
		recursive = true,
	)

	private fun normalizeTopLevel(items: List<BaseItemDto>, source: String): List<BaseItemDto> {
		val accepted = linkedMapOf<UUID, BaseItemDto>()
		var rejected = 0
		items.forEach { item ->
			if (isTopLevelOriginal(item)) accepted.putIfAbsent(item.id, item) else rejected++
		}
		Timber.i(
			"Pakflix Originals normalize source=%s returned=%d accepted=%d rejectedUnexpectedKind=%d kinds=%s",
			source,
			items.size,
			accepted.size,
			rejected,
			items.groupingBy { it.type.serialName }.eachCount(),
		)
		return accepted.values.toList()
	}

	private fun isTopLevelOriginal(item: BaseItemDto) = item.type in TOP_LEVEL_TYPES

	private fun mergeDistinct(first: List<BaseItemDto>, second: List<BaseItemDto>): List<BaseItemDto> = buildList {
		val ids = hashSetOf<UUID>()
		(first + second).forEach { item -> if (isTopLevelOriginal(item) && ids.add(item.id)) add(item) }
	}

	private fun normalizeGenre(value: String) = value.trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ")

	private fun genrePriority(normalized: String): Int {
		val rank = GENRE_PRIORITIES.indexOfFirst { normalized in it }
		return if (rank == -1) Int.MAX_VALUE else rank
	}

	private fun Session.toKey() = SessionKey(serverId, userId)

	private data class SessionKey(val serverId: UUID, val userId: UUID)
	private data class ItemResponse(val items: List<BaseItemDto>, val totalRecordCount: Int)
	private data class GenreAccumulator(val exactName: String, val count: Int, val firstIndex: Int)
	private data class GenreCandidate(val normalizedKey: String, val exactName: String, val count: Int, val firstIndex: Int)

	private companion object {
		val TOP_LEVEL_TYPES = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
		val WHITESPACE = Regex("\\s+")
		val GENRE_PRIORITIES = listOf(
			setOf("drama"),
			setOf("comedy"),
			setOf("romance"),
			setOf("action"),
			setOf("thriller"),
			setOf("family"),
			setOf("animation"),
			setOf("documentary"),
			setOf("history"),
			setOf("war"),
			setOf("crime"),
			setOf("music"),
		)
	}
}
