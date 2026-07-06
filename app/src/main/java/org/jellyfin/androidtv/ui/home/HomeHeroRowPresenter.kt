package org.jellyfin.androidtv.ui.home

import android.view.KeyEvent
import android.view.ViewGroup
import android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import timber.log.Timber

class HomeHeroRowPresenter(
	private val onOpenDetails: (HomeHeroItem) -> Unit,
) : RowPresenter() {
	init {
		selectEffectEnabled = false
	}

	override fun onSelectLevelChanged(holder: ViewHolder) = Unit

	override fun createRowViewHolder(parent: ViewGroup): ViewHolder {
		val view = ComposeView(parent.context).apply {
			setParentCompositionContext(parent.findViewTreeCompositionContext())
			setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
			setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
			isFocusable = true
			isFocusableInTouchMode = false
			isClickable = true
			descendantFocusability = FOCUS_BLOCK_DESCENDANTS
		}

		return ViewHolder(view)
	}

	override fun onBindRowViewHolder(holder: ViewHolder, item: Any) {
		if (item !is HomeHeroRow) return
		val view = holder.view as? ComposeView ?: return

		view.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, hasFocus ->
			item.focused.value = hasFocus
			Timber.i("HomeHero row focus changed focused=%s", hasFocus)
		}
		view.setOnClickListener {
			openDetails(item, "click")
		}
		view.setOnKeyListener { _, keyCode, event ->
			if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false

			when (keyCode) {
				KeyEvent.KEYCODE_DPAD_CENTER,
				KeyEvent.KEYCODE_ENTER,
				KeyEvent.KEYCODE_NUMPAD_ENTER -> {
					openDetails(item, "ok-enter")
					true
				}

				KeyEvent.KEYCODE_DPAD_LEFT -> {
					Timber.i("HomeHero remote left handled")
					item.viewModel.previous()
					true
				}

				KeyEvent.KEYCODE_DPAD_RIGHT -> {
					Timber.i("HomeHero remote right handled")
					item.viewModel.next()
					true
				}

				else -> false
			}
		}

		view.setContent {
			val state by item.viewModel.state.collectAsState()
			val isFocused by item.focused.collectAsState()
			HomeHeroRowContent(
				state = state,
				isFocused = isFocused,
				onPrevious = item.viewModel::previous,
				onNext = item.viewModel::next,
			)
		}
	}

	override fun onUnbindRowViewHolder(holder: ViewHolder) {
		(holder.view as? ComposeView)?.apply {
			setOnKeyListener(null)
			setOnClickListener(null)
			onFocusChangeListener = null
			setContent {}
		}
	}

	private fun openDetails(row: HomeHeroRow, reason: String) {
		val item = row.viewModel.currentItem()
		if (item == null) {
			Timber.i("HomeHero details activation ignored reason=%s item=null", reason)
			return
		}

		Timber.i(
			"HomeHero details activation reason=%s title=%s kind=%s",
			reason,
			item.title,
			item.kind.serialName,
		)
		onOpenDetails(item)
	}
}
