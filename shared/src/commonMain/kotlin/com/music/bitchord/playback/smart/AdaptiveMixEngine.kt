package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Bounded, deterministic search over two measured arrangements. No network, PCM decoding or
 * model inference on the playback thread. Each candidate is a real bar-aligned two-deck plan;
 * the caller's safety policy gets the final veto before it is scored.
 */
internal object AdaptiveMixEngine {
    const val MAX_CANDIDATES = 96
    private const val SAMPLES = 32
    private const val MIN_COVERAGE = 0.8

    data class Decision(val plan: TransitionPlan, val evaluated: Int, val score: Double)

    private data class Metrics(
        val collision: Double,
        val energyDistance: Double,
        val vocalCoverage: Double,
        val energyCoverage: Double,
        val vocalDensity: Double,
    )

    fun select(
        outgoing: TrackAnalysis,
        incoming: TrackAnalysis,
        seed: TransitionPlan,
        contentEnd: Double,
        incomingLength: Double,
        audibleStart: Double,
        eligible: (TransitionPlan) -> Boolean,
    ): Decision? {
        if (assessTransitionTier(outgoing, incoming).tier != TransitionTier.BEATMATCHED) return null
        val rate = incomingRateBetween(outgoing.bpm, incoming.bpm)
        if (!rate.isFinite() || rate !in 0.94..1.06) return null
        val beat = 60.0 / outgoing.bpm
        val outGrid = outgoing.downbeats.filter { it.isFinite() && it >= 0.0 }.distinct().sorted()
        val allInGrid = incoming.downbeats.filter { it.isFinite() && it >= 0.0 }.distinct().sorted()
        val inGrid = allInGrid.filter {
            it >= audibleStart && it <= min(30.0, audibleStart + 8.0)
        }.take(5)
        val octave = alignTempoOctave(outgoing.bpm, incoming.bpm) / incoming.bpm
        val candidates = ArrayList<TransitionPlan>(MAX_CANDIDATES)
        candidates += seed
        // Up to six exit bars, three overlap lengths and five entry bars: at most 91 plans.
        val ends = outGrid.indices.filter { outGrid[it] <= contentEnd && outGrid[it] >= contentEnd - 6.0 }.takeLast(6)
        for (endIndex in ends) {
            for (bars in intArrayOf(1, 2, 4)) {
                val startIndex = endIndex - bars
                if (startIndex < 0) continue
                val start = outGrid[startIndex]
                val end = outGrid[endIndex]
                val span = end - start
                if (span !in 2.0..12.0) continue
                // Reject a skipped or erratic downbeat, not just a reassuring mean BPM.
                if ((startIndex until endIndex).any { abs((outGrid[it + 1] - outGrid[it]) / (4 * beat) - 1) > 0.05 }) continue
                for (cue in inGrid) {
                    val cueIndex = allInGrid.binarySearch(cue)
                    val following = allInGrid.getOrNull(cueIndex + 1) ?: continue
                    val inBar = following - cue
                    // Render the locally observed bar ratio, not a rounded whole-song BPM.
                    val localRate = inBar / (span / bars * octave)
                    if (!localRate.isFinite() || localRate !in 0.94..1.06) continue
                    val inEnd = cue + span * localRate
                    val localBars = allInGrid.zipWithNext().filter { (a, b) -> a >= cue && b <= inEnd + inBar }
                    if (localBars.any { (a, b) -> abs((b - a) / inBar - 1.0) > 0.025 }) continue
                    if (incomingLength > 0.0 && inEnd > incomingLength - 5.0) continue
                    if (candidates.size >= MAX_CANDIDATES) break
                    candidates += TransitionPlan(
                        markerVisible = true,
                        transitionStart = start,
                        transitionEnd = end,
                        fadeSeconds = span,
                        transitionStyle = TransitionStyle.DJ_BLEND,
                        incomingCueTime = cue,
                        incomingHandoffTime = inEnd,
                        incomingPlaybackRate = localRate,
                        handoffDuration = span,
                        transitionBeats = bars * 4,
                        bassSwap = true,
                        bassSwapFraction = 0.5,
                        outgoingBpm = 240.0 * bars / span,
                        incomingBpm = 240.0 / inBar * octave,
                        beatSeconds = span / (bars * 4),
                        reason = "smart-adaptive-blend",
                    )
                }
            }
        }

        val outReference = reference(outgoing)
        val inReference = reference(incoming)
        var best: TransitionPlan? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var evaluated = 0
        for (candidate in candidates) {
            val metrics = measure(outgoing, incoming, candidate, outReference, inReference)
            evaluated++
            // New routes need real vocal coverage. A seed may retain the legacy policy's
            // conservative missing-mask handling; do not invent a new "instrumental" route.
            if (candidate !== seed && metrics.vocalCoverage < MIN_COVERAGE) continue
            val measured = candidate.copy(vocalOverlap = max(candidate.vocalOverlap, metrics.collision))
            if (!eligible(measured)) continue
            val targetSeconds = if (metrics.vocalDensity > 0.25) 4.0 else 8.0
            val score = 1.0 -
                3.0 * metrics.collision -
                0.3 * metrics.energyDistance -
                0.16 * abs(candidate.fadeSeconds - targetSeconds) / 8.0 -
                0.025 * (contentEnd - candidate.transitionEnd).coerceAtLeast(0.0) -
                0.025 * (candidate.incomingCueTime - audibleStart).coerceAtLeast(0.0) -
                0.15 * (1.0 - metrics.vocalCoverage) -
                0.1 * (1.0 - metrics.energyCoverage) +
                phraseBonus(outgoing, candidate.transitionStart) +
                phraseBonus(incoming, candidate.incomingCueTime) +
                if (candidate === seed) 0.025 else 0.0
            // Strict comparison and stable candidate order make ties deterministic.
            if (score > bestScore + 1e-9) {
                best = measured
                bestScore = score
            }
        }
        return best?.let { Decision(it, evaluated, bestScore) }
    }

    /** Compare only shorter fallbacks; no extra intro skipping or early exit is permitted. */
    fun refineFallback(outgoing: TrackAnalysis, incoming: TrackAnalysis, fallback: TransitionPlan): TransitionPlan {
        val outReference = reference(outgoing)
        val inReference = reference(incoming)
        val baseline = measure(outgoing, incoming, fallback, outReference, inReference)
        if (baseline.vocalCoverage < MIN_COVERAGE || baseline.collision <= 0.05) return fallback
        var best = fallback
        var score = 3 * baseline.collision
        for (seconds in listOf(2.5, 3.5)) {
            if (seconds >= fallback.fadeSeconds) continue
            val candidate = fallback.copy(
                transitionStart = fallback.transitionEnd - seconds,
                fadeSeconds = seconds,
                incomingHandoffTime = fallback.incomingCueTime + seconds,
            )
            val metrics = measure(outgoing, incoming, candidate, outReference, inReference)
            if (metrics.vocalCoverage < MIN_COVERAGE) continue
            val cost = 3 * metrics.collision + 0.12 * (fallback.fadeSeconds - seconds)
            if (cost < score - 1e-9) {
                best = candidate.copy(
                    vocalOverlap = metrics.collision,
                    policyReasons = fallback.policyReasons + "adaptive-short-fade",
                )
                score = cost
            }
        }
        return best
    }

    private fun phraseBonus(analysis: TrackAnalysis, time: Double): Double =
        if (analysis.phraseBoundaries.any { it.isFinite() && abs(it - time) <= 0.12 }) 0.04 else 0.0

    private fun reference(analysis: TrackAnalysis): Double {
        val values = analysis.energyCurve.map { it.energy }.filter { it.isFinite() && it > 0 }.sorted()
        return values.getOrNull((values.size * 0.85).toInt()) ?: 1.0
    }

    /** The nearest sample, with bounded coverage: never extend a head-only mask over an outro. */
    private fun sample(analysis: TrackAnalysis, time: Double): Int? {
        val curve = analysis.energyCurve
        if (curve.isEmpty() || !time.isFinite()) return null
        var low = 0
        var high = curve.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (curve[mid].time < time) low = mid + 1 else high = mid
        }
        val index = if (low > 0 && abs(curve[low - 1].time - time) < abs(curve[low].time - time)) low - 1 else low
        // Whole-track DSP curves are often ~1 Hz; measured model windows are finer.
        return index.takeIf { curve[it].time.isFinite() && abs(curve[it].time - time) <= 1.0 }
    }

    private fun vocal(analysis: TrackAnalysis, index: Int?): Double? {
        if (index == null || analysis.vocalActivityMask.size != analysis.energyCurve.size) return null
        return analysis.vocalActivityMask.getOrNull(index)?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
    }

    private fun energy(analysis: TrackAnalysis, index: Int?, reference: Double): Double? =
        index?.let { analysis.energyCurve[it].energy.takeIf { value -> value.isFinite() && value >= 0 } }
            ?.let { (it / reference).coerceIn(0.0, 2.0) }

    private fun measure(
        outgoing: TrackAnalysis,
        incoming: TrackAnalysis,
        plan: TransitionPlan,
        outReference: Double,
        inReference: Double,
    ): Metrics {
        var collision = 0
        var vocals = 0
        var levels = 0
        var energyDistance = 0.0
        var density = 0.0
        for (n in 0 until SAMPLES) {
            val elapsed = plan.fadeSeconds * (n + 0.5) / SAMPLES
            val oi = sample(outgoing, plan.transitionStart + elapsed)
            val ii = sample(incoming, plan.incomingCueTime + elapsed * plan.incomingPlaybackRate)
            val ov = vocal(outgoing, oi)
            val iv = vocal(incoming, ii)
            if (ov != null && iv != null) {
                vocals++
                if (isVocalClash(ov, iv)) collision++
                density += max(ov, iv)
            }
            val oe = energy(outgoing, oi, outReference)
            val ie = energy(incoming, ii, inReference)
            if (oe != null && ie != null) {
                levels++
                // Arrangement energy, not a guessed dB gain; loudness normalization stays
                // with the existing PCM loudness processor and its headroom protection.
                energyDistance += min(1.0, abs(ln((oe + 0.05) / (ie + 0.05))) / ln(4.0))
            }
        }
        return Metrics(
            collision = if (vocals > 0) collision.toDouble() / vocals else 0.0,
            energyDistance = if (levels > 0) energyDistance / levels else 0.0,
            vocalCoverage = vocals.toDouble() / SAMPLES,
            energyCoverage = levels.toDouble() / SAMPLES,
            vocalDensity = if (vocals > 0) density / vocals else 0.0,
        )
    }
}
