package org.jellyfin.androidtv.ui.home

import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector

class HomeRowsPresenterSelector(
	private val homeHeroRowPresenter: Presenter,
	private val defaultRowPresenter: Presenter,
) : PresenterSelector() {
	override fun getPresenter(item: Any?): Presenter = when (item) {
		is HomeHeroRow -> homeHeroRowPresenter
		else -> defaultRowPresenter
	}

	override fun getPresenters(): Array<Presenter> = arrayOf(homeHeroRowPresenter, defaultRowPresenter)
}

