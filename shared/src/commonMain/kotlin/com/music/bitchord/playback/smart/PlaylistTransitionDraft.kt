package com.music.bitchord.playback.smart

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Playlist policy is independent of the on-device vocal separator. */
enum class PlaylistTransitionMode { AUTOMATIC, CUSTOM, CROSSFADE }

enum class TransitionStem { VOCALS, DRUMS, BASS, OTHER }

/** Fractions of the overlap: finish fading out by [outgoingUntil], start fading in at [incomingFrom]. */
data class StemHandoff(val outgoingUntil: Float = 1f, val incomingFrom: Float = 0f) {
    fun sanitized() = StemHandoff(outgoingUntil.safe(1f).coerceIn(0.1f, 1f), incomingFrom.safe(0f).coerceIn(0f, 0.9f))

    /** Design curves, not measured waveforms or separated audio. */
    fun gains(progress: Float): Pair<Float, Float> {
        val p = progress.safe(0f).coerceIn(0f, 1f)
        val s = sanitized()
        val outPhase = (p / s.outgoingUntil).coerceIn(0f, 1f)
        val inPhase = ((p - s.incomingFrom) / (1f - s.incomingFrom)).coerceIn(0f, 1f)
        return cos(outPhase * PI / 2).toFloat().coerceIn(0f, 1f) to
            sin(inPhase * PI / 2).toFloat().coerceIn(0f, 1f)
    }
}

/** Ordered playlist-entry identities; never key by title, row index, or a global song preference. */
data class PlaylistTransitionKey(val playlistId: String, val outgoingEntry: String, val incomingEntry: String) {
    val isValid: Boolean get() = playlistId.isNotBlank() && outgoingEntry.isNotBlank() && incomingEntry.isNotBlank()
}

data class PlaylistTransitionDraft(
    val overlapSeconds: Float = 6f,
    val outgoingTrimSeconds: Float = 0f,
    val incomingCueSeconds: Float = 0f,
    val stems: Map<TransitionStem, StemHandoff> = emptyMap(),
    val outgoingStartSeconds: Float? = null,
    val incomingStartSeconds: Float? = null,
    val enabled: Boolean = false,
    /** Master-volume envelope; independent of the reserved, unseparated stem designs. */
    val volumeHandoff: StemHandoff = StemHandoff(),
    val mode: PlaylistTransitionMode = PlaylistTransitionMode.CUSTOM,
    val separateVocals: Boolean = false,
) {
    fun handoff(stem: TransitionStem): StemHandoff = (stems[stem] ?: defaultHandoff(stem)).sanitized()

    fun sanitized() = copy(
        volumeHandoff = volumeHandoff.sanitized(),
        separateVocals = separateVocals && mode != PlaylistTransitionMode.CROSSFADE,
        overlapSeconds = overlapSeconds.safe(6f).coerceIn(2f, 12f),
        outgoingTrimSeconds = outgoingTrimSeconds.safe(0f).coerceIn(0f, 6f),
        incomingCueSeconds = incomingCueSeconds.safe(0f).coerceIn(0f, 30f),
        stems = TransitionStem.entries.associateWith { handoff(it) },
        outgoingStartSeconds = outgoingStartSeconds?.takeIf { it.isFinite() }?.coerceIn(0f, 86_400f),
        incomingStartSeconds = incomingStartSeconds?.takeIf { it.isFinite() }?.coerceIn(0f, 86_400f),
    )

    fun withHandoff(stem: TransitionStem, handoff: StemHandoff) =
        copy(stems = stems + (stem to handoff.sanitized()))

    /** No source window is legal until actual decoded durations are known. */
    fun windows(outgoingDuration: Double, incomingDuration: Double): TransitionSourceWindows? {
        if (!outgoingDuration.isFinite() || !incomingDuration.isFinite()) return null
        val d = sanitized()
        val outStart = d.outgoingStartSeconds?.toDouble() ?: (outgoingDuration - d.outgoingTrimSeconds - d.overlapSeconds)
        val outEnd = outStart + d.overlapSeconds
        val inStart = d.incomingStartSeconds?.toDouble() ?: d.incomingCueSeconds.toDouble()
        val inEnd = inStart + d.overlapSeconds
        if (outStart < 0.0 || outEnd > outgoingDuration + 0.001 || inEnd > incomingDuration + 0.001 || outgoingDuration <= 0.0 || incomingDuration <= 0.0) return null
        return TransitionSourceWindows(outStart, outEnd, inStart, inEnd)
    }

    companion object {
        fun defaultHandoff(stem: TransitionStem) = when (stem) {
            TransitionStem.VOCALS -> StemHandoff(outgoingUntil = 0.45f, incomingFrom = 0.55f)
            TransitionStem.BASS -> StemHandoff(outgoingUntil = 0.55f, incomingFrom = 0.45f)
            else -> StemHandoff()
        }
    }
}

data class TransitionSourceWindows(val outStart: Double, val outEnd: Double, val inStart: Double, val inEnd: Double)

/** Fail closed. Future Android preparation must satisfy ALL gates; no network/server escape hatch. */
enum class LocalPreparationBlock {
    NOT_A_PLAYLIST, MODEL_UNAVAILABLE, SOURCE_NOT_LOCAL, INVALID_WINDOW,
    NOT_CHARGING, DEVICE_BUSY, BATTERY_LOW, THERMAL_PRESSURE, STORAGE_LOW,
}

data class LocalPreparationConditions(
    val isPlaylist: Boolean,
    val modelAvailable: Boolean,
    val sourcesAreLocal: Boolean,
    val windowsValid: Boolean,
    val charging: Boolean,
    val idle: Boolean,
    val batteryPercent: Int,
    val thermalSafe: Boolean,
    val freeBytes: Long,
    val requiredBytes: Long,
) {
    fun blockers(): Set<LocalPreparationBlock> = buildSet {
        if (!isPlaylist) add(LocalPreparationBlock.NOT_A_PLAYLIST)
        if (!modelAvailable) add(LocalPreparationBlock.MODEL_UNAVAILABLE)
        if (!sourcesAreLocal) add(LocalPreparationBlock.SOURCE_NOT_LOCAL)
        if (!windowsValid) add(LocalPreparationBlock.INVALID_WINDOW)
        if (!charging) add(LocalPreparationBlock.NOT_CHARGING)
        if (!idle) add(LocalPreparationBlock.DEVICE_BUSY)
        if (batteryPercent !in 30..100) add(LocalPreparationBlock.BATTERY_LOW)
        if (!thermalSafe) add(LocalPreparationBlock.THERMAL_PRESSURE)
        if (requiredBytes <= 0 || freeBytes < requiredBytes) add(LocalPreparationBlock.STORAGE_LOW)
    }
}

private fun Float.safe(fallback: Float): Float = if (isFinite()) this else fallback


/** An explicit playlist timing override. Never claims beatmatching or implements stem controls. */
fun PlaylistTransitionDraft.playbackPlan(outgoingDuration: Double, incomingDuration: Double, time: Double,
    outgoing: TrackAnalysis = TrackAnalysis(), incoming: TrackAnalysis = TrackAnalysis()): TransitionPlan? {
    if (!enabled) return null
    if (mode != PlaylistTransitionMode.CUSTOM) {
        return resolvedTiming(outgoingDuration, incomingDuration, outgoing, incoming)
            .playbackPlan(outgoingDuration, incomingDuration, time)?.let {
                it.copy(reason = "playlist-" + mode.name.lowercase(), policyReasons = it.policyReasons + ("playlist-mode:" + mode.name))
            }
    }
    val window = windows(outgoingDuration, incomingDuration) ?: return null
    val base = TransitionPlan(
        markerVisible = true,
        transitionStart = window.outStart, transitionEnd = window.outEnd,
        fadeSeconds = window.outEnd - window.outStart,
        incomingCueTime = window.inStart, incomingHandoffTime = window.inEnd,
        handoffDuration = window.outEnd - window.outStart,
        transitionStyle = TransitionStyle.EQUAL_POWER,
        volumeHandoff = sanitized().volumeHandoff,
        policyReasons = listOf("playlist-custom-timing"), reason = "playlist-custom-transition",
    )
    val now = time.orZero().coerceAtLeast(0.0)
    val adjusted = if (now > base.transitionStart + 0.15) base.forPlaybackStart(now) else base
    return adjusted.copy(shouldStart = !adjusted.blocked && now >= adjusted.transitionStart)
}

/** Matches the original editor's unique-song fallback keys, without conflating stable playlist entries. */
fun playlistTransitionEntry(trackId: String, entryId: String?): String =
    entryId?.takeIf { it.isNotBlank() }?.let { "entry:$it" } ?: "song:${trackId.length}:$trackId:0"


/** Automatic fades reserve most of the incoming song; an explicit user window uses its full selected span. */
fun TransitionPlan.incomingFadeLimitMs(remainingMs: Long?): Long {
    val remaining = remainingMs?.coerceAtLeast(0L) ?: return Long.MAX_VALUE
    return if ("playlist-custom-timing" in policyReasons) remaining else remaining / 3
}

/** Queue metadata also covers local/downloaded URIs, which have no streaming d= parameter. */
fun transitionQueuedDurationMs(metadataMs: Long, uriSeconds: Long?): Long = when {
    metadataMs > 0L -> metadataMs
    uriSeconds != null && uriSeconds in 1..(Long.MAX_VALUE / 1000) -> uriSeconds * 1000L
    else -> 0L
}

/** Small playlist-only policy. No loops, tempo/pitch changes, or hidden dependence on global toggles. */
fun PlaylistTransitionDraft.resolvedTiming(outDuration: Double, inDuration: Double,
    outgoing: TrackAnalysis = TrackAnalysis(), incoming: TrackAnalysis = TrackAnalysis()): PlaylistTransitionDraft {
    val d = sanitized()
    if (d.mode == PlaylistTransitionMode.CUSTOM) return d
    val outLength = outDuration.takeIf { it.isFinite() && it > 0 } ?: 0.0
    val inLength = inDuration.takeIf { it.isFinite() && it > 0 } ?: 0.0
    var fade = d.overlapSeconds.toDouble()
    var contentEnd = outLength
    var cue = 0.0
    if (d.mode == PlaylistTransitionMode.AUTOMATIC) {
        fade = if (outgoing.bpm.isFinite() && outgoing.bpm in 70.0..180.0 && outgoing.beatConfidence >= 0.55)
            (8 * 60.0 / outgoing.bpm).coerceIn(4.0, 8.0) else 6.0
        if (outgoing.contentEndTime.isFinite() && outgoing.contentEndTime > 0 && outgoing.contentEndTime in (outLength - 3).coerceAtLeast(0.0)..outLength)
            contentEnd = outgoing.contentEndTime
        cue = incoming.audibleStartTime?.takeIf { it.isFinite() && it in 0.0..8.0 } ?: 0.0
        cue = cue.coerceAtMost((inLength - 2.0).coerceAtLeast(0.0))
        // Only an actual vocal-model window may advance the cue to a vocal onset.
        if (d.separateVocals) {
            val onset = incoming.vocalWindows.asSequence().filter { it.frameRate.isFinite() && it.frameRate > 0 }
                .flatMap { w -> w.values.indices.asSequence().filter { w.values[it].isFinite() && w.values[it] >= 0.65f }
                    .map { w.startTime + it / w.frameRate } }
                .filter { it.isFinite() && it in cue..minOf(30.0, inLength - 2.0) }.minOrNull()
            if (onset != null) cue = onset
        }
    }
    fade = minOf(fade, contentEnd, (inLength - cue).coerceAtLeast(0.0))
    // Invalid/short sources stay invalid: do not invent a legal two-second window.
    if (fade < 2.0) return d.copy(mode = PlaylistTransitionMode.CUSTOM, outgoingStartSeconds = outLength.toFloat(), incomingStartSeconds = inLength.toFloat())
    return d.copy(mode = PlaylistTransitionMode.CUSTOM, overlapSeconds = fade.toFloat(),
        outgoingStartSeconds = (contentEnd - fade).toFloat(), incomingStartSeconds = cue.toFloat(),
        volumeHandoff = StemHandoff(), separateVocals = d.separateVocals)
}

/** BROWSE also means albums/folders. Never infer playlist identity from an ID prefix. */
fun playlistTransitionKeyFor(out: Song, incoming: Song, shuffled: Boolean): PlaylistTransitionKey? {
    val source = out.playbackSourceId?.takeIf { it.isNotBlank() } ?: return null
    if (shuffled || out.playbackSourceType != PlaybackSourceType.PLAYLIST ||
        incoming.playbackSourceType != PlaybackSourceType.PLAYLIST || incoming.playbackSourceId != source ||
        out.queueTier != QueueTier.CONTEXT || incoming.queueTier != QueueTier.CONTEXT ||
        out.radioName != null || incoming.radioName != null || out.isVideo || incoming.isVideo ||
        out.isVideoOrigin || incoming.isVideoOrigin) return null
    return PlaylistTransitionKey(source, playlistTransitionEntry(out.videoId, out.setVideoId),
        playlistTransitionEntry(incoming.videoId, incoming.setVideoId))
}

/** Explicit pair settings beat global defaults; disabling a pair must not fall through to Automix. */
fun playlistTransitionProfile(stored: PlaylistTransitionDraft?, automatic: Boolean, crossfadeSeconds: Int): PlaylistTransitionDraft? =
    if (stored != null) stored.takeIf { it.enabled } else when {
        automatic -> PlaylistTransitionDraft(enabled = true, mode = PlaylistTransitionMode.AUTOMATIC)
        crossfadeSeconds >= 2 -> PlaylistTransitionDraft(enabled = true, mode = PlaylistTransitionMode.CROSSFADE,
            overlapSeconds = crossfadeSeconds.toFloat()).sanitized()
        else -> null
    }
