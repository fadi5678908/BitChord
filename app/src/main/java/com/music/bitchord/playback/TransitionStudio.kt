package com.music.bitchord.playback

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.MixWaveform
import com.music.bitchord.playback.smart.PlaylistTransitionDraft
import com.music.bitchord.playback.smart.StemHandoff
import kotlinx.coroutines.guava.await

internal const val ACTION_STUDIO_WAVE = "com.music.bitchord.STUDIO_WAVE"
internal const val ACTION_STUDIO_PLAY = "com.music.bitchord.STUDIO_PLAY"
internal const val ACTION_STUDIO_STOP = "com.music.bitchord.STUDIO_STOP"

/** Small, bounded session messages. The service owns the decoder, transport and audio focus. */
internal suspend fun MediaController.studioWave(song: Song, from: Float, to: Float): MixWaveform.Bands? {
    val result = sendCustomCommand(SessionCommand(ACTION_STUDIO_WAVE, Bundle.EMPTY),
        bundleOf("song" to song.toSongBundle(), "from" to from, "to" to to)).await()
    if (result.resultCode != SessionResult.RESULT_SUCCESS) return null
    val b = result.extras
    return MixWaveform.Bands(b.getFloatArray("low") ?: return null,
        b.getFloatArray("mid") ?: return null, b.getFloatArray("high") ?: return null)
}

internal suspend fun MediaController.studioPlay(owner: String, out: Song, inn: Song, draft: PlaylistTransitionDraft): Boolean {
    val d = draft.sanitized()
    val args = bundleOf("owner" to owner, "out" to out.toSongBundle(), "in" to inn.toSongBundle(),
        "overlap" to d.overlapSeconds, "outStart" to d.outgoingStartSeconds,
        "inStart" to d.incomingStartSeconds, "trim" to d.outgoingTrimSeconds, "cue" to d.incomingCueSeconds,
        "until" to d.volumeHandoff.outgoingUntil, "from" to d.volumeHandoff.incomingFrom)
    return sendCustomCommand(SessionCommand(ACTION_STUDIO_PLAY, Bundle.EMPTY), args).await().resultCode == SessionResult.RESULT_SUCCESS
}

internal fun MediaController.studioStop(owner: String) {
    sendCustomCommand(SessionCommand(ACTION_STUDIO_STOP, Bundle.EMPTY), bundleOf("owner" to owner))
}

internal fun studioDraft(args: Bundle) = PlaylistTransitionDraft(
    overlapSeconds = args.getFloat("overlap", 6f), outgoingTrimSeconds = args.getFloat("trim"),
    incomingCueSeconds = args.getFloat("cue"), enabled = true,
    outgoingStartSeconds = if (args.containsKey("outStart")) args.get("outStart") as? Float else null,
    incomingStartSeconds = if (args.containsKey("inStart")) args.get("inStart") as? Float else null,
    volumeHandoff = StemHandoff(args.getFloat("until", 1f), args.getFloat("from", 0f)),
).sanitized()

/** Actual media positions, not a decorative timer. Reset when the service releases the audition. */
internal object StudioTransport {
    data class State(val owner: String, val elapsed: Float = -2f, val running: Boolean = true, val error: Boolean = false)
    val state = kotlinx.coroutines.flow.MutableStateFlow<State?>(null)
}
