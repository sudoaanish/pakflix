package org.jellyfin.androidtv.ui.browsing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.fragment.app.Fragment
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.content
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import org.koin.android.ext.android.inject
import timber.log.Timber

class PakflixMediaFragment : Fragment() {
	private val navigationRepository by inject<NavigationRepository>()

	private val selectedUserView by lazy {
		val encoded = arguments?.getString(Extras.Folder)
		if (encoded == null) null
		else runCatching { Json.decodeFromString<BaseItemDto>(encoded) }
			.onFailure { error -> Timber.w(error, "Pakflix media destination could not decode selected user view") }
			.getOrNull()
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	) = content {
		val userView = selectedUserView
		val activeButton = when (userView?.collectionType) {
			CollectionType.MOVIES -> MainToolbarActiveButton.Movies
			CollectionType.TVSHOWS -> MainToolbarActiveButton.TvShows
			else -> null
		}

		if (userView == null || activeButton == null) {
			LaunchedEffect(Unit) {
				Timber.w(
					"Pakflix media destination rejected invalid user view name=%s collectionType=%s",
					userView?.name,
					userView?.collectionType,
				)
				if (!navigationRepository.goBack()) {
					navigationRepository.reset(Destinations.home, clearHistory = true)
				}
			}
			return@content
		}

		val rowsFocusRequester = remember { FocusRequester() }
		LaunchedEffect(rowsFocusRequester) {
			Timber.i(
				"Pakflix media startup focus requesting first content row mode=%s library=%s",
				userView.collectionType,
				userView.name,
			)
			rowsFocusRequester.requestFocus()
		}

		Column {
			MainToolbar(activeButton)

			var rowsFragment by remember { mutableStateOf<PakflixMediaRowsFragment?>(null) }
			AndroidFragment<PakflixMediaRowsFragment>(
				modifier = Modifier
					.focusGroup()
					.focusRequester(rowsFocusRequester)
					.focusProperties {
						onExit = {
							val isFirstRowSelected = rowsFragment?.selectedPosition?.let { it <= 0 } ?: false
							if (requestedFocusDirection != FocusDirection.Up || !isFirstRowSelected) {
								cancelFocusChange()
							} else {
								rowsFragment?.selectedPosition = 0
								rowsFragment?.verticalGridView?.clearFocus()
							}
						}
					}
					.fillMaxSize(),
				onUpdate = { fragment -> rowsFragment = fragment },
			)
		}
	}
}
