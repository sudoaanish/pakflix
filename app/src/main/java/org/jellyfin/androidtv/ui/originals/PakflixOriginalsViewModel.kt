package org.jellyfin.androidtv.ui.originals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class PakflixOriginalsViewModel(
	private val repository: PakflixOriginalsRepository,
) : ViewModel() {
	private val _pageState = MutableStateFlow<PakflixOriginalsPageState>(PakflixOriginalsPageState.Loading)
	val pageState: StateFlow<PakflixOriginalsPageState> = _pageState.asStateFlow()

	init {
		viewModelScope.launch {
			runCatching { repository.loadPageData() }
				.onSuccess { data ->
					_pageState.value = PakflixOriginalsPageState.Ready(data)
				}
				.onFailure { error ->
					Timber.w(error, "Pakflix Originals page load failed")
					_pageState.value = PakflixOriginalsPageState.Error(error.message ?: "Originals failed to load")
				}
		}
	}
}
