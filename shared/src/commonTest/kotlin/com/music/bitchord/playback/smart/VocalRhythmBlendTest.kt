package com.music.bitchord.playback.smart

import kotlin.test.*

class VocalRhythmBlendTest {
    @Test fun endpointsPreserveOriginalOutgoingAndIncoming() {
        val a = vocalRhythmGains(0f)
        assertEquals(1f, a.outVocal, 0.0001f); assertEquals(1f, a.outMusic, 0.0001f)
        assertEquals(0f, a.inVocal, 0.0001f); assertEquals(0f, a.inMusic, 0.0001f)
        val b = vocalRhythmGains(1f)
        assertEquals(0f, b.outVocal, 0.0001f); assertEquals(0f, b.outMusic, 0.0001f)
        assertEquals(1f, b.inVocal, 0.0001f); assertEquals(1f, b.inMusic, 0.0001f)
    }
    @Test fun middleHasIncomingVoiceOverOutgoingMusicWithoutTwoVoicesOrTwoDrums() {
        val m = vocalRhythmGains(0.5f)
        assertEquals(0f, m.outVocal, 0.0001f); assertEquals(0f, m.inMusic, 0.0001f)
        assertTrue(m.inVocal > 0.6f); assertTrue(m.outMusic > 0.6f)
    }
    @Test fun soloModesCannotLeakOtherReconstructedSources() {
        for(i in 0..100) {
            val v = vocalRhythmGains(i / 100f, mode = LocalStemAudition.VOCALS)
            assertEquals(0f,v.outMusic); assertEquals(0f,v.inMusic)
            val m = vocalRhythmGains(i / 100f, mode = LocalStemAudition.INSTRUMENTAL)
            assertEquals(0f,m.outVocal); assertEquals(0f,m.inVocal)
        }
    }
    @Test fun invalidTimeDoesNotGenerateNonFiniteAudioGains() {
        for(p in listOf(Float.NaN,Float.POSITIVE_INFINITY,-5f,5f)) {
            val g = vocalRhythmGains(p)
            for(v in listOf(g.outVocal,g.outMusic,g.inVocal,g.inMusic)) assertTrue(v.isFinite() && v in 0f..1f)
        }
    }
}
