package com.music.bitchord.playback.smart

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.toMediaItem
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

/** One local playlist-pair audition in memory. No remote processing or source substitution. */
internal class LocalStemStudio(private val context: Context, private val analyzer: TrackAnalyzer) {
    internal data class Key(val outUri: String, val inUri: String, val out: Double, val inn: Double, val overlap: Double)
    internal data class Prepared(val key: Key, val out: VocalTracker.Separated, val inn: VocalTracker.Separated, val lead: Double)
    private var cached: Prepared? = null // only accessed under the service's decoder mutex
    private val folder get() = File(context.cacheDir, "local_stem_audition").apply { mkdirs() }

    fun prepare(outgoing: Song, incoming: Song, draft: PlaylistTransitionDraft,
               abort: () -> Boolean, progress: (String) -> Unit = {}): Prepared {
        check(!abort())
        if (Build.VERSION.SDK_INT >= 29) {
            check(context.getSystemService(PowerManager::class.java).currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE) { "Device is too hot" }
        }
        val outUri = requireNotNull(outgoing.toMediaItem().localConfiguration?.uri)
        val inUri = requireNotNull(incoming.toMediaItem().localConfiguration?.uri)
        require(LocalAudioSource.isLocal(outUri) && LocalAudioSource.isLocal(inUri)) { "Local or downloaded files required" }
        fun duration(uri: android.net.Uri) = LocalAudioSource.open(context.contentResolver, uri)?.use(AudioDecoder::containerDurationSeconds) ?: error("Cannot read duration")
        val window = draft.windows(duration(outUri), duration(inUri)) ?: error("Invalid source windows")
        val key = Key(outUri.toString(), inUri.toString(), window.outStart, window.inStart, draft.overlapSeconds.toDouble())
        val lead = minOf(2.0, window.outStart)
        val prepared = cached?.takeIf { it.key == key } ?: run {
            cached = null
            fun separate(uri: android.net.Uri, from: Double, to: Double): VocalTracker.Separated {
                check(!abort())
                val decoded = LocalAudioSource.open(context.contentResolver, uri)?.use {
                    AudioDecoder.decodeRegionStereo(it, from, to, abort)
                } ?: error("Cannot decode local region")
                val pcm = decoded.first
                val first = ((from - decoded.second) * pcm.sampleRate).roundToInt().coerceAtLeast(0)
                val count = ((to - from) * pcm.sampleRate).roundToInt()
                require(first >= 0 && first + count <= pcm.left.size + 1 && first + count <= pcm.right.size + 1) { "Decoder did not cover selected region" }
                check(!abort())
                return analyzer.separateLocal(pcm.left.copyOfRange(first, minOf(first + count, pcm.left.size)).copyOf(count), pcm.right.copyOfRange(first, minOf(first + count, pcm.right.size)).copyOf(count), pcm.sampleRate, abort)
                    ?: error("Separation model failed")
            }
            progress("outgoing")
            val out = separate(outUri, window.outStart - lead, window.outEnd)
            progress("incoming")
            val inn = separate(inUri, window.inStart, minOf(window.inEnd + 2, duration(inUri)))
            Prepared(key, out, inn, lead).also { cached = it }
        }
        check(!abort())
        return prepared
    }

    fun render(outgoing: Song, incoming: Song, draft: PlaylistTransitionDraft, mode: LocalStemAudition,
               abort: () -> Boolean, progress: (String) -> Unit): Pair<File, Float> {
        val prepared = prepare(outgoing, incoming, draft, abort, progress)
        check(!abort())
        progress("render")
        // One output file retained. Never leave partially rendered WAVs discoverable by playback.
        folder.listFiles()?.forEach { it.delete() }
        val temporary = File(folder, "${UUID.randomUUID()}.partial")
        val file = File(folder, "${UUID.randomUUID()}.wav")
        val rate = 44100
        val tail = (prepared.inn.frames.toDouble() / rate - draft.overlapSeconds).coerceIn(0.0, 2.0)
        val frames = ((prepared.lead + draft.overlapSeconds + tail) * rate).roundToInt()
        try {
            FileOutputStream(temporary).buffered().use { output ->
                fun little(value: Int, bytes: Int) { repeat(bytes) { output.write(value ushr (it * 8) and 255) } }
                output.write("RIFF".toByteArray()); little(36 + frames * 4, 4); output.write("WAVEfmt ".toByteArray())
                little(16, 4); little(1, 2); little(2, 2); little(rate, 4); little(rate * 4, 4); little(4, 2); little(16, 2)
                output.write("data".toByteArray()); little(frames * 4, 4)
                val preFrames = (prepared.lead * rate).roundToInt()
                for (i in 0 until frames) {
                    if (i % 4096 == 0) check(!abort())
                    val inFrame = i - preFrames
                    val p = inFrame.toFloat() / (draft.overlapSeconds * rate)
                    val g = vocalRhythmGains(p, draft.volumeHandoff, mode)
                    for (c in 0..1) {
                        fun value(stem: VocalTracker.Separated, index: Int, vocal: Float, music: Float): Float {
                            if (index !in 0 until stem.frames) return 0f
                            return stem.vocal[index * 2 + c] * vocal + stem.instrument(index, c) * music
                        }
                        val sample = value(prepared.out, i, g.outVocal, g.outMusic) + value(prepared.inn, inFrame, g.inVocal, g.inMusic)
                        // Smooth, bounded summation. No normalization guessed from vocal-presence scores.
                        val level = maxOf(1f, g.outMusic + g.inVocal)
                        val safe = if (sample.isFinite()) (sample / level).coerceIn(-1f, 1f) else 0f
                        little((safe * 32767).roundToInt(), 2)
                    }
                }
            }
            check(!abort() && temporary.renameTo(file))
            return file to prepared.lead.toFloat()
        } finally { temporary.delete() }
    }

    fun clear() { cached = null; folder.listFiles()?.forEach { it.delete() } }
}
