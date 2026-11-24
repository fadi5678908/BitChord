package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistTransitionDraftTest {
    private val ready = LocalPreparationConditions(true, true, true, true, true, true, 80, true, 2_000_000, 1_000_000)

    @Test fun draftsSanitizeInvalidTiming() {
        val d = PlaylistTransitionDraft(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY).sanitized()
        assertEquals(6f, d.overlapSeconds)
        assertEquals(0f, d.outgoingTrimSeconds)
        assertEquals(0f, d.incomingCueSeconds)
        val excessive = PlaylistTransitionDraft(100f, -2f, 60f).sanitized()
        assertEquals(12f, excessive.overlapSeconds)
        assertEquals(0f, excessive.outgoingTrimSeconds)
        assertEquals(30f, excessive.incomingCueSeconds)
    }

    @Test fun defaultVocalsDoNotOverlap() {
        val h = PlaylistTransitionDraft().handoff(TransitionStem.VOCALS)
        for (i in 0..100) {
            val (out, inn) = h.gains(i / 100f)
            assertTrue(out < 0.00001f || inn < 0.00001f)
        }
    }

    @Test fun malformedCurvesRemainFiniteAndBounded() {
        for (out in listOf(Float.NaN, -2f, 0f, 0.5f, Float.POSITIVE_INFINITY)) {
            for (inn in listOf(Float.NaN, -3f, 1f, 0.5f)) {
                val h = StemHandoff(out, inn)
                for (p in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f, 0.5f, 1f, 2f)) {
                    val gains = h.gains(p)
                    assertTrue(gains.first in 0f..1f && gains.second in 0f..1f)
                }
            }
        }
    }

    @Test fun allCurvesStartAndFinishCleanly() {
        for (stem in TransitionStem.entries) {
            val h = PlaylistTransitionDraft().handoff(stem)
            assertEquals(1f, h.gains(0f).first, 0.00001f)
            assertEquals(0f, h.gains(0f).second, 0.00001f)
            assertEquals(0f, h.gains(1f).first, 0.00001f)
            assertEquals(1f, h.gains(1f).second, 0.00001f)
        }
    }

    @Test fun changesToOneStemDoNotChangeTheOthers() {
        val before = PlaylistTransitionDraft()
        val after = before.withHandoff(TransitionStem.BASS, StemHandoff(0.3f, 0.7f))
        assertEquals(before.handoff(TransitionStem.VOCALS), after.handoff(TransitionStem.VOCALS))
        assertEquals(StemHandoff(0.3f, 0.7f), after.handoff(TransitionStem.BASS))
        assertEquals(PlaylistTransitionDraft(), before)
    }

    @Test fun sanitizingIsIdempotent() {
        val d = PlaylistTransitionDraft(20f, -3f, 100f, mapOf(TransitionStem.DRUMS to StemHandoff(Float.NaN, 7f)))
        assertEquals(d.sanitized(), d.sanitized().sanitized())
        assertEquals(TransitionStem.entries.toSet(), d.sanitized().stems.keys)
    }

    @Test fun windowsReferToActualSourceDurations() {
        val windows = PlaylistTransitionDraft(6f, 2f, 3f).windows(180.0, 200.0)
        assertEquals(TransitionSourceWindows(172.0, 178.0, 3.0, 9.0), windows)
    }

    @Test fun invalidOrTooShortSourcesCannotBePrepared() {
        for (duration in listOf(-1.0, 0.0, 4.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(PlaylistTransitionDraft().windows(duration, 200.0))
            assertNull(PlaylistTransitionDraft().windows(200.0, duration))
        }
        assertNull(PlaylistTransitionDraft(incomingCueSeconds = 30f).windows(200.0, 32.0))
    }

    @Test fun keysAreOrderedAndPlaylistScoped() {
        val a = PlaylistTransitionKey("list1", "a", "b")
        assertTrue(a.isValid)
        assertFalse(a == a.copy(playlistId = "list2"))
        assertFalse(a == a.copy(outgoingEntry = "b", incomingEntry = "a"))
        assertFalse(a.copy(playlistId = " ").isValid)
        assertFalse(a.copy(incomingEntry = "").isValid)
    }

    @Test fun preparationRequiresEveryGateIncludingAnInstalledModel() {
        assertTrue(ready.blockers().isEmpty())
        assertEquals(setOf(LocalPreparationBlock.MODEL_UNAVAILABLE), ready.copy(modelAvailable = false).blockers())
        assertEquals(setOf(LocalPreparationBlock.NOT_A_PLAYLIST), ready.copy(isPlaylist = false).blockers())
        assertEquals(setOf(LocalPreparationBlock.SOURCE_NOT_LOCAL), ready.copy(sourcesAreLocal = false).blockers())
        assertEquals(setOf(LocalPreparationBlock.INVALID_WINDOW), ready.copy(windowsValid = false).blockers())
    }

    @Test fun doNotPrepareDuringPlaybackOrOnBatteryOrWhenHot() {
        assertEquals(setOf(LocalPreparationBlock.DEVICE_BUSY), ready.copy(idle = false).blockers())
        assertEquals(setOf(LocalPreparationBlock.NOT_CHARGING), ready.copy(charging = false).blockers())
        assertEquals(setOf(LocalPreparationBlock.THERMAL_PRESSURE), ready.copy(thermalSafe = false).blockers())
        assertEquals(setOf(LocalPreparationBlock.BATTERY_LOW), ready.copy(batteryPercent = 29).blockers())
        assertEquals(setOf(LocalPreparationBlock.BATTERY_LOW), ready.copy(batteryPercent = -1).blockers())
    }

    @Test fun lowAndUnknownStorageFailClosed() {
        assertEquals(setOf(LocalPreparationBlock.STORAGE_LOW), ready.copy(freeBytes = 2).blockers())
        assertEquals(setOf(LocalPreparationBlock.STORAGE_LOW), ready.copy(requiredBytes = 0).blockers())
        assertEquals(setOf(LocalPreparationBlock.STORAGE_LOW), ready.copy(freeBytes = -1).blockers())
    }

    @Test fun allBlockedReasonsAreReportedNotOnlyTheFirst() {
        val blocked = LocalPreparationConditions(false, false, false, false, false, false, 0, false, 0, 1)
        assertEquals(LocalPreparationBlock.entries.toSet(), blocked.blockers())
    }
}
