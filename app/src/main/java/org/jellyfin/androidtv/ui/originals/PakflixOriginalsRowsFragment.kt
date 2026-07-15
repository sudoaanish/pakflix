package org.jellyfin.androidtv.ui.originals

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

@Suppress("DEPRECATION")
class PakflixOriginalsRowsFragment : RowsSupportFragment() {
	private val backgroundService by inject<BackgroundService>()
	private val itemLauncher by inject<ItemLauncher>()
	private val originalsViewModel by viewModel<PakflixOriginalsViewModel>()

	private val cardPresenter = CardPresenter()
	private lateinit var rowsAdapter: MutableObjectAdapter<Row>
	private var rendered = false
	private var initialFocusApplied = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val defaultPresenter = PositionableListRowPresenter(null, R.font.space_grotesk_medium)
		rowsAdapter = MutableObjectAdapter(defaultPresenter)
		adapter = rowsAdapter

		onItemViewClickedListener = OnItemViewClickedListener { _: Presenter.ViewHolder?, item: Any?, _: RowPresenter.ViewHolder?, row: Row ->
			if (item is BaseRowItem && row is ListRow) {
				itemLauncher.launch(item, row.adapter as ItemRowAdapter, requireContext())
			}
		}
		onItemViewSelectedListener = OnItemViewSelectedListener { _: Presenter.ViewHolder?, item: Any?, _: RowPresenter.ViewHolder?, row: Row ->
			when {
				item is BaseRowItem -> {
					val rowAdapter = (row as? ListRow)?.adapter as? ItemRowAdapter
					rowAdapter?.loadMoreItemsIfNeeded(rowAdapter.indexOf(item))
					backgroundService.setBackground(item.baseItem)
				}
				else -> backgroundService.clearBackgrounds()
			}
		}

		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				launch {
					originalsViewModel.pageState.collect { state ->
						when (state) {
							PakflixOriginalsPageState.Loading -> Unit
							is PakflixOriginalsPageState.Ready -> renderPage(state.data)
							is PakflixOriginalsPageState.Error -> Timber.w("Pakflix Originals rows unavailable: %s", state.message)
						}
					}
				}
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		if (rendered) applyInitialFocus()
	}

	private fun renderPage(data: PakflixOriginalsPageData) {
		if (rendered) return
		rendered = true
		addStaticRow(getString(R.string.lbl_top_10), data.top10)
		addStaticRow(getString(R.string.lbl_recent_movies), data.recentMovies)
		addStaticRow(getString(R.string.lbl_recent_shows), data.recentShows)
		data.genreRows.forEach { row -> addStaticRow(row.exactName, row.items) }

		Timber.i(
			"Pakflix Originals rows rendered without hero count=%d top10=%d recentMovies=%d recentShows=%d genres=%d rows=%s",
			rowsAdapter.size(),
			data.top10.size,
			data.recentMovies.size,
			data.recentShows.size,
			data.genreRows.size,
			rowsAdapter
				.filterIsInstance<ListRow>()
				.joinToString { "${it.headerItem?.name}:${it.adapter.size()}" },
		)
		applyInitialFocus()
	}

	private fun addStaticRow(title: String, items: List<org.jellyfin.sdk.model.api.BaseItemDto>) {
		if (items.isEmpty()) return
		val rowAdapter = ItemRowAdapter(requireContext(), items, cardPresenter, rowsAdapter, QueryType.StaticItems)
		val row = ListRow(HeaderItem(title), rowAdapter)
		rowAdapter.setRow(row)
		rowAdapter.Retrieve()
		Timber.i(
			"Pakflix Originals row prepared title=%s sourceItems=%d adapter=%s adapterItems=%d firstTitle=%s firstKind=%s",
			title,
			items.size,
			rowAdapter.javaClass.simpleName,
			rowAdapter.size(),
			items.firstOrNull()?.name ?: "unknown",
			items.firstOrNull()?.type ?: "unknown",
		)
		rowsAdapter.add(row)
	}

	private fun applyInitialFocus() {
		if (initialFocusApplied || view == null) return
		if (rowsAdapter.size() == 0) {
			Timber.i("Pakflix Originals initial focus skipped reason=no-content-rows")
			verticalGridView?.clearFocus()
			return
		}
		initialFocusApplied = true
		view?.post {
			val firstRow = rowsAdapter[0] as? ListRow
			Timber.i(
				"Pakflix Originals initial focus selecting first normal row index=0 title=%s adapterItems=%d",
				firstRow?.headerItem?.name ?: "unknown",
				firstRow?.adapter?.size() ?: 0,
			)
			setSelectedPosition(0)
			verticalGridView?.requestFocus()
		}
	}
}
