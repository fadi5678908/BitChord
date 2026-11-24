package com.music.bitchord.playback.smart

import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.data.settings.mixPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixPresentationTest {
    private fun view(out: FloatArray = floatArrayOf(1f, 0.8f, 0f), inn: FloatArray = floatArrayOf(0f, 0.3f, 1f)): MixTransitionView {
        val deck = MixDeck("Song", "Artist", null, 120.0, "C major", 200_000, 0.0, 10.0)
        return MixTransitionView(deck, deck, 190.0, 198.0, 0.5, 8.5, 16, floatArrayOf(), out, inn, beatMatched = true)
    }

    @Test
    fun artworkFocusReadsTheRealFaderCurves() {
        val frame = mixPresentation(view(), 0.5f)
        assertEquals(0.8f, frame.outgoingGain)
        assertEquals(0.3f, frame.incomingGain)
        assertEquals(0.9f, mixPresentation(view(), 0.25f).outgoingGain, 0.0001f)
    }

    @Test
    fun endpointsAndNonFiniteProgressAreSafe() {
        assertEquals(0f, mixPresentation(view(), -1f).progress)
        assertEquals(1f, mixPresentation(view(), 2f).progress)
        assertEquals(0f, mixPresentation(view(), Float.NaN).progress)
        assertEquals(1f, mixPresentation(view(), 0f).outgoingGain)
        assertEquals(1f, mixPresentation(view(), 1f).incomingGain)
    }

    @Test
    fun absentAndMalformedFadersNeverProduceInvalidLayers() {
        for (curve in listOf(floatArrayOf(), floatArrayOf(Float.NaN), floatArrayOf(-1f, 2f))) {
            val frame = mixPresentation(view(curve, curve), 0.5f)
            assertTrue(frame.outgoingGain.isFinite() && frame.outgoingGain in 0f..1f)
            assertTrue(frame.incomingGain.isFinite() && frame.incomingGain in 0f..1f)
        }
    }

    @Test
    fun waveformRefreshPreservesTheExplicitRendererMode() {
        val original = view()
        val refreshed = original.withDecks(original.outgoing, original.incoming)
        assertTrue(refreshed.beatMatched)
        assertEquals(original.fadeIn, refreshed.fadeIn)
    }
}
