package org.jellyfin.androidtv.ui.base.button

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class ButtonColors(
	val containerColor: Color,
	val contentColor: Color,
	val focusedContainerColor: Color,
	val focusedContentColor: Color,
	val disabledContainerColor: Color,
	val disabledContentColor: Color,
	val focusedBorderColor: Color = Color.Transparent,
	val focusedBorderWidth: Dp = 0.dp,
)
