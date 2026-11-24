package com.music.bitchord.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.PlaylistTransitionDraftStore
import com.music.bitchord.playback.smart.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Playlist-only design surface. Never masquerades design curves as measured stems or waveforms. */
@Composable
fun PlaylistTransitionEditor(playlistId: String, songs: List<Song>, onDismiss: () -> Unit) {
    if (playlistId.isBlank() || songs.size < 2) return
    val context = LocalContext.current
    val store = remember(context) { PlaylistTransitionDraftStore(context) }
    val scope = rememberCoroutineScope()
    var selectedIndex by rememberSaveable(playlistId, songs) { mutableIntStateOf(0) }
    val index = selectedIndex.coerceIn(0, songs.size - 2)
    val outgoing = songs[index]
    val incoming = songs[index + 1]
    val key = remember(playlistId, songs, index) {
        fun entry(at: Int): String {
            val song = songs[at]
            // Remote playlist entry IDs distinguish duplicate songs. Device lists fall back to occurrence.
            return song.setVideoId?.takeIf { it.isNotBlank() }?.let { "entry:$it" }
                ?: "song:${song.videoId.length}:${song.videoId}:${songs.take(at).count { it.videoId == song.videoId }}"
        }
        PlaylistTransitionKey(playlistId, entry(index), entry(index + 1))
    }
    // Distinct saveable-state namespace per ordered pair, including after process recreation.
    androidx.compose.runtime.key(key) {
        var draft by rememberSaveable(key, stateSaver = TransitionDraftSaver) { mutableStateOf(PlaylistTransitionDraft().sanitized()) }
        var baseline by rememberSaveable(key, stateSaver = TransitionDraftSaver) { mutableStateOf(draft) }
        var loaded by rememberSaveable(key) { mutableStateOf(false) }
        var saving by remember(key) { mutableStateOf(false) }
        var saved by remember(key) { mutableStateOf(false) }
        var failed by remember(key) { mutableStateOf(false) }
        var selectedStem by remember(key) { mutableStateOf(TransitionStem.VOCALS) }
        var confirmClose by remember { mutableStateOf(false) }
        val dirty = draft != baseline
        val canEdit = loaded && !saving
        LaunchedEffect(key) {
            if (loaded) return@LaunchedEffect
            val stored = withContext(Dispatchers.IO) { store.read(key) }
            draft = stored ?: PlaylistTransitionDraft().sanitized()
            baseline = draft
            loaded = true
        }
        fun edit(value: PlaylistTransitionDraft) { draft = value.sanitized(); saved = false; failed = false }
        fun close() { if (saving) return else if (dirty) confirmClose = true else onDismiss() }
    
        Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.safeDrawingPadding().padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.transition_studio), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = ::close, enabled = !saving) { Text(stringResource(R.string.transition_close)) }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.transition_draft_badge), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(stringResource(R.string.transition_model_notice), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { selectedIndex = index - 1 }, enabled = canEdit && !dirty && index > 0) { Text(stringResource(R.string.transition_previous)) }
                            Text(stringResource(R.string.transition_pair_number, index + 1, songs.size - 1))
                            TextButton(onClick = { selectedIndex = index + 1 }, enabled = canEdit && !dirty && index < songs.size - 2) { Text(stringResource(R.string.transition_next)) }
                        }
                        SongSleeve(outgoing, stringResource(R.string.transition_outgoing), MaterialTheme.colorScheme.primary)
                        SongSleeve(incoming, stringResource(R.string.transition_incoming), MaterialTheme.colorScheme.tertiary)
                        Text(stringResource(R.string.transition_timing), style = MaterialTheme.typography.titleMedium)
                        SecondsControl(stringResource(R.string.transition_overlap), draft.overlapSeconds, 2f..12f, canEdit) { edit(draft.copy(overlapSeconds = it)) }
                        SecondsControl(stringResource(R.string.transition_trim), draft.outgoingTrimSeconds, 0f..6f, canEdit) { edit(draft.copy(outgoingTrimSeconds = it)) }
                        SecondsControl(stringResource(R.string.transition_cue), draft.incomingCueSeconds, 0f..30f, canEdit) { edit(draft.copy(incomingCueSeconds = it)) }
                        Text(stringResource(R.string.transition_layers), style = MaterialTheme.typography.titleMedium)
                        // Two columns fit narrow phones and large font settings better than a fixed tab strip.
                        TransitionStem.entries.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { stem ->
                                    FilterChip(selected = selectedStem == stem, enabled = canEdit, onClick = { selectedStem = stem }, label = { Text(stemLabel(stem)) })
                                }
                            }
                        }
                        val handoff = draft.handoff(selectedStem)
                        Text(stringResource(R.string.transition_design_curves), style = MaterialTheme.typography.labelMedium)
                        HandoffCurves(handoff)
                        Text(stringResource(R.string.transition_curve_legend), style = MaterialTheme.typography.bodySmall)
                        PercentControl(stringResource(R.string.transition_out_finish), handoff.outgoingUntil, 0.1f..1f, canEdit) {
                            edit(draft.withHandoff(selectedStem, handoff.copy(outgoingUntil = it)))
                        }
                        PercentControl(stringResource(R.string.transition_in_start), handoff.incomingFrom, 0f..0.9f, canEdit) {
                            edit(draft.withHandoff(selectedStem, handoff.copy(incomingFrom = it)))
                        }
                        Text(stringResource(R.string.transition_no_preview), style = MaterialTheme.typography.bodySmall)
                        if (dirty) Text(stringResource(R.string.transition_unsaved), color = MaterialTheme.colorScheme.primary)
                        if (saved) Text(stringResource(R.string.transition_saved), color = MaterialTheme.colorScheme.primary)
                        if (failed) Text(stringResource(R.string.transition_save_failed), color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { edit(PlaylistTransitionDraft()) }, enabled = canEdit, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.transition_reset))
                        }
                        Button(onClick = {
                            saving = true
                            val snapshot = draft
                            val savingKey = key
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { runCatching { store.save(savingKey, snapshot) }.getOrDefault(false) }
                                saving = false
                                saved = ok
                                failed = !ok
                                if (ok) baseline = snapshot
                            }
                        }, enabled = canEdit && dirty, modifier = Modifier.weight(1f)) {
                            Text(stringResource(if (saving) R.string.transition_saving else R.string.transition_save_draft))
                        }
                    }
                }
            }
        }
        if (confirmClose) AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(stringResource(R.string.transition_discard_title)) },
            text = { Text(stringResource(R.string.transition_discard_body)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.transition_discard)) } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.transition_keep_editing)) } },
        )
    }
}

@Composable
private fun SongSleeve(song: Song, label: String, accent: Color) {
    Surface(shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = 0.1f)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = song.thumbnailUrl, contentDescription = null, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f)) {
                Text(label, color = accent, style = MaterialTheme.typography.labelMedium)
                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SecondsControl(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    Column {
        Text(stringResource(R.string.transition_seconds_value, label, (value * 10).roundToInt() / 10f))
        Slider(value = value, onValueChange = { onChange((it * 10).roundToInt() / 10f) }, valueRange = range, enabled = enabled,
            modifier = Modifier.semanticsLabel(label))
    }
}

@Composable
private fun PercentControl(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    Column {
        Text(stringResource(R.string.transition_percent_value, label, (value * 100).roundToInt()))
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled, modifier = Modifier.semanticsLabel(label))
    }
}

private fun Modifier.semanticsLabel(label: String): Modifier = this.then(Modifier.semantics { contentDescription = label })

@Composable
private fun stemLabel(stem: TransitionStem): String = stringResource(when (stem) {
    TransitionStem.VOCALS -> R.string.transition_vocals
    TransitionStem.DRUMS -> R.string.transition_drums
    TransitionStem.BASS -> R.string.transition_bass
    TransitionStem.OTHER -> R.string.transition_other
})

@Composable
private fun HandoffCurves(handoff: StemHandoff) {
    val outColor = MaterialTheme.colorScheme.primary
    val inColor = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        val pad = 10.dp.toPx()
        val w = (size.width - pad * 2).coerceAtLeast(0f)
        val h = (size.height - pad * 2).coerceAtLeast(0f)
        for (i in 0..4) {
            val x = pad + w * i / 4f
            drawLine(grid, Offset(x, pad), Offset(x, pad + h))
        }
        for (incoming in listOf(false, true)) {
            val path = Path()
            for (i in 0..64) {
                val p = i / 64f
                val gains = handoff.gains(p)
                val y = pad + h * (1f - if (incoming) gains.second else gains.first)
                val x = pad + w * p
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, if (incoming) inColor else outColor, style = Stroke(width = 3.dp.toPx()))
        }
    }
}

// Preserve unsaved designs and their comparison baseline across activity recreation.
private val TransitionDraftSaver = listSaver<PlaylistTransitionDraft, Float>(
    save = { value ->
        val d = value.sanitized()
        listOf(d.overlapSeconds, d.outgoingTrimSeconds, d.incomingCueSeconds) +
            TransitionStem.entries.flatMap { listOf(d.handoff(it).outgoingUntil, d.handoff(it).incomingFrom) }
    },
    restore = { values ->
        if (values.size != 11) null else PlaylistTransitionDraft(
            values[0], values[1], values[2],
            TransitionStem.entries.mapIndexed { i, stem -> stem to StemHandoff(values[3 + 2 * i], values[4 + 2 * i]) }.toMap(),
        ).sanitized()
    },
)
