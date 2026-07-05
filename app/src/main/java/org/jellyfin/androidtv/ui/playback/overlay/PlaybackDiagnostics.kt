package org.jellyfin.androidtv.ui.playback.overlay

import android.net.Uri
import org.jellyfin.androidtv.data.compat.StreamInfo
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.util.apiclient.StreamHelper
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import timber.log.Timber
import java.util.Locale

object PlaybackDiagnostics {
	private const val Marker = "PAKFLIX_PLAYBACK_DIAGNOSTICS"
	private const val Unknown = "Unknown"
	private const val NotAvailable = "Not available"
	private const val None = "None"

	data class Report(
		val displayText: String,
		val logText: String,
	)

	fun build(controller: PlaybackController): Report {
		val streamInfo = controller.currentStreamInfo
		val mediaSource = controller.currentMediaSource
		val audioIndex = controller.audioStreamIndex
		val subtitleIndex = controller.subtitleStreamIndex
		val videoStream = mediaSource?.mediaStreams.orEmpty().firstOrNull { it.type == MediaStreamType.VIDEO }
		val audioStream = findStream(mediaSource, MediaStreamType.AUDIO, audioIndex)
			?: StreamHelper.getAudioStreams(mediaSource).firstOrNull()
		val subtitleStream = findStream(mediaSource, MediaStreamType.SUBTITLE, subtitleIndex)
		val method = methodLabel(streamInfo?.playMethod)
		val mediaUrlPresent = !streamInfo?.mediaUrl.isNullOrBlank()
		val routeType = routeType(streamInfo)
		val transcodeReason = safeQueryParameter(streamInfo?.mediaUrl, "TranscodeReasons")
		val subtitleMethod = safeQueryParameter(streamInfo?.mediaUrl, "SubtitleMethod")

		val sections = listOf(
			section(
				"Playback",
				"Method" to method,
				"Position" to "${formatDuration(controller.currentPosition)} / ${formatDuration(controller.duration)}",
				"Speed" to String.format(Locale.US, "%.2fx", controller.playbackSpeed),
				"Aspect" to controller.zoomMode.name,
				"Session ID" to valueOrUnknown(streamInfo?.playSessionId),
			),
			section(
				"Video",
				"Container" to valueOrUnknown(streamInfo?.container ?: mediaSource?.container),
				"Codec" to valueOrUnknown(videoStream?.codec),
				"Resolution" to resolution(videoStream),
				"Bitrate" to bitrate(videoStream?.bitRate),
				"Dynamic range" to valueOrUnavailable(videoStream?.videoRangeType?.name),
				"Stream index" to valueOrUnavailable(videoStream?.index?.toString()),
			),
			section(
				"Audio",
				"Selected stream index" to indexOrNone(audioIndex),
				"Title / language" to streamName(audioStream),
				"Codec" to valueOrUnknown(audioStream?.codec),
				"Channels" to valueOrUnavailable(audioStream?.channels?.toString()),
				"Bitrate" to bitrate(audioStream?.bitRate),
			),
			section(
				"Subtitles",
				"Selected stream index" to indexOrNone(subtitleIndex),
				"Title / language" to if (subtitleIndex < 0) None else streamName(subtitleStream),
				"Codec" to if (subtitleIndex < 0) None else valueOrUnknown(subtitleStream?.codec),
				"Type" to if (subtitleIndex < 0) None else valueOrUnknown(subtitleStream?.type?.name),
				"Delivery method" to if (subtitleIndex < 0) None else valueOrUnknown(subtitleStream?.deliveryMethod?.name),
			),
			section(
				"Diagnostics",
				"Media URL present" to if (mediaUrlPresent) "Yes" else "No",
				"Route type" to routeType,
				"Transcode reason" to valueOrUnavailable(formatReasonList(transcodeReason)),
				"Subtitle method" to valueOrUnavailable(subtitleMethod),
				"Full diagnostics written to logcat" to "Yes",
			),
		)

		val displayText = sections.joinToString("\n\n")
		val logText = sections.joinToString("\n\n")
		return Report(displayText, logText)
	}

	fun log(report: Report) {
		Timber.i("%s\n%s", Marker, report.logText)
	}

	private fun section(title: String, vararg rows: Pair<String, String>): String =
		buildString {
			appendLine(title)
			rows.forEach { (label, value) ->
				appendLine("$label: $value")
			}
		}.trimEnd()

	private fun findStream(mediaSource: MediaSourceInfo?, type: MediaStreamType, index: Int): MediaStream? =
		mediaSource?.mediaStreams.orEmpty().firstOrNull { it.type == type && it.index == index }

	private fun methodLabel(method: PlayMethod?): String = when (method) {
		PlayMethod.DIRECT_PLAY -> "Direct Play"
		PlayMethod.DIRECT_STREAM -> "Direct Stream"
		PlayMethod.TRANSCODE -> "Transcode"
		null -> Unknown
	}

	private fun routeType(streamInfo: StreamInfo?): String = when (streamInfo?.playMethod) {
		PlayMethod.DIRECT_PLAY -> "direct"
		PlayMethod.DIRECT_STREAM -> "direct-stream"
		PlayMethod.TRANSCODE -> "transcode"
		null -> "unknown"
	}

	private fun formatDuration(milliseconds: Long): String {
		if (milliseconds <= 0) return Unknown
		val totalSeconds = milliseconds / 1000
		val hours = totalSeconds / 3600
		val minutes = (totalSeconds % 3600) / 60
		val seconds = totalSeconds % 60
		return if (hours > 0) {
			String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
		} else {
			String.format(Locale.US, "%d:%02d", minutes, seconds)
		}
	}

	private fun resolution(stream: MediaStream?): String {
		val width = stream?.width
		val height = stream?.height
		return if (width != null && height != null && width > 0 && height > 0) "${width}x$height" else Unknown
	}

	private fun bitrate(bitRate: Int?): String = when {
		bitRate == null || bitRate <= 0 -> Unknown
		bitRate >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bitRate / 1_000_000f)
		else -> String.format(Locale.US, "%.0f kbps", bitRate / 1_000f)
	}

	private fun streamName(stream: MediaStream?): String {
		if (stream == null) return Unknown
		return listOfNotNull(stream.displayTitle, stream.title, stream.language)
			.firstOrNull { it.isNotBlank() }
			?: Unknown
	}

	private fun safeQueryParameter(mediaUrl: String?, parameterName: String): String? {
		if (mediaUrl.isNullOrBlank()) return null
		return runCatching { Uri.parse(mediaUrl).getQueryParameter(parameterName) }.getOrNull()
	}

	private fun formatReasonList(value: String?): String? =
		value
			?.split(',')
			?.map { it.trim() }
			?.filter { it.isNotEmpty() }
			?.joinToString(", ")
			?.takeIf { it.isNotBlank() }

	private fun indexOrNone(index: Int): String = if (index < 0) None else index.toString()

	private fun valueOrUnknown(value: String?): String = value?.takeIf { it.isNotBlank() } ?: Unknown

	private fun valueOrUnavailable(value: String?): String = value?.takeIf { it.isNotBlank() } ?: NotAvailable
}
