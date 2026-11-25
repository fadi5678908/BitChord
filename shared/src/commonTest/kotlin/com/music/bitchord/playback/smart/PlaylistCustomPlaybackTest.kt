package com.music.bitchord.playback.smart

import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixTransitionView
import kotlin.test.*

class PlaylistCustomPlaybackTest {
    private val custom = PlaylistTransitionDraft(overlapSeconds = 8f, outgoingStartSeconds = 70f, incomingStartSeconds = 42f, enabled = true)

    @Test fun explicitExitAndEntryBecomeAPlayableNaturalSpeedPlan() {
        val p = assertNotNull(custom.playbackPlan(200.0, 180.0, 20.0))
        assertEquals(70.0, p.transitionStart)
        assertEquals(78.0, p.transitionEnd)
        assertEquals(42.0, p.incomingCueTime)
        assertEquals(50.0, p.incomingHandoffTime)
        assertEquals(TransitionStyle.EQUAL_POWER, p.transitionStyle)
        assertEquals(1.0, p.incomingPlaybackRate)
        assertEquals(1.0, p.outgoingPlaybackRate)
        assertFalse(p.shouldStart)
        assertFalse(p.phaseLock)
        assertFalse(p.bassSwap)
        assertTrue("playlist-custom-timing" in p.policyReasons)
    }

    @Test fun aSavedDraftIsNotEnabledByMigration() {
        assertNull(PlaylistTransitionDraft().playbackPlan(200.0, 180.0, 0.0))
        assertNull(custom.copy(enabled = false).playbackPlan(200.0, 180.0, 0.0))
    }

    @Test fun selectedEntryIsNotRestrictedToTheFirstThirtySeconds() {
        val p = assertNotNull(custom.copy(incomingStartSeconds = 120f).playbackPlan(200.0, 180.0, 0.0))
        assertEquals(120.0, p.incomingCueTime)
    }

    @Test fun actualDurationsMustAuthorizeBothSelectedRegions() {
        assertNull(custom.playbackPlan(75.0, 180.0, 0.0))
        assertNull(custom.playbackPlan(200.0, 45.0, 0.0))
        assertNull(custom.playbackPlan(200.0, 0.0, 0.0))
        assertNull(custom.playbackPlan(Double.NaN, 180.0, 0.0))
    }

    @Test fun theSelectedStartTriggersOnlyWhenReached() {
        assertFalse(assertNotNull(custom.playbackPlan(200.0, 180.0, 69.9)).shouldStart)
        assertTrue(assertNotNull(custom.playbackPlan(200.0, 180.0, 70.0)).shouldStart)
    }

    @Test fun lateDecoderCannotFadePastTheChosenExit() {
        val p = assertNotNull(custom.playbackPlan(200.0, 180.0, 72.0))
        assertEquals(72.0, p.transitionStart)
        assertEquals(78.0, p.transitionEnd)
        assertEquals(6.0, p.fadeSeconds)
        assertEquals(42.0, p.incomingCueTime)
    }

    @Test fun seekingPastTheExitDoesNotRestartAnOldMix() {
        assertTrue(assertNotNull(custom.playbackPlan(200.0, 180.0, 100.0)).blocked)
    }

    @Test fun legacyTimingRemainsAvailableWhenExplicitPositionsAreUnset() {
        val p = assertNotNull(PlaylistTransitionDraft(enabled = true).playbackPlan(200.0, 180.0, 0.0))
        assertEquals(194.0, p.transitionStart)
        assertEquals(200.0, p.transitionEnd)
        assertEquals(0.0, p.incomingCueTime)
    }

    @Test fun aUserSelectedIncomingEndingIsNotSilentlyShortenedToOneThird() {
        val customPlan = assertNotNull(custom.playbackPlan(200.0, 180.0, 0.0))
        assertEquals(8_000L, customPlan.incomingFadeLimitMs(8_000L))
        assertEquals(2_666L, TransitionPlan().incomingFadeLimitMs(8_000L))
        assertEquals(Long.MAX_VALUE, customPlan.incomingFadeLimitMs(null))
    }

    @Test fun entryKeysDistinguishRemoteDuplicatesWithoutUsingTheTitle() {
        assertNotEquals(playlistTransitionEntry("song", "slot1"), playlistTransitionEntry("song", "slot2"))
        assertEquals("song:4:song:0", playlistTransitionEntry("song", null))
    }

    @Test fun diagnosticsCannotFollowThePreviousPairOntoANewSong() {
        val memory = AutomixPlanDiagnostics()
        val late = TransitionPlan(reason = "smart-late-buffer-fade", policyReasons = listOf("late-buffer"))
        memory.remember("entry-a" to "entry-b", late)
        assertEquals(late, memory.forPair("entry-a" to "entry-b"))
        assertNull(memory.forPair("entry-b" to "entry-c"))
        assertNull(memory.forPair("entry-b" to "entry-a"))
        assertNull(memory.forPair("entry-a-new-occurrence" to "entry-b"))
    }

    @Test fun finishingOrSeekingClearsTheStoredVerdict() {
        val memory = AutomixPlanDiagnostics()
        memory.remember("a" to "b", TransitionPlan())
        memory.clear()
        assertNull(memory.forPair("a" to "b"))
    }

    @Test fun refreshingWaveformsPreservesCustomTimingWithoutClaimingBeatmatching() {
        val deck = MixDeck("title", "artist", null, 0.0, "", 200_000, 0.0, 20.0)
        val view = MixTransitionView(deck, deck, 70.0, 78.0, 42.0, 50.0, 0, floatArrayOf(), floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), customTiming = true)
        val refreshed = view.withDecks(deck, deck)
        assertTrue(refreshed.customTiming)
        assertFalse(refreshed.beatMatched)
    }
}
