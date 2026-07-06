package org.jellyfin.androidtv.ui.home

import androidx.leanback.widget.Row
import kotlinx.coroutines.flow.MutableStateFlow

class HomeHeroRow(
	val viewModel: HomeHeroViewModel,
) : Row() {
	val focused = MutableStateFlow(false)
}
