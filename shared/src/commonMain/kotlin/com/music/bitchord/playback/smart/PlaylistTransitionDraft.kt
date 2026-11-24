package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Editable designs only. These must not change playback until real separated audio is available. */
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
) {
    fun handoff(stem: TransitionStem): StemHandoff = (stems[stem] ?: defaultHandoff(stem)).sanitized()

    fun sanitized() = copy(
        overlapSeconds = overlapSeconds.safe(6f).coerceIn(2f, 12f),
        outgoingTrimSeconds = outgoingTrimSeconds.safe(0f).coerceIn(0f, 6f),
        incomingCueSeconds = incomingCueSeconds.safe(0f).coerceIn(0f, 30f),
        stems = TransitionStem.entries.associateWith { handoff(it) },
    )

    fun withHandoff(stem: TransitionStem, handoff: StemHandoff) =
        copy(stems = stems + (stem to handoff.sanitized()))

    /** No source window is legal until actual decoded durations are known. */
    fun windows(outgoingDuration: Double, incomingDuration: Double): TransitionSourceWindows? {
        if (!outgoingDuration.isFinite() || !incomingDuration.isFinite()) return null
        val d = sanitized()
        val outEnd = outgoingDuration - d.outgoingTrimSeconds
        val outStart = outEnd - d.overlapSeconds
        val inStart = d.incomingCueSeconds.toDouble()
        val inEnd = inStart + d.overlapSeconds
        if (outStart < 0.0 || inEnd > incomingDuration || outgoingDuration <= 0.0 || incomingDuration <= 0.0) return null
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
