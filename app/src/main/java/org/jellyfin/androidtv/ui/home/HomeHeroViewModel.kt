package org.jellyfin.androidtv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

class HomeHeroViewModel(
	private val repository: HomeHeroRepository,
) : ViewModel() {
	private val _state = MutableStateFlow<HomeHeroState>(HomeHeroState.Loading)
	val state: StateFlow<HomeHeroState> = _state.asStateFlow()

	private var loaded = false
	private var slideshowJob: Job? = null

	init {
		load()
	}

	fun load() {
		if (loaded) return
		loaded = true

		viewModelScope.launch {
			runCatching { repository.loadHeroItems() }
				.onSuccess { items ->
					_state.value = if (items.isEmpty()) HomeHeroState.Empty else HomeHeroState.Ready(items)
					Timber.i("HomeHero state=%s count=%s", _state.value::class.simpleName, items.size)
					logVisibleItem("initial")
					scheduleSlideshow()
				}
				.onFailure { error ->
					Timber.w(error, "HomeHero failed to load")
					_state.value = HomeHeroState.Error(error.message ?: "Home hero failed")
				}
		}
	}

	fun previous() = move(delta = -1, reason = "manual-previous")

	fun next() = move(delta = 1, reason = "manual-next")

	fun currentItem(): HomeHeroItem? {
		val currentState = _state.value as? HomeHeroState.Ready ?: return null
		return currentState.items.getOrNull(currentState.selectedIndex)
	}

	private fun move(delta: Int, reason: String) {
		val currentState = _state.value as? HomeHeroState.Ready ?: return
		val items = currentState.items
		if (items.size < 2) return

		val nextIndex = (currentState.selectedIndex + delta).floorMod(items.size)
		_state.value = currentState.copy(selectedIndex = nextIndex)
		logVisibleItem(reason)
		scheduleSlideshow()
	}

	private fun scheduleSlideshow() {
		slideshowJob?.cancel()
		val currentState = _state.value as? HomeHeroState.Ready ?: return
		if (currentState.items.size < 2) return

		slideshowJob = viewModelScope.launch {
			delay(SLIDESHOW_INTERVAL)
			move(delta = 1, reason = "slideshow")
		}
	}

	private fun logVisibleItem(reason: String) {
		val currentState = _state.value as? HomeHeroState.Ready ?: return
		val item = currentState.items.getOrNull(currentState.selectedIndex) ?: return

		Timber.i(
			"HomeHero visible reason=%s index=%s count=%s title=%s kind=%s",
			reason,
			currentState.selectedIndex,
			currentState.items.size,
			item.title,
			item.kind.serialName,
		)
	}

	private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

	companion object {
		val SLIDESHOW_INTERVAL = 7.seconds
	}
}
