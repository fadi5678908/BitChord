package com.music.bitchord

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.bitchord.playback.smart.VocalTracker
import java.io.File
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Signal-path smoke test, not a perceptual music-separation benchmark. Runs the bundled model. */
@RunWith(AndroidJUnit4::class)
class LocalStemReconstructionTest {
    @Test fun localClipsDecodeSeparateAndRenderWithoutChangingOriginalFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val analyzer = com.music.bitchord.playback.smart.TrackAnalyzer(context, com.music.bitchord.playback.AudioCache)
        val studio = com.music.bitchord.playback.smart.LocalStemStudio(context, analyzer)
        val sources = (0..1).map { channel ->
            File(context.cacheDir, "stem-source-$channel.wav").apply {
                outputStream().buffered().use { output ->
                    fun little(value: Int, bytes: Int) { repeat(bytes) { output.write(value ushr (8*it) and 255) } }
                    val frames = 44100 * 4
                    output.write("RIFF".toByteArray()); little(36+frames*4,4); output.write("WAVEfmt ".toByteArray())
                    little(16,4); little(1,2); little(2,2); little(44100,4); little(176400,4); little(4,2); little(16,2)
                    output.write("data".toByteArray()); little(frames*4,4)
                    for(i in 0 until frames) repeat(2) { little((6000*sin(i*(0.04+channel*.01))).toInt(),2) }
                }
            }
        }
        val sizes = sources.map { it.length() }
        try {
            val songs = sources.map { file -> com.music.bitchord.data.model.Song(file.toURI().toString(), "Local fixture", "Test", null,
                durationText = "0:04", localUri = file.toURI().toString()) }
            val draft = com.music.bitchord.playback.smart.PlaylistTransitionDraft(overlapSeconds=2f,
                outgoingStartSeconds=1f,incomingStartSeconds=.5f,enabled=true)
            for(mode in com.music.bitchord.playback.smart.LocalStemAudition.entries) {
                val (wave, lead) = studio.render(songs[0],songs[1],draft,mode,{false},{})
                assertEquals(1f,lead)
                assertTrue(wave.length()>44)
                val header = ByteArray(12).also { bytes -> java.io.DataInputStream(wave.inputStream()).use { it.readFully(bytes) } }
                assertEquals("RIFF", String(header,0,4))
                assertEquals("WAVE", String(header,8,4))
            }
            assertEquals(sizes,sources.map { it.length() })
        } finally { studio.clear(); analyzer.release(); sources.forEach { it.delete() } }
    }

    @Test fun bundledModelProducesReconstructableStereoSources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.cacheDir, "test-vocals.onnx")
        context.assets.open(VocalTracker.MODEL_ASSET).use { input -> model.outputStream().use { input.copyTo(it) } }
        val tracker = VocalTracker({ model.absolutePath }, { 2 })
        try {
            val left = FloatArray(44100) { (0.25 * sin(it * 0.061) + 0.12 * sin(it * 0.137)).toFloat() }
            val right = FloatArray(44100) { left[it] * 0.8f }
            val separated = tracker.separate(left, right, 44100.0)
            assertNotNull("Bundled model or JNI reconstruction failed", separated)
            val stems = separated!!
            assertEquals(44100, stems.frames)
            for (i in left.indices) for (c in 0..1) {
                val vocal = stems.vocal[i*2+c]
                assertTrue(vocal.isFinite())
                assertEquals(stems.mixture(i,c), vocal + stems.instrument(i,c), 0.00001f)
            }
            assertNull(tracker.separate(left,right,44100.0) { true })
        } finally { tracker.release(); model.delete() }
    }
}
