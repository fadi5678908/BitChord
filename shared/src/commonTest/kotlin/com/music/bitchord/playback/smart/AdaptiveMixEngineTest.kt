package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptiveMixEngineTest {
    private fun track(
        id: String,
        vocal: (Double) -> Double = { 0.0 },
        energy: (Double) -> Double = { 1.0 },
    ): TrackAnalysis {
        val curve = (0..800).map { EnergySample(it / 4.0, energy(it / 4.0)) }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = 200.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.95,
            firstBeat = 0.5,
            downbeats = (0..99).map { 0.5 + 2 * it },
            audibleStartTime = 0.5,
            contentEndTime = 198.0,
            mixInTime = 8.5,
            key = "C major",
            keyConfidence = 0.9,
            energyCurve = curve,
            vocalActivityMask = curve.map { vocal(it.time) },
        )
    }

    private val current = TransitionTrackInfo("a", 200_000)
    private val next = TransitionTrackInfo("b", 200_000)
    private fun plan(out: TrackAnalysis, inn: TrackAnalysis, time: Double = 10.0) = planTransition(
        analysis = out, nextAnalysis = inn,
        currentTrack = current, nextTrack = next,
        duration = 200.0, currentTime = time,
        mode = CrossfadeMode.SMART, advanced = true, natural = true,
    )

    private fun seed() = TransitionPlan(
        transitionStart = 185.5, transitionEnd = 197.5, fadeSeconds = 12.0,
        incomingCueTime = 0.5, incomingPlaybackRate = 1.0,
        transitionStyle = TransitionStyle.DJ_BLEND, beatSeconds = 0.5,
    )

    @Test
    fun aCleanInstrumentalEntryCanRescueAnOtherwiseCollidingPair() {
        val out = track("a", vocal = { if (it >= 192.0) 0.8 else 0.0 })
        val inn = track("b", vocal = { if (it >= 3.5) 0.8 else 0.0 })
        val result = plan(out, inn)
        assertEquals(TransitionStyle.DJ_BLEND, result.transitionStyle)
        assertTrue(result.fadeSeconds <= 4.0)
        assertTrue(result.vocalOverlap <= 0.2)
        assertTrue(result.incomingCueTime < 3.5)
        assertTrue(result.policyReasons.any { it.startsWith("adaptive-search:") })
    }

    @Test
    fun everyCandidateObeysTheCallersSafetyVeto() {
        assertNull(AdaptiveMixEngine.select(track("a"), track("b"), seed(), 198.0, 200.0, 0.5) { false })
    }

    @Test
    fun searchIsDeterministicAndStrictlyBounded() {
        val out = track("a")
        val inn = track("b")
        val seed = seed()
        val first = assertNotNull(AdaptiveMixEngine.select(out, inn, seed, 198.0, 200.0, 0.5) { true })
        val second = AdaptiveMixEngine.select(out, inn, seed, 198.0, 200.0, 0.5) { true }
        assertEquals(first, second)
        assertTrue(first.evaluated in 2..AdaptiveMixEngine.MAX_CANDIDATES)
        assertTrue(first.score.isFinite())
    }

    @Test
    fun energyMatchingCanAvoidADeadIncomingBed() {
        val out = track("a")
        val inn = track("b", energy = { if (it < 3.0) 0.01 else 1.0 })
        val result = plan(out, inn)
        assertEquals(TransitionStyle.DJ_BLEND, result.transitionStyle)
        assertTrue(result.incomingCueTime >= 2.5, "cue=${result.incomingCueTime}")
        assertTrue(result.incomingCueTime <= 8.5)
    }

    @Test
    fun unknownMasksCannotInventAnInstrumentalAlternative() {
        val out = track("a").copy(vocalActivityMask = emptyList())
        val inn = track("b").copy(vocalActivityMask = emptyList())
        val seed = seed()
        val result = assertNotNull(AdaptiveMixEngine.select(out, inn, seed, 198.0, 200.0, 0.5) { true })
        assertEquals(seed.transitionStart, result.plan.transitionStart)
        assertEquals(seed.incomingCueTime, result.plan.incomingCueTime)
    }

    @Test
    fun missingTailCoverageIsNotExtrapolatedFromAHeadMask() {
        val full = track("a")
        val partial = full.copy(energyCurve = full.energyCurve.take(120), vocalActivityMask = full.vocalActivityMask.take(120))
        val seed = seed()
        val result = assertNotNull(AdaptiveMixEngine.select(partial, track("b"), seed, 198.0, 200.0, 0.5) { true })
        assertEquals(seed.transitionStart, result.plan.transitionStart)
    }

    @Test
    fun weakOrDistantTempiDoNotEnterTheSearch() {
        val out = track("a")
        assertNull(AdaptiveMixEngine.select(out, track("b").copy(beatConfidence = 0.2), seed(), 198.0, 200.0, 0.5) { true })
        assertNull(AdaptiveMixEngine.select(out, track("b").copy(bpm = 100.0), seed(), 198.0, 200.0, 0.5) { true })
    }

    @Test
    fun generatedPlansUseTheObservedLocalBarRatio() {
        val incoming = track("b").copy(downbeats = (0..95).map { 0.5 + 2.04 * it })
        val result = assertNotNull(AdaptiveMixEngine.select(track("a"), incoming, seed(), 198.0, 200.0, 0.5) {
            it.reason == "smart-adaptive-blend"
        })
        assertEquals(1.02, result.plan.incomingPlaybackRate, 0.0001)
        assertEquals(0.5, result.plan.beatSeconds, 0.0001)
    }

    @Test
    fun localBarMatchingPreservesHalfTimeCompatibility() {
        val incoming = track("b").copy(bpm = 60.0, downbeats = (0..49).map { 0.5 + 4.0 * it })
        val result = assertNotNull(AdaptiveMixEngine.select(track("a"), incoming, seed(), 198.0, 200.0, 0.5) {
            it.reason == "smart-adaptive-blend"
        })
        assertEquals(1.0, result.plan.incomingPlaybackRate, 0.0001)
        assertEquals(120.0, result.plan.incomingBpm, 0.0001)
    }

    @Test
    fun missingDownbeatsCannotBeFilledInByAGuessedGrid() {
        val out = track("a").copy(downbeats = emptyList())
        val seed = seed()
        val result = assertNotNull(AdaptiveMixEngine.select(out, track("b"), seed, 198.0, 200.0, 0.5) { true })
        assertEquals(1, result.evaluated)
    }

    @Test
    fun shorterFallbackCanAvoidABriefCollisionHiddenByAnAverage() {
        val out = track("a", vocal = { if (it in 193.0..194.25) 0.8 else 0.0 })
        val inn = track("b", vocal = { if (it < 2.0) 0.8 else 0.0 })
        val fallback = TransitionPlan(
            transitionStart = 193.0, transitionEnd = 198.0, fadeSeconds = 5.0,
            incomingCueTime = 0.5, incomingHandoffTime = 5.5,
        )
        val result = AdaptiveMixEngine.refineFallback(out, inn, fallback)
        assertTrue(result.fadeSeconds < fallback.fadeSeconds)
        assertEquals(fallback.transitionEnd, result.transitionEnd)
        assertEquals(fallback.incomingCueTime, result.incomingCueTime)
        assertTrue(result.vocalOverlap <= 0.05)
    }

    @Test
    fun unknownOrCleanFallbackIsNotShortenedForNoReason() {
        val fallback = seed().copy(transitionStyle = TransitionStyle.EQUAL_POWER, fadeSeconds = 3.5, transitionStart = 194.0)
        assertEquals(fallback, AdaptiveMixEngine.refineFallback(track("a"), track("b"), fallback))
        assertEquals(fallback, AdaptiveMixEngine.refineFallback(
            track("a").copy(vocalActivityMask = emptyList()), track("b"), fallback,
        ))
    }

    @Test
    fun onePairIsScoredOnceNotOnEveryPlaybackTick() {
        val session = AutomixSessionPlanner()
        val out = track("a")
        val inn = track("b")
        val first = session.plan(out, inn, current, next, 10.0)
        repeat(100) {
            val result = session.plan(out, inn, current, next, 10.0 + it)
            assertEquals(first.transitionStart, result.transitionStart)
            assertEquals(first.incomingCueTime, result.incomingCueTime)
        }
        assertEquals(1, session.planningPasses)
    }

    @Test
    fun freshEmptyPlaceholdersDoNotCauseAPlanningLoop() {
        val session = AutomixSessionPlanner()
        repeat(50) {
            session.plan(TrackAnalysis(trackId = "a"), TrackAnalysis(trackId = "b"), current, next, it.toDouble())
        }
        assertEquals(1, session.planningPasses)
        session.plan(track("a"), track("b"), current, next, 50.0)
        assertEquals(2, session.planningPasses)
    }

    @Test
    fun refinedAnalysisAndQueueChangesInvalidateTheCache() {
        val session = AutomixSessionPlanner()
        val out = track("a")
        val inn = track("b")
        session.plan(out, inn, current, next, 10.0)
        session.plan(out, inn.copy(), current, next, 10.0)
        assertEquals(2, session.planningPasses)
        session.plan(out, inn, current, next.copy(id = "c"), 10.0)
        assertEquals(3, session.planningPasses)
        session.clear()
        session.plan(out, inn, current, next, 10.0)
        assertEquals(4, session.planningPasses)
    }

    @Test
    fun seekPastAChosenStartRetimesTheSameExitInsteadOfSearchingForANewOne() {
        val session = AutomixSessionPlanner()
        val out = track("a")
        val inn = track("b")
        val initial = session.plan(out, inn, current, next, 10.0)
        val late = session.plan(out, inn, current, next, initial.transitionStart + 1.0)
        assertEquals(1, session.planningPasses)
        assertEquals(initial.transitionEnd, late.transitionEnd)
        assertEquals(TransitionStyle.EQUAL_POWER, late.transitionStyle)
        assertEquals(1.0, late.incomingPlaybackRate)
        assertFalse(late.blocked)
        val tooLate = session.plan(out, inn, current, next, initial.transitionEnd - 0.3)
        assertTrue(tooLate.blocked)
    }

    @Test
    fun aTempoAndVocalMatrixKeepsAllNaturalSafetyRails() {
        for (bpm in listOf(70.0, 100.0, 116.0, 120.0, 126.0, 180.0)) {
            for (vocal in listOf(0.0, 0.8)) {
                val out = track("a", vocal = { vocal })
                val inn = track("b", vocal = { vocal }).copy(bpm = bpm)
                val result = plan(out, inn)
                assertTrue(result.fadeSeconds.isFinite() && result.fadeSeconds in 0.0..12.0)
                assertTrue(result.transitionStart < result.transitionEnd)
                assertTrue(result.transitionEnd <= 198.0)
                assertTrue(result.incomingCueTime in 0.0..8.5)
                assertTrue(result.incomingPlaybackRate in 0.94..1.06)
                assertEquals(1.0, result.outgoingPlaybackRate)
                if (result.transitionStyle == TransitionStyle.DJ_BLEND) assertTrue(result.vocalOverlap <= 0.2)
            }
        }
    }
}
