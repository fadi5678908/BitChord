package com.music.bitchord.data.settings

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Drawn from the renderer's published faders, not from a decorative random equalizer. */
data class MixPresentation(val progress: Float, val outgoingGain: Float, val incomingGain: Float)

fun mixPresentation(view: MixTransitionView, progress: Float): MixPresentation {
    val p = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    fun gain(curve: FloatArray, fallback: Float): Float {
        if (curve.isEmpty()) return fallback
        val index = p * curve.lastIndex
        val from = index.toInt()
        val to = (from + 1).coerceAtMost(curve.lastIndex)
        val left = curve[from].takeIf { it.isFinite() } ?: fallback
        val right = curve[to].takeIf { it.isFinite() } ?: fallback
        return (left + (right - left) * (index - from)).coerceIn(0f, 1f)
    }
    return MixPresentation(
        p,
        gain(view.fadeOut, cos(p * PI / 2).toFloat()),
        gain(view.fadeIn, sin(p * PI / 2).toFloat()),
    )
}
