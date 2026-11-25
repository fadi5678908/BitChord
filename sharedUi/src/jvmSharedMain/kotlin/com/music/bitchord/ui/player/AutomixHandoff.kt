package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.settings.MixPresentation
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.data.settings.mixPresentation
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.theme.rememberPlayerTint
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource

/**
 * Two sleeves exchanging focus on the actual audio timeline. No free-running simulated bars:
 * gain comes from the renderer's fader curves, motion from published progress and beat anchors.
 * Expensive state is read in draw/layer scopes, not by the whole player composition.
 */
@Composable
internal fun AutomixHandoffHero(view: MixTransitionView, progress: State<Float>, live: Boolean, expandedArtwork: Boolean = false) {
    val reduced by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val blend = PlayerSettings.smartMixBlend.collectAsStateWithLifecycle()
    val playing by remember { derivedStateOf { blend.value?.playing == true } }
    val pulse = rememberMixPulse({ blend.value }, enabled = !reduced && live)
    val smooth = remember(view) { Animatable(progress.value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f) }
    LaunchedEffect(view, reduced, live, playing) {
        snapshotFlow { progress.value }.collectLatest { position ->
            val target = position.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            if (reduced || !live || !playing) smooth.snapTo(target)
            else smooth.animateTo(target, tween(90, easing = LinearEasing))
        }
    }
    val frame = remember(view) { derivedStateOf { mixPresentation(view, smooth.value) } }
    val outgoingTint = rememberPlayerTint(view.outgoing.artworkUrl, 128) ?: Color(0xFFA78BFA)
    val incomingTint = rememberPlayerTint(view.incoming.artworkUrl, 128) ?: Color(0xFF72DFD3)
    val stateLabel = stringResource(when {
        !live -> Res.string.automix_transition_ended
        !playing -> Res.string.automix_transition_paused
        view.customTiming -> Res.string.automix_custom_transition
        view.beatMatched -> Res.string.automix_beat_matched
        else -> Res.string.automix_smooth_transition
    })
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(if (view.customTiming) "MIX" else "AUTOMIX", color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            Spacer(Modifier.width(8.dp))
            HandoffPercent(frame)
        }
        val artSize = if (expandedArtwork) 128.dp else 76.dp
        val spread = if (expandedArtwork) 52f else 30f
        Box(Modifier.fillMaxWidth().height(if (expandedArtwork) 186.dp else 108.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
                val pose = frame.value
                val left = Offset(size.width * 0.33f, size.height * 0.5f)
                val right = Offset(size.width * 0.67f, size.height * 0.5f)
                val radius = 66.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(outgoingTint.copy(alpha = 0.22f * pose.outgoingGain), Color.Transparent), left, radius), radius, left)
                drawCircle(Brush.radialGradient(listOf(incomingTint.copy(alpha = 0.22f * pose.incomingGain), Color.Transparent), right, radius), radius, right)
                val arc = Path().apply {
                    moveTo(size.width * 0.2f, size.height * 0.68f)
                    cubicTo(size.width * 0.36f, size.height * 1.03f, size.width * 0.64f, size.height * 1.03f, size.width * 0.8f, size.height * 0.68f)
                }
                drawPath(arc, Brush.horizontalGradient(listOf(outgoingTint.copy(alpha = 0.35f), incomingTint.copy(alpha = 0.35f))), style = Stroke(1.5.dp.toPx()))
                val t = pose.progress
                val rest = 1f - t
                val bead = Offset(
                    size.width * (0.2f * rest * rest * rest + 1.08f * rest * rest * t + 1.92f * rest * t * t + 0.8f * t * t * t),
                    size.height * (0.68f + 1.05f * t * rest),
                )
                drawCircle(Color.White.copy(alpha = 0.7f), 2.dp.toPx(), bead)
            }
            AsyncImage(
                model = view.outgoing.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(artSize).graphicsLayer {
                    val pose = frame.value
                    translationX = (if (reduced) -spread else -spread - 12f * pose.progress).dp.toPx()
                    rotationZ = if (reduced) 0f else -6f * pose.progress
                    val scale = if (reduced) 1f else 1f - 0.12f * pose.progress
                    scaleX = scale
                    scaleY = scale
                    alpha = 0.35f + 0.65f * pose.outgoingGain
                }.clip(RoundedCornerShape(14.dp)).background(outgoingTint.copy(alpha = 0.18f)),
            )
            AsyncImage(
                model = view.incoming.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(artSize).graphicsLayer {
                    val pose = frame.value
                    translationX = (if (reduced) spread else spread * (1f - pose.progress)).dp.toPx()
                    rotationZ = if (reduced) 0f else 6f * (1f - pose.progress)
                    val scale = if (reduced) 1f else 0.86f + 0.14f * pose.progress + 0.006f * pulse.alpha(1f)
                    scaleX = scale
                    scaleY = scale
                    alpha = 0.35f + 0.65f * pose.incomingGain
                }.clip(RoundedCornerShape(14.dp)).background(incomingTint.copy(alpha = 0.18f)),
            )
        }
        Text(stateLabel, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        Spacer(Modifier.height(14.dp))
    }
}

/** Local recomposition only for the integer percentage, with a localized accessibility label. */
@Composable
private fun HandoffPercent(frame: State<MixPresentation>) {
    val percent by remember(frame) { derivedStateOf { (frame.value.progress * 100).toInt() } }
    val label = stringResource(Res.string.automix_transition_percent, percent)
    Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp,
        modifier = Modifier.clearAndSetSemantics { contentDescription = label })
}

/** A small version always visible beside "Mixing"; reuses the scrubber's existing beat clock. */
@Composable
internal fun AutomixHandoffMark(pulse: MixPulse, modifier: Modifier = Modifier) {
    val progress = PlayerSettings.smartMixProgress.collectAsStateWithLifecycle()
    Canvas(modifier.size(width = 20.dp, height = 12.dp).clearAndSetSemantics {}) {
        val p = progress.value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val radius = 3.5.dp.toPx()
        val left = Offset(radius, size.height / 2)
        val right = Offset(size.width - radius, size.height / 2)
        drawLine(Color.White.copy(alpha = 0.25f), left, right, 1.dp.toPx())
        drawCircle(Color.White.copy(alpha = (1f - p * 0.65f) * pulse.alpha(0.9f)), radius, left)
        drawCircle(Color.White.copy(alpha = (0.35f + p * 0.65f) * pulse.alpha(0.9f)), radius, right)
        drawCircle(Color.White, 1.5.dp.toPx(), Offset(left.x + (right.x - left.x) * p, left.y))
    }
}


/** Mounted directly over the player artwork. Appears automatically for a real audible overlap. */
@Composable
internal fun AutomixInlineHandoff(modifier: Modifier = Modifier) {
    val active by PlayerSettings.smartMixInProgress.collectAsStateWithLifecycle()
    val view by PlayerSettings.smartMixView.collectAsStateWithLifecycle()
    val progress = PlayerSettings.smartMixProgress.collectAsStateWithLifecycle()
    val shown = view ?: return
    if (!active) return
    Box(modifier.clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AutomixHandoffHero(shown, progress, live = true, expandedArtwork = true)
            Text(shown.outgoing.title, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, maxLines = 1)
            Text(shown.incoming.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
