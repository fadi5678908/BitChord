package com.music.bitchord.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.media3.session.MediaController
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.studioWave
import com.music.bitchord.playback.smart.MixWaveform
import com.music.bitchord.playback.smart.StemHandoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** A bounded decode of the played source. No energy-curve approximation or generated waveform. */
@Composable
internal fun StudioWaveformTrack(
    controller: MediaController?, song: Song, start: Float, length: Float, overlap: Float,
    outgoing: Boolean, handoff: StemHandoff, accent: Color, enabled: Boolean,
    elapsed: Float?, onStart: (Float) -> Unit, onEnvelope: (Float) -> Unit,
) {
    val span = minOf(24f, length.coerceAtLeast(0.1f))
    // Chunked viewport: a small drag does not decode the audio on every pointer frame.
    val region = (floor(start / 6f) * 6f - 6f).coerceIn(0f, (length - span).coerceAtLeast(0f))
    var retry by remember(song) { mutableIntStateOf(0) }
    var bands by remember(song, region, span) { mutableStateOf<MixWaveform.Bands?>(null) }
    var loading by remember(song, region, span) { mutableStateOf(true) }
    LaunchedEffect(controller, song, region, span, retry) {
        bands = null
        loading = true
        if (controller == null) return@LaunchedEffect
        delay(250)
        try { bands = controller.studioWave(song, region, region + span) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { bands = null }
        loading = false
    }
    val latestStart by rememberUpdatedState(start)
    val latestOnStart by rememberUpdatedState(onStart)
    val latestOnEnvelope by rememberUpdatedState(onEnvelope)
    val latestHandoff by rememberUpdatedState(handoff)
    val handleRadius = with(LocalDensity.current) { 28.dp.toPx() }
    val foreground = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(Modifier.fillMaxWidth().height(112.dp).clipToBounds().pointerInput(region, span, overlap, enabled) {
            if (!enabled) return@pointerInput
            var envelopeDrag = false
            var dragStart = 0f
            var origin = 0f
            detectDragGestures(onDragStart = { position ->
                val fraction = if (outgoing) latestHandoff.outgoingUntil else latestHandoff.incomingFrom
                val handleX = (latestStart + overlap * fraction - region) / span * size.width
                envelopeDrag = abs(position.x - handleX) < handleRadius && position.y > size.height * 0.55f
                dragStart = position.x
                origin = latestStart
            }) { change, _ ->
                change.consume()
                if (envelopeDrag) {
                    val time = region + change.position.x / size.width * span
                    latestOnEnvelope(((time - latestStart) / overlap).coerceIn(if (outgoing) 0.1f else 0f, if (outgoing) 1f else 0.9f))
                } else {
                    // Slide the selected source region; the overlap duration remains unchanged.
                    latestOnStart((origin + (change.position.x - dragStart) / size.width * span)
                        .coerceIn(0f, (length - overlap).coerceAtLeast(0f)))
                }
            }
        }) {
            val w = size.width
            val h = size.height
            fun x(time: Float) = (time - region) / span * w
            val left = x(start)
            val right = x(start + overlap)
            drawRect(accent.copy(alpha = 0.09f), Offset(left, 0f), Size(right - left, h))
            for (second in 0..span.toInt() step 2) {
                val px = second / span * w
                drawLine(gridColor.copy(alpha = 0.35f), Offset(px, 0f), Offset(px, h), 1f)
            }
            bands?.let { b ->
                val peak = b.low.indices.maxOfOrNull { b.low[it] + b.mid[it] + b.high[it] }?.coerceAtLeast(0.001f) ?: 1f
                for (i in b.low.indices) {
                    val px = (i + 0.5f) / b.low.size * w
                    val total = (b.low[i] + b.mid[i] + b.high[i]) / peak * h * 0.42f
                    drawLine(accent.copy(alpha = if (px in left..right) 0.95f else 0.3f),
                        Offset(px, h / 2 - total), Offset(px, h / 2 + total), max(1f, w / b.low.size * 0.75f))
                }
            }
            val curve = Path()
            for (i in 0..160) {
                val time = region + i / 160f * span
                val p = (time - start) / overlap
                val gains = handoff.gains(p)
                val gain = if (outgoing) gains.first else gains.second
                val point = Offset(i / 160f * w, 10f + (1f - gain) * (h - 20f))
                if (i == 0) curve.moveTo(point.x, point.y) else curve.lineTo(point.x, point.y)
            }
            drawPath(curve, foreground, style = Stroke(width = 2.dp.toPx()))
            drawLine(accent, Offset(left, 0f), Offset(left, h), 2.dp.toPx())
            drawLine(accent, Offset(right, 0f), Offset(right, h), 1.dp.toPx())
            val handle = x(start + overlap * if (outgoing) handoff.outgoingUntil else handoff.incomingFrom)
            drawCircle(foreground, 7.dp.toPx(), Offset(handle, h - 10f))
            elapsed?.let {
                val playhead = x(start + it)
                if (playhead in 0f..w) drawLine(Color(0xFF35EE92), Offset(playhead, 0f), Offset(playhead, h), 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(waveTime(region), style = MaterialTheme.typography.labelSmall)
            Text(waveTime(region + span), style = MaterialTheme.typography.labelSmall)
        }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    else if (bands == null) Row {
        Text(stringResource(R.string.transition_wave_unavailable), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { retry++ }) { Text(stringResource(R.string.transition_retry)) }
    }

}

private fun waveTime(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
