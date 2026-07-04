package org.jellyfin.androidtv.ui.base

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import org.jellyfin.design.Tokens

fun colorScheme(): ColorScheme = ColorScheme(
	background = Color(0xFF0B0E0C),
	onBackground = Tokens.Color.colorBluegrey25,
	button = Color(0xB3747474),
	onButton = Color(0xFFDDDDDD),
	buttonFocused = Color(0xFF007200),
	onButtonFocused = Color(0xFFFFFFFF),
	buttonDisabled = Color(0x33747474),
	onButtonDisabled = Color(0xFF686868),
	buttonActive = Color(0xFF007200),
	onButtonActive = Color(0xFFFFFFFF),
	input = Color(0xB3747474),
	onInput = Color(0xE6CCCCCC),
	inputFocused = Color(0xFF007200),
	onInputFocused = Color(0xFFFFFFFF),
	rangeControlBackground = Tokens.Color.colorBluegrey700,
	rangeControlFill = Color(0xFF38B000),
	rangeControlKnob = Color(0xFFFFFFFF),
	seekbarBuffer = Tokens.Color.colorBluegrey300,
	recording = Tokens.Color.colorRed300,
	onRecording = Tokens.Color.colorRed25,
	badge = Color(0xFF007200),
	onBadge = Color(0xFFFFFFFF),
	listHeader = Tokens.Color.colorGrey50,
	listOverline = Tokens.Color.colorGrey500,
	listHeadline = Tokens.Color.colorGrey25,
	listCaption = Tokens.Color.colorGrey200,
	listButton = Color.Transparent,
	listButtonFocused = Color(0xFF0D3B24),
	surface = Color(0xFF14241B),
	scrim = Tokens.Color.colorBlack.copy(alpha = 0.67f),
)


@Immutable
data class ColorScheme(
	val background: Color,
	val onBackground: Color,

	val button: Color,
	val onButton: Color,
	val buttonFocused: Color,
	val onButtonFocused: Color,
	val buttonDisabled: Color,
	val onButtonDisabled: Color,
	val buttonActive: Color,
	val onButtonActive: Color,

	val input: Color,
	val onInput: Color,
	val inputFocused: Color,
	val onInputFocused: Color,

	val rangeControlBackground: Color,
	val rangeControlFill: Color,
	val rangeControlKnob: Color,
	val seekbarBuffer: Color,

	val recording: Color,
	val onRecording: Color,

	val badge: Color,
	val onBadge: Color,

	val listHeader: Color,
	val listOverline: Color,
	val listHeadline: Color,
	val listCaption: Color,
	val listButton: Color,
	val listButtonFocused: Color,

	val surface: Color,
	val scrim: Color,
)

val LocalColorScheme = staticCompositionLocalOf { colorScheme() }
