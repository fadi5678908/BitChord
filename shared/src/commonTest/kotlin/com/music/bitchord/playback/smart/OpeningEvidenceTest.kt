package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpeningEvidenceTest {
    private val opening = (0..60).map { EnergySample(it * 0.5, 1.0) }

    @Test fun provisionalHeadRetainsMeasuredEnergyAndModelVocals() {
        val base = TrackAnalysis(trackId = "incoming", duration = 200.0, bpm = 120.0)
        val window = CurveWindow(0.0, 2.0, FloatArray(61) { 0.8f })
        val result = base.withOpeningEvidence(OpeningEvidence(0.0, 30.0, opening, vocalWindow = window))
        assertEquals(opening, result.energyCurve)
        assertEquals(opening.size, result.vocalActivityMask.size)
        assertEquals(0.8, result.vocalActivityMask[10], 0.00001)
        assertEquals(200.0, result.duration)
        assertEquals(0.0, result.contentEndTime)
        assertNull(result.tailBeats)
        assertEquals(1, result.vocalWindows.size)
        assertTrue(result.energyCurve.all { it.time <= 30.0 })
    }

    @Test fun modelGapsUseMeasuredHeuristicNotAnInventedInstrumental() {
        val result = TrackAnalysis().withOpeningEvidence(OpeningEvidence(
            0.0, 30.0, opening,
            heuristicVocalMask = opening.map { 0.9 },
            vocalWindow = CurveWindow(0.0, 2.0, FloatArray(10) { 0.1f }),
        ))
        assertEquals(0.1, result.vocalActivityMask[0], 0.00001)
        assertEquals(heuristicVocalOnModelScale(0.9), result.vocalActivityMask.last())
    }

    @Test fun missingOrInvalidMaskValuesRemainUnknown() {
        val result = TrackAnalysis().withOpeningEvidence(OpeningEvidence(
            0.0, 30.0, opening, vocalWindow = CurveWindow(0.0, 2.0, floatArrayOf(Float.NaN)),
        ))
        assertTrue(result.vocalActivityMask.all { it.isNaN() })
        assertEquals(opening.size, result.vocalActivityMask.size)
    }

    @Test fun decodedOffsetAndMaskIndicesStayAligned() {
        val result = TrackAnalysis().withOpeningEvidence(OpeningEvidence(
            1.0, 2.0,
            listOf(EnergySample(1.0, 0.5), EnergySample(0.0, 1.0), EnergySample(Double.NaN, 1.0), EnergySample(5.0, 1.0)),
            heuristicVocalMask = listOf(0.9, 0.0, 0.0, 0.0),
        ))
        assertEquals(listOf(1.0, 2.0), result.energyCurve.map { it.time })
        assertEquals(listOf(0.0, heuristicVocalOnModelScale(0.9)), result.vocalActivityMask)
    }

    @Test fun badWindowDoesNotChangeTheAnalysis() {
        val base = TrackAnalysis(bpm = 120.0)
        for (seconds in listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(base, base.withOpeningEvidence(OpeningEvidence(0.0, seconds, opening)))
        }
        assertEquals(base, base.withOpeningEvidence(OpeningEvidence(-1.0, 30.0, opening)))
    }

    @Test fun retainingOpeningEvidenceUnlocksAdaptiveCandidatesBeforeTheFullDownload() {
        val out = TrackAnalysis(
            bpm = 120.0, beatInterval = 0.5, beatConfidence = 0.9,
            downbeats = (0..99).map { it * 2.0 },
            energyCurve = (0..400).map { EnergySample(it * 0.5, 1.0) },
            vocalActivityMask = List(401) { 0.0 },
        )
        val provisional = TrackAnalysis(
            bpm = 120.0, beatInterval = 0.5, beatConfidence = 0.9,
            downbeats = (0..14).map { it * 2.0 },
        )
        // The legacy seed is not an eligible blend. A new candidate needs BOTH masks.
        val seed = TransitionPlan(transitionStart = 194.0, transitionEnd = 198.0, fadeSeconds = 4.0)
        fun choose(incoming: TrackAnalysis) = AdaptiveMixEngine.select(out, incoming, seed, 198.0, 200.0, 0.0) {
            it.transitionStyle == TransitionStyle.DJ_BLEND
        }
        assertNull(choose(provisional))
        val measured = provisional.withOpeningEvidence(OpeningEvidence(
            0.0, 30.0, opening, heuristicVocalMask = opening.map { 0.0 },
        ))
        val selected = assertNotNull(choose(measured))
        assertEquals(TransitionStyle.DJ_BLEND, selected.plan.transitionStyle)
        assertTrue(selected.plan.bassSwap)
        assertFalse(selected.plan.blocked)
    }

    @Test fun streamedIncomingHeadCanReplaceAFallbackWithAnEarlyBeatmatchedEntry() {
        val outgoing = TrackAnalysis(
            duration = 200.0, contentEndTime = 198.0,
            bpm = 120.0, beatInterval = 0.5, beatConfidence = 0.9,
            downbeats = (0..99).map { it * 2.0 },
            energyCurve = (0..400).map { EnergySample(it * 0.5, 1.0) },
            vocalActivityMask = List(401) { 0.0 },
        )
        val head = TrackAnalysis(
            duration = 200.0, mixInTime = 24.0,
            bpm = 120.0, beatInterval = 0.5, beatConfidence = 0.9,
            downbeats = (0..14).map { it * 2.0 },
        )
        fun plan(incoming: TrackAnalysis) = planTransition(
            analysis = outgoing, nextAnalysis = incoming, duration = 200.0,
            mode = CrossfadeMode.SMART, advanced = true, natural = true,
        )
        val before = plan(head)
        assertEquals(TransitionStyle.EQUAL_POWER, before.transitionStyle)
        assertEquals("natural-preserve-intro", before.routingReason)
        val after = plan(head.withOpeningEvidence(OpeningEvidence(
            0.0, 30.0, opening, heuristicVocalMask = opening.map { 0.0 },
        )))
        assertEquals(TransitionStyle.DJ_BLEND, after.transitionStyle)
        assertTrue(after.incomingCueTime <= 8.0)
        assertTrue(after.policyReasons.any { it.startsWith("adaptive-search:") })
    }

    @Test fun fallbackDiagnosticNamesTheFinalRejectionNotTheSeedVerdict() {
        val plan = TransitionPlan(policyReasons = listOf("tempo-distance", "natural-unmeasured-grid"))
        assertEquals("natural-unmeasured-grid", plan.routingReason)
        assertEquals("late-buffer", plan.copy(policyReasons = plan.policyReasons + "late-buffer").routingReason)
    }
}
