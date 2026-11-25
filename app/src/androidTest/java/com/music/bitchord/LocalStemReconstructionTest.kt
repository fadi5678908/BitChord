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
