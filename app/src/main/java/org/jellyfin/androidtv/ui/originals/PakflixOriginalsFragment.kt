package org.jellyfin.androidtv.ui.originals

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
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import timber.log.Timber

class PakflixOriginalsFragment : Fragment() {
	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	) = content {
		val rowsFocusRequester = remember { FocusRequester() }
		LaunchedEffect(rowsFocusRequester) {
			Timber.i("Pakflix Originals startup focus requesting first content row")
			rowsFocusRequester.requestFocus()
		}

		Column {
			MainToolbar(MainToolbarActiveButton.Originals)

			var rowsFragment by remember { mutableStateOf<PakflixOriginalsRowsFragment?>(null) }
			AndroidFragment<PakflixOriginalsRowsFragment>(
				modifier = Modifier
					.focusGroup()
					.focusRequester(rowsFocusRequester)
					.focusProperties {
						onExit = {
							val firstRowSelected = rowsFragment?.selectedPosition?.let { it <= 0 } ?: false
							if (requestedFocusDirection != FocusDirection.Up || !firstRowSelected) {
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
