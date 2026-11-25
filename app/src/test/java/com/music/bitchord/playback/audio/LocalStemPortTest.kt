package com.music.bitchord.playback.audio

import com.music.bitchord.playback.smart.VocalTracker
import org.junit.Assert.*
import org.junit.Test

class LocalStemPortTest {
    private fun window(outgoing: Boolean) = LocalStemPort.Window("track", 0.0, 0.0, 2.0, outgoing,
        VocalTracker.Separated(FloatArray(88201) { .7f }, FloatArray(88201) { .5f },
            FloatArray(176402) { if (it % 2 == 0) .2f else .1f }))
    private fun block() = AudioBlock().apply { setFrameCount(8); samples.fill(.9f) }

    @Test fun idleAndOutsideTheSelectedMediaWindowAreBitIdentical() {
        val port = LocalStemPort()
        val audio = block()
        port.process(audio, 0, 48000)
        assertEquals(.9f, audio.samples[0], 0f)
        port.arm(window(true))
        port.process(audio, -1, 48000)
        port.process(audio, 3_000_000, 48000)
        assertEquals(.9f, audio.samples[0], 0f)
        assertFalse(port.processed)
    }

    @Test fun outgoingLosesVocalsButRetainsActualAccompaniment() {
        val port = LocalStemPort().apply { arm(window(true)) }
        val audio = block()
        port.process(audio, 0, 48000)
        assertEquals(.7f, audio.samples[0], .00001f)
        assertEquals(.5f, audio.samples[1], .00001f)
        port.process(audio, 1_000_000, 48000)
        assertEquals(.5f, audio.samples[0], .00001f)
        assertEquals(.4f, audio.samples[1], .00001f)
        assertTrue(port.processed)
    }

    @Test fun incomingVocalsEnterBeforeItsAccompaniment() {
        val port = LocalStemPort().apply { arm(window(false)) }
        val audio = block()
        port.process(audio, 400_000, 44100)
        assertEquals(.2f, audio.samples[0], .00001f)
        assertEquals(.1f, audio.samples[1], .00001f)
        port.process(audio, 1_999_000, 48000)
        assertEquals(.7f, audio.samples[0], .00001f)
        assertEquals(.5f, audio.samples[1], .00001f)
    }

    @Test fun startupFlushKeepsPreparedAudioButSeekAfterRenderingDropsIt() {
        val port = LocalStemPort().apply { arm(window(true)) }
        port.onFlush()
        assertNotNull(port.window)
        port.process(block(), 100_000, 44100)
        port.onFlush()
        assertNull(port.window)
        port.arm(window(false))
        port.retainOwner("different")
        assertNull(port.window)
    }

    @Test fun nonFiniteModelOutputNeverReachesTheSink() {
        val w = window(false)
        w.audio.vocal.fill(Float.NaN)
        val port = LocalStemPort().apply { arm(w) }
        val audio = block()
        port.process(audio, 100_000, 44100)
        assertEquals(0f, audio.samples[0], 0f)
        port.clear()
        assertFalse(port.processed)
        assertNull(port.window)
    }
}
