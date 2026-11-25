package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Audition modes for actual reconstructed sources; not four-stem labels. */
enum class LocalStemAudition { VOCAL_OVER_RHYTHM, VOCALS, INSTRUMENTAL }

data class TwoStemGains(val outVocal: Float, val outMusic: Float, val inVocal: Float, val inMusic: Float)

/** Outgoing vocals leave first; incoming music arrives last, leaving a vocal-over-rhythm middle. */
fun vocalRhythmGains(progress: Float, envelope: StemHandoff = StemHandoff(), mode: LocalStemAudition = LocalStemAudition.VOCAL_OVER_RHYTHM): TwoStemGains {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val (out, inn) = envelope.gains(p)
    if (mode == LocalStemAudition.VOCALS) return TwoStemGains(out, 0f, inn, 0f)
    if (mode == LocalStemAudition.INSTRUMENTAL) return TwoStemGains(0f, out, 0f, inn)
    val leavingVoice = cos((p / 0.35f).coerceIn(0f, 1f) * PI / 2).toFloat().coerceAtLeast(0f)
    val arrivingMusic = sin(((p - 0.6f) / 0.4f).coerceIn(0f, 1f) * PI / 2).toFloat()
    return TwoStemGains(out * leavingVoice, out, inn, inn * arrivingMusic)
}
