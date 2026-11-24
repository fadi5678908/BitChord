package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression coverage for the listening-first policy enabled by the Android controller. */
class NaturalAutomixTest {
    private fun track(id: String, bpm: Double = 120.0, vocal: Double? = 0.0): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < 199.0 }.toList()
        val energy = (0 until 400).map { EnergySample(it / 2.0, 1.0) }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = 200.0,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = downbeats,
            firstBeat = 0.5,
            key = "C major",
            keyConfidence = 0.9,
            audibleStartTime = 0.5,
            contentEndTime = 198.0,
            mixInTime = downbeats[4],
            energyCurve = energy,
            vocalActivityMask = vocal?.let { value -> energy.map { value } } ?: emptyList(),
            vocalProbability = vocal ?: 0.9,
        )
    }

    private fun plan(
        out: TrackAnalysis = track("a"),
        inn: TrackAnalysis = track("b"),
        natural: Boolean = true,
        time: Double = 10.0,
        mode: CrossfadeMode = CrossfadeMode.SMART,
    ) = planTransition(
        analysis = out,
        nextAnalysis = inn,
        currentTrack = TransitionTrackInfo("a", 200_000),
        nextTrack = TransitionTrackInfo("b", 200_000),
        currentTime = time,
        duration = 200.0,
        fadeSeconds = 8.0,
        mode = mode,
        advanced = true,
        natural = natural,
    )

    @Test
    fun aCompatibleInstrumentalBlendRetainsItsBeatAlignment() {
        val result = plan()
        assertEquals(TransitionStyle.DJ_BLEND, result.transitionStyle)
        assertTrue(result.policyReasons.any { it.startsWith("adaptive-search:") })
        assertEquals(1.0, result.incomingPlaybackRate)
        assertEquals(0.0, result.filterSweep)
        assertTrue(result.fadeSeconds in 2.0..12.0)
        assertTrue(result.transitionStart >= 170.0)
    }

    @Test
    fun modestStretchingIsStillAllowedOnReliableGrids() {
        val result = plan(inn = track("b", bpm = 116.0))
        assertEquals(TransitionStyle.DJ_BLEND, result.transitionStyle)
        assertEquals(120.0 / 116.0, result.incomingPlaybackRate, 0.001)
        assertEquals(1.0, result.outgoingPlaybackRate)
    }

    @Test
    fun largeTempoChangesUseANaturalSpeedFadeInstead() {
        val result = plan(inn = track("b", bpm = 110.0))
        assertEquals(TransitionStyle.EQUAL_POWER, result.transitionStyle)
        assertEquals(1.0, result.incomingPlaybackRate)
        assertEquals(1.0, result.outgoingPlaybackRate)
        assertTrue("natural-tempo-change" in result.policyReasons)
        assertEquals(198.0, result.transitionEnd)
        assertEquals(0.5, result.incomingCueTime)
    }

    @Test
    fun aWeakIncomingGridNeverStretchesEvenWithANearbyTempo() {
        for (advanced in listOf(false, true)) {
            val result = planTransition(
                analysis = track("a"),
                nextAnalysis = track("b", bpm = 118.0).copy(beatConfidence = 0.3),
                duration = 200.0,
                mode = CrossfadeMode.SMART,
                advanced = advanced,
            )
            assertEquals(TransitionStyle.DJ_FILTER, result.transitionStyle)
            assertEquals(1.0, result.incomingPlaybackRate)
        }
    }

    @Test
    fun aWeakOutgoingGridCannotBorrowTheIncomingConfidence() {
        val result = plan(out = track("a").copy(beatConfidence = 0.3))
        assertEquals(TransitionStyle.EQUAL_POWER, result.transitionStyle)
        assertEquals(1.0, result.incomingPlaybackRate)
        assertTrue("natural-uncertain-grid" in result.policyReasons)
    }

    @Test
    fun catalogTempoAloneIsNotABeatGrid() {
        val result = plan(
            out = track("a").copy(beatConfidence = 0.0, downbeats = emptyList()),
            inn = track("b").copy(beatConfidence = 0.0, downbeats = emptyList()),
        )
        assertEquals(TransitionStyle.EQUAL_POWER, result.transitionStyle)
        assertEquals(0.0, result.beatSeconds)
        assertFalse(result.phaseLock)
    }

    @Test
    fun twoSingersGetAShortFadeRatherThanALongChorusCollision() {
        val result = plan(out = track("a", vocal = 0.7), inn = track("b", vocal = 0.7))
        assertEquals(TransitionStyle.EQUAL_POWER, result.transitionStyle)
        assertEquals(2.5, result.fadeSeconds)
        assertTrue(result.vocalOverlap > 0.2)
        assertEquals(0.0, result.filterSweep)
        assertEquals(0.0, result.echoSeconds)
        assertFalse(result.bassSwap)
    }

    @Test
    fun missingOrMalformedMasksDoNotMeanInstrumental() {
        for (mask in listOf(emptyList(), listOf(0.0), List(400) { Double.NaN })) {
            val result = plan(
                out = track("a", vocal = null).copy(vocalActivityMask = mask),
                inn = track("b", vocal = null).copy(vocalActivityMask = mask),
            )
            assertEquals(TransitionStyle.EQUAL_POWER, result.transitionStyle)
            assertEquals(2.5, result.fadeSeconds)
            assertTrue(result.vocalOverlap >= 0.5)
        }
    }

    @Test
    fun measuredInstrumentalWindowsOverrideWholeSongVocalProbability() {
        val result = plan(
            out = track("a").copy(vocalProbability = 0.95),
            inn = track("b").copy(vocalProbability = 0.95),
        )
        assertEquals(TransitionStyle.DJ_BLEND, result.transitionStyle)
    }

    @Test
    fun aHarmonicClashIsNotDrawnOutEvenAtTheSameTempo() {
        val result = plan(inn = track("b").copy(key = "F♯ major"))
        assertEquals(TransitionStyle.EQUAL_POWER, result.transitionStyle)
        assertEquals(5.0, result.fadeSeconds)
        assertTrue("natural-key-clash" in result.policyReasons)
    }

    @Test
    fun aDeepDropDoesNotSkipTheIncomingIntro() {
        val result = plan(inn = track("b").copy(
            mixInTime = 40.5,
            mixInCandidates = listOf(MixCandidate(40.5, 1.0, "main_drop")),
        ))
        assertTrue(result.incomingCueTime <= 8.5, "cue=${result.incomingCueTime}")
        if (result.transitionStyle == TransitionStyle.EQUAL_POWER) assertEquals(0.5, result.incomingCueTime)
    }

    @Test
    fun anAttractiveEarlyExitDoesNotCutAwayTheOutro() {
        val result = plan(out = track("a").copy(
            mixOutTime = 187.0,
            mixOutCandidates = listOf(MixCandidate(187.0, 1.0, "energy_cliff")),
        ))
        assertTrue(result.transitionEnd >= 192.0, "exit=${result.transitionEnd}")
    }

    @Test
    fun missingAnalysisHasABoundedUsableFallback() {
        val result = plan(out = TrackAnalysis(), inn = TrackAnalysis())
        assertEquals(TransitionStyle.EQUAL_POWER, result.transitionStyle)
        assertEquals(3.5, result.fadeSeconds)
        assertEquals(200.0, result.transitionEnd)
        assertEquals(0.0, result.incomingCueTime)
        assertFalse(result.blocked)
    }

    @Test
    fun staleAnalysisCannotTrimOrCueAnotherRecording() {
        val result = plan(
            out = track("wrong").copy(status = "", contentEndTime = 100.0),
            inn = track("wrong").copy(status = "", audibleStartTime = 25.0),
        )
        assertEquals(200.0, result.transitionEnd)
        assertEquals(0.0, result.incomingCueTime)
        assertEquals(3.5, result.fadeSeconds)
    }

    @Test
    fun seekingPastABlendStartDoesNotPretendTheBeatsStillAlign() {
        val original = plan()
        val result = plan(time = original.transitionStart + 1.0)
        assertEquals(TransitionStyle.EQUAL_POWER, result.transitionStyle)
        assertTrue("natural-missed-beat" in result.policyReasons)
        assertEquals(1.0, result.incomingPlaybackRate)
        assertEquals(result.transitionEnd - result.transitionStart, result.fadeSeconds, 0.0001)
    }

    @Test
    fun theIncomingIntroUsesItsOwnTempoNotItsOutroTempo() {
        val opening = track("b")
        val intro = BeatSide(
            beats = (0 until 100).map { 0.5 + it * 0.5 },
            downbeats = opening.downbeats.filter { it < 50.0 },
            bpm = 120.0,
            confidence = 0.9,
        )
        val incoming = track("b", bpm = 90.0).copy(headBeats = intro)
        val result = plan(inn = incoming)
        assertEquals(TransitionStyle.DJ_BLEND, result.transitionStyle)
        assertEquals(1.0, result.incomingPlaybackRate)
        assertEquals(90.0, incoming.bpm, "planning must not mutate the cached analysis")
    }

    @Test
    fun anUncertainIntroDoesNotInheritConfidenceFromTheOutro() {
        val opening = BeatSide(
            beats = listOf(0.5, 1.0, 1.5),
            downbeats = listOf(0.5),
            bpm = 120.0,
            confidence = 0.1,
        )
        val result = plan(inn = track("b").copy(headBeats = opening))
        assertEquals(TransitionStyle.EQUAL_POWER, result.transitionStyle)
        assertEquals(1.0, result.incomingPlaybackRate)
    }

    @Test
    fun headOnlyAnalysisCannotAuthorizeABeatMixOnAnUnmeasuredOutro() {
        val result = plan(out = track("a").copy(
            downbeats = track("a").downbeats.filter { it < 30.0 },
            contentEndTime = 0.0,
            energyCurve = emptyList(),
            vocalActivityMask = emptyList(),
        ))
        assertEquals(TransitionStyle.EQUAL_POWER, result.transitionStyle)
        assertTrue("natural-unmeasured-grid" in result.policyReasons)
        assertEquals(200.0, result.transitionEnd)
    }

    @Test
    fun anOnTimeDecoderKeepsTheArmedBeatMix() {
        val armed = plan()
        assertEquals(armed, armed.forPlaybackStart(armed.transitionStart))
        assertEquals(armed, armed.forPlaybackStart(armed.transitionStart + 0.1))
    }

    @Test
    fun aLateDecoderShortensTheFadeAndDiscardsTheLostBeatAlignment() {
        val armed = plan(inn = track("b", bpm = 116.0))
        val position = armed.transitionStart + 1.0
        val adjusted = armed.forPlaybackStart(position)
        assertFalse(adjusted.blocked)
        assertTrue(adjusted.shouldStart)
        assertEquals(position, adjusted.transitionStart)
        assertEquals(armed.transitionEnd, adjusted.transitionEnd)
        assertEquals(armed.transitionEnd - position, adjusted.fadeSeconds, 0.0001)
        assertEquals(TransitionStyle.EQUAL_POWER, adjusted.transitionStyle)
        assertEquals(1.0, adjusted.incomingPlaybackRate)
        assertEquals(0.0, adjusted.beatSeconds)
        assertFalse(adjusted.bassSwap)
        assertEquals(0.0, adjusted.filterSweep)
        assertEquals(armed.incomingCueTime, adjusted.incomingCueTime)
    }

    @Test
    fun aDecoderWithTooLittleTimeLeftDoesNotSnapIntoTheNextSong() {
        val armed = plan()
        for (position in listOf(armed.transitionEnd - 0.5, armed.transitionEnd, armed.transitionEnd + 1.0)) {
            val adjusted = armed.forPlaybackStart(position)
            assertTrue(adjusted.blocked)
            assertFalse(adjusted.markerVisible)
            assertFalse(adjusted.shouldStart)
        }
    }

    @Test
    fun tinyAlbumHandoffsAreNotMistakenForLateBuffers() {
        val gapless = TransitionPlan(
            transitionStart = 199.55,
            transitionEnd = 200.0,
            fadeSeconds = 0.12,
            transitionStyle = TransitionStyle.GAPLESS,
        )
        assertEquals(gapless, gapless.forPlaybackStart(199.7))
    }

    @Test
    fun manualCrossfadeDoesNotInheritTheAutomixPolicy() {
        val result = plan(mode = CrossfadeMode.STANDARD)
        assertEquals(plan(natural = false, mode = CrossfadeMode.STANDARD), result)
        assertEquals(8.0, result.fadeSeconds)
    }

    @Test
    fun speechAndShortTrackGuardsStillApply() {
        assertTrue(planTransition(
            currentTrack = TransitionTrackInfo("a", 200_000, title = "Podcast episode 4"),
            duration = 200.0,
            mode = CrossfadeMode.SMART,
            natural = true,
        ).blocked)
        assertTrue(planTransition(duration = 20.0, mode = CrossfadeMode.SMART, natural = true).blocked)
    }

    @Test
    fun explicitSequentialAlbumHandoffIsNotConvertedToAFade() {
        val result = planTransition(
            currentTrack = TransitionTrackInfo("a", 200_000, albumId = "album"),
            nextTrack = TransitionTrackInfo("b", 200_000, albumId = "album"),
            duration = 200.0,
            mode = CrossfadeMode.SMART,
            albumSequential = true,
            natural = true,
        )
        assertEquals(TransitionStyle.GAPLESS, result.transitionStyle)
        assertEquals(0.12, result.fadeSeconds)
    }
}
