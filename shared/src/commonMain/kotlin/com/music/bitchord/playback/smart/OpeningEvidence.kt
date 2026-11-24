package com.music.bitchord.playback.smart

/**
 * Evidence from an actually decoded opening. A provisional incoming track needs more than BPM:
 * the adaptive search also reads its energy and vocal mask before the full file is available.
 * Coordinates in [energy] and [lowEnergy] are relative to the decoded window; model windows
 * already have source coordinates. Never fill the unmeasured rest of the song.
 */
data class OpeningEvidence(
    val start: Double,
    val seconds: Double,
    val energy: List<EnergySample>,
    val lowEnergy: List<EnergySample> = emptyList(),
    val heuristicVocalMask: List<Double> = emptyList(),
    val vocalProbability: Double = 0.0,
    val vocalWindow: CurveWindow? = null,
    val levelWindow: CurveWindow? = null,
)

fun TrackAnalysis.withOpeningEvidence(evidence: OpeningEvidence): TrackAnalysis {
    val start = evidence.start
    val end = start + evidence.seconds
    if (!start.isFinite() || start < 0.0 || !end.isFinite() || end <= start) return this
    val indices = evidence.energy.indices.filter { index ->
        val sample = evidence.energy[index]
        sample.time.isFinite() && sample.time >= 0.0 && sample.time <= evidence.seconds && sample.energy.isFinite()
    }.sortedBy { evidence.energy[it].time }
    val energy = indices.map { index -> evidence.energy[index].let { EnergySample(it.time + start, it.energy) } }
    if (energy.isEmpty()) return this
    fun validWindow(window: CurveWindow?): CurveWindow? {
        if (window == null || !window.startTime.isFinite() || window.startTime < start || window.startTime >= end ||
            !window.frameRate.isFinite() || window.frameRate <= 0.0 || window.values.isEmpty()) return null
        val count = kotlin.math.ceil((end - window.startTime) * window.frameRate).toInt().coerceIn(0, window.values.size)
        if (count == 0) return null
        return if (count == window.values.size) window else
            CurveWindow(window.startTime, window.frameRate, window.values.copyOf(count))
    }
    val vocals = validWindow(evidence.vocalWindow)
    val levels = validWindow(evidence.levelWindow)
    val heuristic = evidence.heuristicVocalMask.takeIf { it.size == evidence.energy.size }
    val mask = indices.mapIndexed { position, original ->
        val time = energy[position].time
        val frame = vocals?.let { kotlin.math.floor((time - it.startTime) * it.frameRate).toInt() }
        val measured = if (frame != null && frame >= 0) vocals?.values?.getOrNull(frame)?.toDouble() else null
        measured?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
            ?: heuristic?.get(original)?.takeIf { it.isFinite() }?.let(::heuristicVocalOnModelScale)
            ?: Double.NaN // Unknown is not an instrumental sample.
    }
    return copy(
        energyCurve = energy,
        lowEnergyCurve = evidence.lowEnergy.filter {
            it.time.isFinite() && it.time >= 0.0 && it.time <= evidence.seconds && it.energy.isFinite()
        }.map { EnergySample(it.time + start, it.energy) }.sortedBy { it.time },
        vocalActivityMask = mask,
        vocalProbability = evidence.vocalProbability.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
        vocalWindows = listOfNotNull(vocals),
        levelWindows = listOfNotNull(levels),
    )
}

/** Prefer the actual fallback verdict over an earlier, generic seed-planner reason. */
val TransitionPlan.routingReason: String
    get() = policyReasons.lastOrNull { it.startsWith("natural-") || it == "late-buffer" }
        ?: policyReasons.firstOrNull() ?: reason
