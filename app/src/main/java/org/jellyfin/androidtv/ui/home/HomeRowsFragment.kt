package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.constant.LiveTvOption
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.browsing.CompositeClickedListener
import org.jellyfin.androidtv.ui.browsing.CompositeSelectedListener
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.refreshItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class HomeRowsFragment : RowsSupportFragment(), AudioEventListener, View.OnKeyListener {
	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val playbackManager by inject<PlaybackManager>()
	private val mediaManager by inject<MediaManager>()
	private val notificationsRepository by inject<NotificationsRepository>()
	private val userRepository by inject<UserRepository>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val userViewsRepository by inject<UserViewsRepository>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val customMessageRepository by inject<CustomMessageRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val itemLauncher by inject<ItemLauncher>()
	private val keyProcessor by inject<KeyProcessor>()
	private val homeHeroViewModel by viewModel<HomeHeroViewModel>()

	private val helper by lazy { HomeFragmentHelper(requireContext(), userRepository) }

	// Data
	private var currentItem: BaseRowItem? = null
	private var currentRow: ListRow? = null
	private var justLoaded = true
	private var lastHandledPlaybackRefresh: Instant? = null
	private var resetResumeRowWhenAttached = false
	private var initialHeroFocusApplied = false

	// Special rows
	private val notificationsRow by lazy { NotificationsHomeFragmentRow(lifecycleScope, notificationsRepository) }
	private val nowPlaying by lazy { HomeFragmentNowPlayingRow(lifecycleScope, playbackManager, mediaManager) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		adapter = MutableObjectAdapter<Row>(
			HomeRowsPresenterSelector(
				homeHeroRowPresenter = HomeHeroRowPresenter(::openHeroDetails),
				defaultRowPresenter = PositionableListRowPresenter(),
			)
		)

		homeHeroViewModel.state
			.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			.onEach(::updateHeroRow)
			.launchIn(lifecycleScope)

		lifecycleScope.launch(Dispatchers.IO) {
			val currentUser = withTimeout(30.seconds) {
				userRepository.currentUser.filterNotNull().first()
			}

			// Start out with default sections
			val homesections = userSettingPreferences.activeHomesections

			// Make sure the rows are empty
			val rows = mutableListOf<HomeFragmentRow>()

			// Check for coroutine cancellation
			if (!isActive) return@launch

			// Actually add the sections
			for (section in homesections) when (section) {
				HomeSectionType.LATEST_MEDIA -> rows.add(helper.loadRecentlyAdded(userViewsRepository.views.first()))
				HomeSectionType.LIBRARY_TILES_SMALL -> rows.add(HomeFragmentViewsRow(small = false))
				HomeSectionType.LIBRARY_BUTTONS -> rows.add(HomeFragmentViewsRow(small = true))
				HomeSectionType.RESUME -> rows.add(helper.loadResumeVideo())
				HomeSectionType.RESUME_AUDIO -> rows.add(helper.loadResumeAudio())
				HomeSectionType.RESUME_BOOK -> Unit // Books are not (yet) supported
				HomeSectionType.ACTIVE_RECORDINGS -> rows.add(helper.loadLatestLiveTvRecordings())
				HomeSectionType.NEXT_UP -> rows.add(helper.loadNextUp())
				HomeSectionType.LIVE_TV -> if (currentUser.policy?.enableLiveTvAccess == true) {
					rows.add(HomeFragmentLiveTVRow(requireActivity(), userRepository))
					rows.add(helper.loadOnNow())
				}

				HomeSectionType.NONE -> Unit
			}

			// Add sections to layout
			withContext(Dispatchers.Main) {
				val cardPresenter = CardPresenter()

				// Add rows in order
				notificationsRow.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)
				nowPlaying.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)
				for (row in rows) row.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)

				// Wire up Live TV sibling rows so the On Now row removes the buttons row when empty
				@Suppress("UNCHECKED_CAST")
				val rowsAdapter = adapter as MutableObjectAdapter<Row>
				for (i in 0 until rowsAdapter.size()) {
					val listRow = rowsAdapter.get(i) as? ListRow ?: continue
					val itemAdapter = listRow.adapter as? ItemRowAdapter ?: continue
					if (itemAdapter.queryType == QueryType.LiveTvProgram && i > 0) {
						val previousRow = rowsAdapter.get(i - 1)
						if (previousRow != null) itemAdapter.setSiblingRow(previousRow)
					}
				}
			}
		}

		onItemViewClickedListener = CompositeClickedListener().apply {
			registerListener(ItemViewClickedListener())
			registerListener(notificationsRow::onItemClicked)
		}

		onItemViewSelectedListener = CompositeSelectedListener().apply {
			registerListener(ItemViewSelectedListener())
		}

		customMessageRepository.message
			.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { message ->
				when (message) {
					CustomMessage.RefreshCurrentItem -> refreshCurrentItem()
					else -> Unit
				}
			}.launchIn(lifecycleScope)

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				api.webSocket.subscribe<UserDataChangedMessage>()
					.onEach { refreshRows(force = true, delayed = false) }
					.launchIn(this)

				api.webSocket.subscribe<LibraryChangedMessage>()
					.onEach { refreshRows(force = true, delayed = false) }
					.launchIn(this)
			}
		}

		// Subscribe to Audio messages
		mediaManager.addAudioEventListener(this)
	}

	private fun updateHeroRow(state: HomeHeroState) {
		val rowsAdapter = adapter as? MutableObjectAdapter<Row> ?: return
		val existingIndex = rowsAdapter.indexOfFirst { it is HomeHeroRow }
		val shouldShowHero = state is HomeHeroState.Ready && state.items.isNotEmpty()

		when {
			shouldShowHero && existingIndex == -1 -> {
				Timber.i("Adding HomeHero row to Home row stack")
				rowsAdapter.add(0, HomeHeroRow(homeHeroViewModel))
				selectInitialHeroRow()
				updateHeroBackground(state, "row-added")
			}

			shouldShowHero -> {
				if (selectedPosition == 0) updateHeroBackground(state, "state-update")
			}

			existingIndex >= 0 -> {
				Timber.i("Removing HomeHero row from Home row stack")
				rowsAdapter.removeAt(existingIndex)
			}
		}
	}

	private fun selectInitialHeroRow() {
		if (initialHeroFocusApplied) return
		initialHeroFocusApplied = true

		view?.post {
			Timber.i("HomeHero initial focus selecting row 0")
			setSelectedPosition(0)
			verticalGridView?.requestFocus()
		}
	}

	private fun updateHeroBackground(state: HomeHeroState, reason: String) {
		val readyState = state as? HomeHeroState.Ready
		val item = readyState?.items?.getOrNull(readyState.selectedIndex)
		if (item == null) {
			Timber.i("HomeHero background fallback reason=%s item=null", reason)
			backgroundService.clearBackgrounds()
			return
		}

		Timber.i(
			"HomeHero background using BackgroundService reason=%s title=%s kind=%s",
			reason,
			item.title,
			item.kind.serialName,
		)
		backgroundService.setBackground(item.backgroundItem)
	}

	private fun openHeroDetails(item: HomeHeroItem) {
		Timber.i("HomeHero opening Details title=%s kind=%s", item.title, item.kind.serialName)
		navigationRepository.navigate(Destinations.itemDetails(item.id))
	}

	override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
		if (event?.action != KeyEvent.ACTION_UP) return false
		return keyProcessor.handleKey(keyCode, currentItem, activity)
	}

	override fun onResume() {
		super.onResume()

		//React to deletion
		if (currentRow != null && currentItem != null && currentItem?.baseItem != null && currentItem!!.baseItem!!.id == dataRefreshService.lastDeletedItemId) {
			(currentRow!!.adapter as ItemRowAdapter).remove(currentItem)
			currentItem = null
			dataRefreshService.lastDeletedItemId = null
		}

		if (!justLoaded) {
			//Re-retrieve anything that needs it but delay slightly so we don't take away gui landing
			val playbackRefresh = consumePlaybackRefreshTimestamp()
			refreshCurrentItem()
			refreshRows(resetResumeRowPosition = playbackRefresh != null)
		} else {
			justLoaded = false
		}

		// Update audio queue
		Timber.i("Updating audio queue in HomeFragment (onResume)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)
	}

	override fun onQueueStatusChanged(hasQueue: Boolean) {
		if (activity == null || requireActivity().isFinishing) return

		Timber.i("Updating audio queue in HomeFragment (onQueueStatusChanged)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)
	}

	private fun refreshRows(
		force: Boolean = false,
		delayed: Boolean = true,
		resetResumeRowPosition: Boolean = false,
	) {
		val activeRowIndex = selectedPosition
		val activeRow = adapter.get(activeRowIndex) as? ListRow
		val activeItemIndex = (activeRow?.adapter as? ItemRowAdapter)?.indexOf(currentItem) ?: -1

		lifecycleScope.launch(Dispatchers.IO) {
			if (delayed) delay(1.5.seconds)

			repeat(adapter.size()) { i ->
				val rowAdapter = (adapter[i] as? ListRow)?.adapter as? ItemRowAdapter
				if (force) rowAdapter?.Retrieve()
				else if (resetResumeRowPosition && rowAdapter?.queryType == QueryType.Resume) {
					rowAdapter.ReRetrieveIfNeeded(object : EmptyResponse(lifecycle) {
						override fun onResponse() {
							resetRowPositionToStart(i)
						}

						override fun onError(exception: Exception) {
							Timber.w(exception, "Unable to refresh Continue Watching before resetting row position")
						}
					})
				} else rowAdapter?.ReRetrieveIfNeeded()
			}

			withContext(Dispatchers.Main) {
				if (activeRowIndex in 0 until adapter.size()) {
					setSelectedPosition(activeRowIndex)
				}
			}
		}
	}

	private fun consumePlaybackRefreshTimestamp(): Instant? {
		val latestPlayback = listOfNotNull(
			dataRefreshService.lastPlayback,
			dataRefreshService.lastMoviePlayback,
			dataRefreshService.lastTvPlayback,
		).maxOrNull() ?: return null

		val lastHandled = lastHandledPlaybackRefresh
		if (lastHandled != null && !latestPlayback.isAfter(lastHandled)) return null

		lastHandledPlaybackRefresh = latestPlayback
		return latestPlayback
	}

	private fun resetRowPositionToStart(rowIndex: Int) {
		view?.post {
			val row = adapter.get(rowIndex) as? ListRow ?: return@post
			val rowAdapter = row.adapter as? ItemRowAdapter ?: return@post
			if (rowAdapter.queryType != QueryType.Resume || rowAdapter.size() == 0) return@post

			val rowView = verticalGridView?.findViewHolderForAdapterPosition(rowIndex)?.itemView
			val horizontalGrid = rowView?.findDescendant(HorizontalGridView::class.java)
			if (horizontalGrid == null) {
				Timber.i("Continue Watching row is not attached; skipping horizontal position reset")
				resetResumeRowWhenAttached = true
				return@post
			}

			Timber.i("Resetting Continue Watching row horizontal position to 0 after playback refresh")
			horizontalGrid.scrollToPosition(0)
			horizontalGrid.setSelectedPosition(0)
			resetResumeRowWhenAttached = false
		}
	}

	private fun <T : View> View.findDescendant(type: Class<T>): T? {
		if (type.isInstance(this)) return type.cast(this)
		if (this !is ViewGroup) return null

		for (i in 0 until childCount) {
			val child = getChildAt(i)
			val match = child.findDescendant(type)
			if (match != null) return match
		}

		return null
	}

	private fun refreshCurrentItem() {
		val adapter = currentRow?.adapter as? ItemRowAdapter ?: return
		val item = currentItem ?: return

		Timber.i("Refresh item ${item.getFullName(requireContext())}")
		adapter.refreshItem(api, this, item)
	}

	override fun onDestroy() {
		super.onDestroy()

		mediaManager.removeAudioEventListener(this)
	}

	private inner class ItemViewClickedListener : OnItemViewClickedListener {
		override fun onItemClicked(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item is GridButton) {
				when (item.id) {
					LiveTvOption.LIVE_TV_GUIDE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvGuide)
					LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSchedule)
					LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvRecordings)
					LiveTvOption.LIVE_TV_SERIES_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSeriesRecordings)
				}
			}

			if (item !is BaseRowItem) return
			if (row !is ListRow) return
			@Suppress("UNCHECKED_CAST")
			itemLauncher.launch(item, row.adapter as MutableObjectAdapter<Any>, requireContext())
		}
	}

	private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
		override fun onItemSelected(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item is HomeHeroRow || row is HomeHeroRow) {
				currentItem = null
				currentRow = null
				updateHeroBackground(homeHeroViewModel.state.value, "row-selected")
				return
			}

			if (item !is BaseRowItem) {
				currentItem = null
				//fill in default background
				backgroundService.clearBackgrounds()
			} else {
				currentItem = item
				currentRow = row as ListRow

				val itemRowAdapter = row.adapter as? ItemRowAdapter
				itemRowAdapter?.loadMoreItemsIfNeeded(itemRowAdapter.indexOf(item))
				if (resetResumeRowWhenAttached && itemRowAdapter?.queryType == QueryType.Resume) {
					resetRowPositionToStart(selectedPosition)
				}

				backgroundService.setBackground(item.baseItem)
			}
		}
	}
}
