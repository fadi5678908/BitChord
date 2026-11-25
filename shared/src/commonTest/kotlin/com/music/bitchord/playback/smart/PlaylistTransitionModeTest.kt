package com.music.bitchord.playback.smart

import com.music.bitchord.data.model.*
import kotlin.test.*

class PlaylistTransitionModeTest {
    private val automatic = PlaylistTransitionDraft(enabled = true, mode = PlaylistTransitionMode.AUTOMATIC)
    private val song = Song("a", "A", "Artist", null, playbackSourceType = PlaybackSourceType.PLAYLIST, playbackSourceId = "list")

    @Test fun automaticDoesNotWaitForAnalysisOrGlobalCrossfade() {
        val plan = assertNotNull(automatic.playbackPlan(180.0, 150.0, 0.0))
        assertEquals(174.0, plan.transitionStart)
        assertEquals(180.0, plan.transitionEnd)
        assertEquals(6.0, plan.fadeSeconds)
        assertEquals(0.0, plan.incomingCueTime)
        assertEquals(1.0, plan.incomingPlaybackRate)
        assertFalse(plan.phaseLock)
        assertFalse(plan.blocked)
        assertTrue("playlist-mode:AUTOMATIC" in plan.policyReasons)
    }

    @Test fun automaticMayUseBoundedEvidenceButNeverStretchesAudio() {
        val out = TrackAnalysis(bpm = 120.0, beatConfidence = .8, contentEndTime = 179.0)
        val inn = TrackAnalysis(audibleStartTime = 1.5, vocalWindows = listOf(CurveWindow(10.0, 2.0, floatArrayOf(0f, .9f))))
        val p = assertNotNull(automatic.copy(separateVocals = true).playbackPlan(180.0, 150.0, 0.0, out, inn))
        assertEquals(175.0, p.transitionStart)
        assertEquals(179.0, p.transitionEnd)
        assertEquals(10.5, p.incomingCueTime)
        assertEquals(1.0, p.outgoingPlaybackRate)
        assertEquals(1.0, p.incomingPlaybackRate)
        assertEquals(1.5, assertNotNull(automatic.playbackPlan(180.0, 150.0, 0.0, out, inn)).incomingCueTime)
    }

    @Test fun crossfadeIgnoresStaleCustomCuesAndStemSwitch() {
        val draft = automatic.copy(mode = PlaylistTransitionMode.CROSSFADE, outgoingStartSeconds = 20f,
            incomingStartSeconds = 50f, overlapSeconds = 8f, volumeHandoff = StemHandoff(.2f, .8f), separateVocals = true)
        val p = assertNotNull(draft.playbackPlan(180.0, 150.0, 0.0))
        assertEquals(172.0, p.transitionStart)
        assertEquals(0.0, p.incomingCueTime)
        assertEquals(StemHandoff(), p.volumeHandoff)
        assertFalse(draft.sanitized().separateVocals)
    }

    @Test fun invalidOrTinySourcesNeverInventPlayableWindows() {
        for (mode in PlaylistTransitionMode.entries) {
            val d = automatic.copy(mode = mode)
            assertNull(d.playbackPlan(Double.NaN, 150.0, 0.0))
            assertNull(d.playbackPlan(180.0, 0.0, 0.0))
            assertNull(d.playbackPlan(1.0, 150.0, 0.0))
        }
        assertNotNull(automatic.playbackPlan(2.5, 150.0, 0.0))
    }

    @Test fun onlyAnOrderedPlaylistContextQualifies() {
        val inn = song.copy(videoId = "b")
        assertNotNull(playlistTransitionKeyFor(song, inn, false))
        assertNull(playlistTransitionKeyFor(song, inn, true))
        assertNull(playlistTransitionKeyFor(song, inn.copy(playbackSourceId = "other"), false))
        assertNull(playlistTransitionKeyFor(song.copy(playbackSourceType = PlaybackSourceType.BROWSE), inn, false))
        for (type in PlaybackSourceType.entries.filter { it != PlaybackSourceType.PLAYLIST }) {
            assertNull(playlistTransitionKeyFor(song.copy(playbackSourceType = type), inn.copy(playbackSourceType = type), false))
        }
        for (tier in QueueTier.entries.filter { it != QueueTier.CONTEXT }) {
            assertNull(playlistTransitionKeyFor(song, inn.copy(queueTier = tier), false))
        }
        assertNull(playlistTransitionKeyFor(song, inn.copy(isVideoOrigin = true), false))
        assertNull(playlistTransitionKeyFor(song, inn.copy(radioName = "Radio"), false))
    }

    @Test fun explicitProfileWinsAndDisabledProfileDoesNotFallThrough() {
        val custom = PlaylistTransitionDraft(enabled = true, overlapSeconds = 9f)
        assertEquals(custom, playlistTransitionProfile(custom, true, 12))
        assertNull(playlistTransitionProfile(custom.copy(enabled = false), true, 12))
        assertEquals(PlaylistTransitionMode.AUTOMATIC, playlistTransitionProfile(null, true, 12)?.mode)
        assertEquals(PlaylistTransitionMode.CROSSFADE, playlistTransitionProfile(null, false, 8)?.mode)
        assertNull(playlistTransitionProfile(null, false, 0))
    }
}
