package org.jellyfin.androidtv.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.base.Badge
import org.jellyfin.androidtv.ui.base.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import timber.log.Timber

@Composable
fun HomeHeroRowContent(
	state: HomeHeroState,
	isFocused: Boolean,
	modifier: Modifier = Modifier,
	onPrevious: () -> Unit,
	onNext: () -> Unit,
) {
	when (val currentState = state) {
		HomeHeroState.Loading,
		HomeHeroState.Empty,
		is HomeHeroState.Error -> Unit

		is HomeHeroState.Ready -> {
			PreloadHeroImages(currentState)
			val item = currentState.items.getOrNull(currentState.selectedIndex) ?: return
			HomeHeroContent(
				item = item,
				isFocused = isFocused,
				modifier = modifier,
				onPrevious = onPrevious,
				onNext = onNext,
			)
		}
	}
}

@Composable
private fun HomeHeroContent(
	item: HomeHeroItem,
	isFocused: Boolean,
	modifier: Modifier = Modifier,
	onPrevious: () -> Unit,
	onNext: () -> Unit,
) {
	val backgroundService = koinInject<BackgroundService>()
	val globalBackground by backgroundService.currentBackground.collectAsState()
	val backgroundEnabled by backgroundService.enabled.collectAsState()
	val useInRowBackdrop = globalBackground == null || !backgroundEnabled

	LaunchedEffect(useInRowBackdrop, item.title) {
		Timber.i(
			"HomeHero in-row backdrop mode title=%s renderInRow=%s globalBackgroundActive=%s",
			item.title,
			useInRowBackdrop,
			globalBackground != null && backgroundEnabled,
		)
	}

	Box(
		modifier = modifier
			.fillMaxWidth()
			.height(HeroHeight)
			.focusGroup()
			.onPreviewKeyEvent { event ->
				if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
				when (event.key) {
					Key.DirectionLeft -> {
						onPrevious()
						true
					}

					Key.DirectionRight -> {
						onNext()
						true
					}

					else -> false
				}
			}
	) {
		if (useInRowBackdrop) HomeHeroBackdrop(item)
		HomeHeroScrims()
		HomeHeroText(item, isFocused)
	}
}

@Composable
private fun BoxScope.HomeHeroBackdrop(item: HomeHeroItem) {
	val imageUrl = item.backdropUrl ?: item.primaryImageUrl
	if (imageUrl != null) {
		Image(
			painter = rememberAsyncImagePainter(imageUrl),
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.matchParentSize(),
		)
	}
}

@Composable
private fun BoxScope.HomeHeroScrims() {
	Box(
		modifier = Modifier
			.matchParentSize()
			.background(
				Brush.horizontalGradient(
					0f to Color(0xFF0B0E0C),
					0.38f to Color(0xF20B0E0C),
					0.70f to Color(0x990B0E0C),
					1f to Color(0x440B0E0C),
				)
			)
	)
	Box(
		modifier = Modifier
			.matchParentSize()
			.background(
				Brush.verticalGradient(
					0f to Color(0x330B0E0C),
					0.12f to Color(0x110B0E0C),
					0.72f to Color.Transparent,
					0.94f to Color(0x440B0E0C),
					1f to Color(0x800B0E0C),
				)
			)
	)
	Box(
		modifier = Modifier
			.align(Alignment.BottomCenter)
			.fillMaxWidth()
			.height(16.dp)
			.offset(y = 4.dp)
			.background(
				Brush.verticalGradient(
					0f to Color.Transparent,
					1f to Color(0x880B0E0C),
				)
			)
	)
}

@Composable
private fun BoxScope.HomeHeroText(
	item: HomeHeroItem,
	isFocused: Boolean,
) {
	Column(
		modifier = Modifier
			.align(Alignment.CenterStart)
			.padding(start = 56.dp, end = 48.dp, top = 12.dp, bottom = 20.dp)
			.widthIn(max = 600.dp),
		verticalArrangement = Arrangement.Center,
	) {
		if (item.logoUrl != null) {
			Image(
				painter = rememberAsyncImagePainter(item.logoUrl),
				contentDescription = item.title,
				contentScale = ContentScale.Fit,
				modifier = Modifier
					.sizeIn(maxWidth = 320.dp, maxHeight = 88.dp)
			)
			Spacer(modifier = Modifier.height(14.dp))
		} else {
			Text(
				text = item.title,
				color = Color.White,
				fontSize = 38.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			Spacer(modifier = Modifier.height(10.dp))
		}

		if (item.badges.isNotEmpty()) {
			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				modifier = Modifier.fillMaxWidth(),
			) {
				item.badges.forEach { badge ->
					Badge(
						containerColor = Color(0xCC14241B),
						contentColor = Color(0xFFE8F7EC),
					) {
						Text(badge, maxLines = 1, overflow = TextOverflow.Ellipsis)
					}
				}
			}
			Spacer(modifier = Modifier.height(12.dp))
		}

		if (!item.overview.isNullOrBlank()) {
			Text(
				text = item.overview,
				color = Color(0xFFE4E4E4),
				fontSize = 15.sp,
				lineHeight = 20.sp,
				maxLines = 3,
				overflow = TextOverflow.Ellipsis,
			)
			Spacer(modifier = Modifier.height(18.dp))
		}

		DetailsPill(isFocused)
	}
}

@Composable
private fun DetailsPill(isFocused: Boolean) {
	val shape = RoundedCornerShape(22.dp)
	Box(
		modifier = Modifier
			.clip(shape)
			.background(if (isFocused) Color(0xFF007200) else Color(0xE6E8F7EC))
			.border(
				width = if (isFocused) 2.dp else 1.dp,
				color = if (isFocused) Color(0xFFE8F7EC) else Color(0x55E8F7EC),
				shape = shape,
			)
			.padding(horizontal = 20.dp, vertical = 10.dp)
	) {
		Text(
			text = "Details",
			color = if (isFocused) Color.White else Color(0xFF0B0E0C),
			fontWeight = FontWeight.Bold,
		)
	}
}

@Composable
private fun PreloadHeroImages(state: HomeHeroState.Ready) {
	val context = LocalContext.current
	val imageLoader = koinInject<ImageLoader>()

	LaunchedEffect(state.items) {
		val items = state.items
		val prioritizedIndexes = listOf(0, 1, items.lastIndex)
			.filter { it in items.indices }
			.distinct()
		val remainingIndexes = items.indices.filterNot { it in prioritizedIndexes }
		val urls = (prioritizedIndexes + remainingIndexes)
			.flatMap { index ->
				val item = items[index]
				listOfNotNull(item.backdropUrl, item.logoUrl)
			}
			.distinct()

		if (urls.isEmpty()) return@LaunchedEffect

		Timber.i("HomeHero image preload start count=%s", urls.size)
		withContext(Dispatchers.IO) {
			var completed = 0
			var failed = 0
			urls.forEach { url ->
				val result = runCatching {
					imageLoader.execute(ImageRequest.Builder(context).data(url).build())
				}

				if (result.isSuccess) completed++
				else {
					failed++
					Timber.w(result.exceptionOrNull(), "HomeHero image preload failed")
				}
			}
			Timber.i("HomeHero image preload complete success=%s failed=%s", completed, failed)
		}
	}
}

private val HeroHeight = 280.dp
