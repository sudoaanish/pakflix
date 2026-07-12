package org.jellyfin.androidtv.ui.presentation

import android.widget.TextView
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import androidx.leanback.widget.RowHeaderPresenter

class CustomRowHeaderPresenter(
	@FontRes private val fontRes: Int? = null,
) : RowHeaderPresenter() {
	override fun onBindViewHolder(viewHolder: androidx.leanback.widget.Presenter.ViewHolder, item: Any?) {
		super.onBindViewHolder(viewHolder, item)

		val headerText = viewHolder.view.findViewById<TextView>(androidx.leanback.R.id.row_header) ?: return
		val resource = fontRes ?: return
		headerText.typeface = ResourcesCompat.getFont(headerText.context, resource)
	}

	override fun onSelectLevelChanged(holder: ViewHolder) = Unit
}
