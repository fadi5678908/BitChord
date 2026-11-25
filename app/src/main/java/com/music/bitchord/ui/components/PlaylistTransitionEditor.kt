package com.music.bitchord.ui.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.PlaylistTransitionDraftStore
import com.music.bitchord.playback.smart.*
import com.music.bitchord.playback.rememberMediaController
import com.music.bitchord.playback.StudioTransport
import com.music.bitchord.playback.studioPlay
import com.music.bitchord.playback.studioStop
import androidx.media3.session.MediaController
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Source waveforms, audible timing and master-volume automation; no pretend stem controls. */
@Composable
fun PlaylistTransitionEditor(playlistId: String, songs: List<Song>, onDismiss: () -> Unit) {
    if (playlistId.isBlank() || songs.size < 2) return
    val context = LocalContext.current
    val store = remember(context) { PlaylistTransitionDraftStore.get(context) }
    val scope = rememberCoroutineScope()
    val controller = rememberMediaController()
    val transport by StudioTransport.state.collectAsState()
    var selected by rememberSaveable(playlistId, songs) { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, songs.size - 2)
    val outgoing = songs[index]
    val incoming = songs[index + 1]
    val outLength = outgoing.durationMillis() / 1000f
    val inLength = incoming.durationMillis() / 1000f
    val key = remember(playlistId, outgoing, incoming) {
        PlaylistTransitionKey(playlistId, playlistTransitionEntry(outgoing.videoId, outgoing.setVideoId), playlistTransitionEntry(incoming.videoId, incoming.setVideoId))
    }
    val unambiguous = listOf(outgoing, incoming).all { song ->
        !song.setVideoId.isNullOrBlank() || songs.count { it.videoId == song.videoId } == 1
    }
    androidx.compose.runtime.key(key) {
        val owner = remember { UUID.randomUUID().toString() }
        val preview = transport?.takeIf { it.owner == owner }
        var previewFailed by remember { mutableStateOf(false) }
        var previewStarting by remember { mutableStateOf(false) }
        DisposableEffect(controller, owner) {
            onDispose { controller?.studioStop(owner) }
        }
        var draft by rememberSaveable(key, stateSaver = TimingSaver) { mutableStateOf(PlaylistTransitionDraft(enabled = true).sanitized()) }
        var baseline by rememberSaveable(key, stateSaver = TimingSaver) { mutableStateOf(draft) }
        var loaded by rememberSaveable(key) { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        var saved by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var confirmClose by remember { mutableStateOf(false) }
        val dirty = draft != baseline
        val valid = !draft.enabled || draft.windows(outLength.toDouble(), inLength.toDouble()) != null
        val previewRunning = preview?.running == true || previewStarting
        val editable = loaded && !saving && !previewRunning
        LaunchedEffect(key) {
            if (!loaded) {
                val stored = withContext(Dispatchers.IO) { store.read(key) }
                draft = stored ?: PlaylistTransitionDraft(enabled = true).sanitized()
                baseline = draft
                saved = stored?.enabled == true
                loaded = true
            }
        }
        fun edit(next: PlaylistTransitionDraft) { draft = next.sanitized(); saved = false; failed = false }
        fun close() { if (!saving) { if (dirty) confirmClose = true else onDismiss() } }
        Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.safeDrawingPadding().padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.transition_studio), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = ::close, enabled = !saving) { Text(stringResource(R.string.transition_close)) }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { selected = index - 1 }, enabled = editable && !dirty && index > 0) { Text(stringResource(R.string.transition_previous)) }
                            Text(stringResource(R.string.transition_pair_number, index + 1, songs.size - 1))
                            TextButton(onClick = { selected = index + 1 }, enabled = editable && !dirty && index < songs.size - 2) { Text(stringResource(R.string.transition_next)) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.transition_enable), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.transition_scope), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = draft.enabled, onCheckedChange = { edit(draft.copy(enabled = it)) }, enabled = editable,
                                modifier = Modifier.semantics { contentDescription = context.getString(R.string.transition_enable) })
                        }
                        val outMax = (outLength - draft.overlapSeconds).coerceAtLeast(0f)
                        val inMax = (inLength - draft.overlapSeconds).coerceAtLeast(0f)
                        val outStart = draft.outgoingStartSeconds ?: (outMax - draft.outgoingTrimSeconds).coerceAtLeast(0f)
                        val inStart = draft.incomingStartSeconds ?: draft.incomingCueSeconds
                        TrackTiming(outgoing, stringResource(R.string.transition_exit_point), outStart, outLength, draft.overlapSeconds,
                            Color(0xFFFFA348), editable && outMax > 0f, outMax, controller, true, draft.volumeHandoff,
                            preview?.takeIf { it.running }?.elapsed, { edit(draft.copy(volumeHandoff = draft.volumeHandoff.copy(outgoingUntil = it))) }) { edit(draft.copy(outgoingStartSeconds = it)) }
                        TrackTiming(incoming, stringResource(R.string.transition_entry_point), inStart, inLength, draft.overlapSeconds,
                            Color(0xFF58A7FF), editable && inMax > 0f, inMax, controller, false, draft.volumeHandoff,
                            preview?.takeIf { it.running }?.elapsed, { edit(draft.copy(volumeHandoff = draft.volumeHandoff.copy(incomingFrom = it))) }) { edit(draft.copy(incomingStartSeconds = it)) }
                        Column {
                            Text(stringResource(R.string.transition_seconds_value, stringResource(R.string.transition_overlap), draft.overlapSeconds))
                            Slider(value = draft.overlapSeconds, onValueChange = {
                                val overlap = (it * 10).roundToInt() / 10f
                                edit(draft.copy(overlapSeconds = overlap,
                                    outgoingStartSeconds = outStart.coerceAtMost((outLength - overlap).coerceAtLeast(0f)),
                                    incomingStartSeconds = inStart.coerceAtMost((inLength - overlap).coerceAtLeast(0f))))
                            }, valueRange = 2f..12f, enabled = editable,
                                modifier = Modifier.semantics { contentDescription = context.getString(R.string.transition_overlap) })
                        }
                        Text(stringResource(R.string.transition_timing_help), style = MaterialTheme.typography.bodySmall)
                        if (!valid || !unambiguous) Text(stringResource(R.string.transition_invalid_timing), color = MaterialTheme.colorScheme.error)
                        if (dirty) Text(stringResource(R.string.transition_unsaved), color = MaterialTheme.colorScheme.primary)
                        if (saved) Text(stringResource(R.string.transition_applied), color = MaterialTheme.colorScheme.primary)
                        if (failed) Text(stringResource(R.string.transition_save_failed), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { edit(PlaylistTransitionDraft(enabled = true)) }, enabled = editable) { Text(stringResource(R.string.transition_reset)) }
                    }
                    Button(onClick = {
                        saving = true
                        val snapshot = draft
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { store.save(key, snapshot) }.getOrDefault(false) }
                            saving = false; failed = !ok; saved = ok
                            if (ok) baseline = snapshot
                        }
                    }, enabled = editable && valid && unambiguous, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text(stringResource(if (saving) R.string.transition_saving else R.string.transition_apply))
                    }
                    if (previewFailed || preview?.error == true) Text(stringResource(R.string.transition_preview_failed), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.transition_preview_notice), style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = {
                        if (previewRunning) controller?.studioStop(owner)
                        else scope.launch {
                            previewStarting = true
                            previewFailed = false
                            try { previewFailed = controller?.studioPlay(owner, outgoing, incoming, draft) != true }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { previewFailed = true }
                            finally { previewStarting = false }
                        }
                    }, enabled = controller != null && loaded && valid && draft.enabled && !saving && !previewStarting,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Text(stringResource(if (previewRunning) R.string.transition_stop_preview else R.string.transition_preview))
                    }
                }
            }
        }
        if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false },
            title = { Text(stringResource(R.string.transition_discard_title)) },
            text = { Text(stringResource(R.string.transition_discard_body)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.transition_discard)) } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.transition_keep_editing)) } })
    }
}

@Composable
private fun TrackTiming(song: Song, label: String, start: Float, length: Float, overlap: Float, accent: Color, enabled: Boolean, maximum: Float,
    controller: MediaController?, outgoing: Boolean, handoff: StemHandoff, elapsed: Float?, onEnvelope: (Float) -> Unit, onChange: (Float) -> Unit) {
    var fine by remember(song) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(model = song.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.15f)))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            Text(label, color = accent, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(start))
                Text(formatTime(length), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StudioWaveformTrack(controller, song, start, length, overlap, outgoing, handoff, accent, enabled,
                elapsed, onChange, onEnvelope)
            TextButton(onClick = { fine = !fine }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (fine) R.string.transition_hide_fine else R.string.transition_fine))
            }
            if (fine) {
                val envelopeLabel = stringResource(if (outgoing) R.string.transition_master_out else R.string.transition_master_in)
                Text(envelopeLabel, style = MaterialTheme.typography.labelMedium)
                Slider(value = if (outgoing) handoff.outgoingUntil else handoff.incomingFrom,
                    onValueChange = onEnvelope, enabled = enabled,
                    valueRange = if (outgoing) 0.1f..1f else 0f..0.9f,
                    modifier = Modifier.semantics { contentDescription = envelopeLabel })
            val safe = start.coerceIn(0f, maximum.coerceAtLeast(0f))
            Slider(value = safe, onValueChange = { onChange((it * 10).roundToInt() / 10f) },
                valueRange = 0f..maximum.coerceAtLeast(0.1f), enabled = enabled,
                modifier = Modifier.semantics { contentDescription = label })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onChange((safe - 1).coerceAtLeast(0f)) }, enabled = enabled) { Text("−1 " + stringResource(R.string.transition_second_unit)) }
                TextButton(onClick = { onChange((safe + 1).coerceAtMost(maximum)) }, enabled = enabled) { Text("+1 " + stringResource(R.string.transition_second_unit)) }
            }
            }
        }
    }
}

private fun formatTime(value: Float): String {
    val tenths = (value.coerceAtLeast(0f) * 10).roundToInt()
    return "${tenths / 600}:${((tenths / 10) % 60).toString().padStart(2, '0')}.${tenths % 10}"
}

private val TimingSaver = listSaver<PlaylistTransitionDraft, Float>(save = {
    listOf(it.overlapSeconds, it.outgoingTrimSeconds, it.incomingCueSeconds,
        it.outgoingStartSeconds ?: -1f, it.incomingStartSeconds ?: -1f, if (it.enabled) 1f else 0f, it.volumeHandoff.outgoingUntil, it.volumeHandoff.incomingFrom)
}, restore = {
    if (it.size != 6 && it.size != 8) null else PlaylistTransitionDraft(it[0], it[1], it[2],
        outgoingStartSeconds = it[3].takeIf { v -> v >= 0 }, incomingStartSeconds = it[4].takeIf { v -> v >= 0 }, enabled = it[5] == 1f,
        volumeHandoff = StemHandoff(it.getOrElse(6) { 1f }, it.getOrElse(7) { 0f })).sanitized()
})
