package org.jellyfin.androidtv.ui.browsing

import android.os.Bundle
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.Locale

@Suppress("DEPRECATION")
class PakflixMediaRowsFragment : RowsSupportFragment() {
	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val itemLauncher by inject<ItemLauncher>()

	private lateinit var rowsAdapter: MutableObjectAdapter<Row>
	private val cardPresenter = CardPresenter()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		rowsAdapter = MutableObjectAdapter(PositionableListRowPresenter(null, R.font.space_grotesk_medium))
		adapter = rowsAdapter

		onItemViewClickedListener = OnItemViewClickedListener { _: Presenter.ViewHolder?, item: Any?, _: RowPresenter.ViewHolder?, row: Row ->
			if (item is BaseRowItem) {
				itemLauncher.launch(item, (row as ListRow).adapter as ItemRowAdapter, requireContext())
			}
		}
		onItemViewSelectedListener = OnItemViewSelectedListener { _: Presenter.ViewHolder?, item: Any?, _: RowPresenter.ViewHolder?, row: Row ->
			if (item !is BaseRowItem) {
				backgroundService.clearBackgrounds()
			} else {
				val rowAdapter = (row as? ListRow)?.adapter as? ItemRowAdapter
				rowAdapter?.loadMoreItemsIfNeeded(rowAdapter.indexOf(item))
				backgroundService.setBackground(item.baseItem)
			}
		}

		val userView = readSelectedUserView()
		val itemKind = when (userView?.collectionType) {
			CollectionType.MOVIES -> BaseItemKind.MOVIE
			CollectionType.TVSHOWS -> BaseItemKind.SERIES
			else -> null
		}
		if (userView == null || itemKind == null) {
			Timber.w(
				"Pakflix media rows skipped invalid user view name=%s collectionType=%s",
				userView?.name,
				userView?.collectionType,
			)
			return
		}

		Timber.i(
			"Pakflix media page loading mode=%s library=%s type=%s",
			userView.collectionType,
			userView.name,
			itemKind,
		)
		addPrimaryRows(userView, itemKind)

		lifecycleScope.launch {
			val genres = loadGenres(userView, itemKind)
			addGenreRows(userView, itemKind, genres)
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		view.post {
			Timber.i("Pakflix media rows startup selecting first content row")
			setSelectedPosition(0)
			verticalGridView?.requestFocus()
		}
	}

	private fun readSelectedUserView(): BaseItemDto? {
		val encoded = parentFragment?.arguments?.getString(Extras.Folder)
			?: arguments?.getString(Extras.Folder)
			?: return null
		return runCatching { Json.decodeFromString<BaseItemDto>(encoded) }
			.onFailure { error -> Timber.w(error, "Pakflix media rows could not decode selected user view") }
			.getOrNull()
	}

	private fun addPrimaryRows(userView: BaseItemDto, itemKind: BaseItemKind) {
		val latestKind = if (itemKind == BaseItemKind.SERIES) BaseItemKind.EPISODE else BaseItemKind.MOVIE
		val latestRequest = GetLatestMediaRequest(
			fields = ItemRepository.browseFields,
			parentId = userView.id,
			limit = ITEM_LIMIT,
			imageTypeLimit = 1,
			includeItemTypes = setOf(latestKind),
			groupItems = true,
		)
		addLatestRow(getString(R.string.lbl_latest_in, userView.name.orEmpty()), latestRequest)

		if (itemKind == BaseItemKind.MOVIE) {
			val collectionsRequest = BrowsingUtils.createCollectionsRequest(userView.id).copy(
				startIndex = 0,
				limit = ITEM_LIMIT,
				enableTotalRecordCount = true,
			)
			addItemsRow(getString(R.string.lbl_collections), collectionsRequest, minimumItems = 1)
		}
	}

	private suspend fun loadGenres(userView: BaseItemDto, itemKind: BaseItemKind): List<GenreCandidate> {
		Timber.i(
			"Pakflix genre-list request start mode=%s library=%s includeItemTypes=%s limit=%d",
			userView.collectionType,
			userView.name,
			itemKind,
			GENRE_CANDIDATE_LIMIT,
		)

		val response = runCatching {
			withContext(Dispatchers.IO) {
				api.genresApi.getGenres(
					parentId = userView.id,
					fields = setOf(ItemFields.RECURSIVE_ITEM_COUNT),
					includeItemTypes = setOf(itemKind),
					sortBy = setOf(ItemSortBy.SORT_NAME),
					limit = GENRE_CANDIDATE_LIMIT,
					enableTotalRecordCount = true,
				).content
			}
		}.onFailure { error ->
			Timber.w(
				error,
				"Pakflix genre-list request failed mode=%s library=%s",
				userView.collectionType,
				userView.name,
			)
		}.getOrNull() ?: return emptyList()

		val rawGenres = response.items.take(GENRE_CANDIDATE_LIMIT)
		val countsAvailable = rawGenres.any { it.recursiveItemCount != null }
		Timber.i(
			"Pakflix genre-list result mode=%s count=%d recursiveCountsAvailable=%s candidates=%s",
			userView.collectionType,
			rawGenres.size,
			countsAvailable,
			rawGenres.joinToString { "${it.name}:${it.recursiveItemCount ?: "unknown"}" },
		)

		val deduped = linkedMapOf<String, GenreCandidate>()
		rawGenres.forEachIndexed { index, genre ->
			val exactName = genre.name?.trim()
			if (exactName.isNullOrEmpty()) {
				Timber.i("Pakflix genre skipped reason=missing-name mode=%s", userView.collectionType)
				return@forEachIndexed
			}
			val recursiveItemCount = genre.recursiveItemCount
			if (recursiveItemCount != null && recursiveItemCount < MINIMUM_GENRE_ITEMS) {
				Timber.i(
					"Pakflix genre skipped reason=under-six mode=%s name=%s count=%d",
					userView.collectionType,
					exactName,
					recursiveItemCount,
				)
				return@forEachIndexed
			}

			val normalizedName = normalizeGenreName(exactName)
			if (deduped.containsKey(normalizedName)) {
				Timber.i(
					"Pakflix genre skipped reason=duplicate-normalized-key mode=%s name=%s key=%s",
					userView.collectionType,
					exactName,
					normalizedName,
				)
				return@forEachIndexed
			}
			deduped[normalizedName] = GenreCandidate(exactName, index)
		}

		val ordered = deduped.values
			.sortedWith(compareBy<GenreCandidate>({ genrePriority(it.exactName, itemKind) }, { it.serverOrder }))
			.take(MAXIMUM_GENRE_ROWS)
		Timber.i(
			"Pakflix genre rows selected mode=%s names=%s",
			userView.collectionType,
			ordered.joinToString { it.exactName },
		)
		return ordered
	}

	private fun addGenreRows(userView: BaseItemDto, itemKind: BaseItemKind, genres: List<GenreCandidate>) {
		genres.forEach { genre ->
			val request = GetItemsRequest(
				parentId = userView.id,
				includeItemTypes = setOf(itemKind),
				genres = setOf(genre.exactName),
				recursive = true,
				fields = ItemRepository.browseFields,
				imageTypeLimit = 1,
				sortBy = setOf(ItemSortBy.SORT_NAME),
				startIndex = 0,
				limit = ITEM_LIMIT,
				enableTotalRecordCount = true,
			)
			addItemsRow(genre.exactName, request, minimumItems = MINIMUM_GENRE_ITEMS)
		}
		removeStaleEmptyPlaceholder()

		val primaryCalls = if (itemKind == BaseItemKind.MOVIE) 2 else 1
		Timber.i(
			"Pakflix media initial row requests mode=%s count=%d genreRows=%d maxBudget=%d",
			userView.collectionType,
			primaryCalls + 1 + genres.size,
			genres.size,
			if (itemKind == BaseItemKind.MOVIE) 11 else 10,
		)
	}

	private fun removeStaleEmptyPlaceholder() {
		if (rowsAdapter.size() <= 1) return
		val emptyTitle = getString(R.string.lbl_empty)
		rowsAdapter
			.filterIsInstance<ListRow>()
			.filter { it.headerItem?.name == emptyTitle && it.adapter !is ItemRowAdapter }
			.forEach(rowsAdapter::remove)
	}

	private fun addLatestRow(title: String, request: GetLatestMediaRequest) {
		val rowAdapter = ItemRowAdapter(requireContext(), request, true, cardPresenter, rowsAdapter)
		val row = ListRow(HeaderItem(title), rowAdapter)
		rowAdapter.setRow(row)
		rowAdapter.setRetrieveFinishedListener(object : EmptyResponse(lifecycle) {
			override fun onResponse() = Unit

			override fun onError(exception: Exception) {
				Timber.w(exception, "Pakflix media row request failed title=%s", title)
				rowsAdapter.remove(row)
			}
		})
		rowsAdapter.add(row)
		rowAdapter.Retrieve()
	}

	private fun addItemsRow(title: String, request: GetItemsRequest, minimumItems: Int) {
		val rowAdapter = ItemRowAdapter(
			requireContext(),
			request,
			ITEM_LIMIT,
			false,
			true,
			cardPresenter,
			rowsAdapter,
			QueryType.Items,
		)
		val row = ListRow(HeaderItem(title), rowAdapter)
		rowAdapter.setRow(row)
		rowAdapter.setRetrieveFinishedListener(object : EmptyResponse(lifecycle) {
			override fun onResponse() {
				val totalItems = rowAdapter.totalItems
				if (totalItems < minimumItems) {
					Timber.i(
						"Pakflix media row removed after retrieval title=%s total=%d minimum=%d",
						title,
						totalItems,
						minimumItems,
					)
					rowsAdapter.remove(row)
				}
			}

			override fun onError(exception: Exception) {
				Timber.w(exception, "Pakflix media row request failed title=%s", title)
				rowsAdapter.remove(row)
			}
		})
		rowsAdapter.add(row)
		rowAdapter.Retrieve()
	}

	private fun genrePriority(name: String, itemKind: BaseItemKind): Int {
		val normalized = normalizeGenreName(name)
		val priorities = if (itemKind == BaseItemKind.MOVIE) MOVIE_GENRE_PRIORITIES else TV_GENRE_PRIORITIES
		val rank = priorities.indexOfFirst { normalized in it }
		return if (rank == -1) Int.MAX_VALUE else rank
	}

	private fun normalizeGenreName(name: String) = name
		.trim()
		.lowercase(Locale.ROOT)
		.replace(Regex("\\s+"), " ")

	private data class GenreCandidate(
		val exactName: String,
		val serverOrder: Int,
	)

	companion object {
		private const val GENRE_CANDIDATE_LIMIT = 20
		private const val MAXIMUM_GENRE_ROWS = 8
		private const val MINIMUM_GENRE_ITEMS = 6
		private const val ITEM_LIMIT = 25

		private val MOVIE_GENRE_PRIORITIES = listOf(
			setOf("action"),
			setOf("adventure"),
			setOf("comedy"),
			setOf("drama"),
			setOf("science fiction", "sci-fi", "sci fi"),
			setOf("horror"),
			setOf("thriller"),
			setOf("family"),
			setOf("animation"),
			setOf("mystery"),
			setOf("romance"),
			setOf("documentary"),
		)
		private val TV_GENRE_PRIORITIES = listOf(
			setOf("drama"),
			setOf("comedy"),
			setOf("crime"),
			setOf("sci-fi & fantasy", "science fiction", "sci-fi", "sci fi"),
			setOf("animation"),
			setOf("documentary"),
			setOf("action & adventure"),
			setOf("mystery"),
			setOf("family"),
			setOf("reality"),
		)
	}
}
