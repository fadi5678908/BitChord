package com.music.bitchord.playback.audio

import com.music.bitchord.playback.smart.VocalTracker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Prepared data only. No decoding, allocation or model inference on the audio thread. */
class LocalStemPort {
    data class Window(val owner: String, val sourceStart: Double, val overlapStart: Double,
        val overlapSeconds: Double, val outgoing: Boolean, val audio: VocalTracker.Separated)
    @Volatile var window: Window? = null
        private set
    @Volatile var processed = false
        private set

    fun arm(next: Window) {
        if (window == next) return
        processed = false
        window = next
    }
    fun clear() { window = null; processed = false }
    /** A startup seek may occur after an incoming window is armed, before it has rendered. */
    fun onFlush() { if (processed) clear() }
    fun retainOwner(owner: String?) { if (window?.owner != owner) clear() }

    fun process(block: AudioBlock, sourceTimeUs: Long, sampleRate: Int) {
        val w = window ?: return
        if (sourceTimeUs < 0 || sampleRate <= 0 || block.channelCount != 2 || w.overlapSeconds <= 0) return
        val source = w.audio
        for (i in 0 until block.frameCount) {
            val time = sourceTimeUs / 1e6 + i.toDouble() / sampleRate
            val p = (time - w.overlapStart) / w.overlapSeconds
            if (p < 0 || p >= 1) continue
            val at = (time - w.sourceStart) * 44100.0
            if (at < 0 || at >= source.frames - 1) continue
            val first = at.toInt()
            val fraction = (at - first).toFloat()
            val vocal = if (w.outgoing) cos((p / .35).coerceIn(0.0,1.0) * PI / 2).toFloat() else 1f
            val music = if (w.outgoing) 1f else sin(((p - .6) / .4).coerceIn(0.0,1.0) * PI / 2).toFloat()
            for (c in 0..1) {
                val v0 = source.vocal[first * 2 + c]
                val v = v0 + (source.vocal[(first+1)*2+c] - v0) * fraction
                val m0 = source.mixture(first,c)
                val mix = m0 + (source.mixture(first+1,c) - m0) * fraction
                val value = v * vocal + (mix-v) * music
                block.samples[i*2+c] = if (value.isFinite()) value else 0f
            }
            processed = true
        }
    }
}
