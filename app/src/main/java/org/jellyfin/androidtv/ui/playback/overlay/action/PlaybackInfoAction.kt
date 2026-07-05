package org.jellyfin.androidtv.ui.playback.overlay.action

import android.app.AlertDialog
import android.content.Context
import android.view.View
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.PlaybackDiagnostics
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter

class PlaybackInfoAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue) {
	private var dialog: AlertDialog? = null

	init {
		initializeWithIcon(R.drawable.ic_info)
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		videoPlayerAdapter.leanbackOverlayFragment.setFading(false)
		dismissDialog()

		val report = PlaybackDiagnostics.build(playbackController)
		PlaybackDiagnostics.log(report)

		dialog = AlertDialog.Builder(context)
			.setTitle(R.string.playback_info)
			.setMessage(report.displayText)
			.setPositiveButton(android.R.string.ok, null)
			.create()
			.apply {
				setOnDismissListener {
					videoPlayerAdapter.leanbackOverlayFragment.setFading(true)
					dialog = null
				}
				show()
			}
	}

	fun dismissDialog() {
		dialog?.dismiss()
	}
}
