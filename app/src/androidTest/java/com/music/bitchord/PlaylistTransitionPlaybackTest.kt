package com.music.bitchord

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.CrossfadeController
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.smart.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/** Real two-player scheduling and handoff, not just planner arithmetic. No network or ML analysis. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaylistTransitionPlaybackTest {
    @Test fun everyPlaylistModeHandsOffWithGlobalAutomixAndCrossfadeOff() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "playlist-transition-fixture.wav")
        file.outputStream().buffered().use { out ->
            fun little(value: Int, bytes: Int) { repeat(bytes) { out.write(value ushr (8 * it) and 255) } }
            val frames = 44100 * 12
            out.write("RIFF".toByteArray()); little(36 + frames * 4, 4); out.write("WAVEfmt ".toByteArray())
            little(16,4); little(1,2); little(2,2); little(44100,4); little(176400,4); little(4,2); little(16,2)
            out.write("data".toByteArray()); little(frames*4,4)
            for (i in 0 until frames) repeat(2) { little((3000 * sin(i * .06)).toInt(),2) }
        }
        try {
            withContext(Dispatchers.Main) {
                val oldSmart = AppSettings.smartFadeEnabled.value
                val oldCrossfade = AppSettings.crossfadeSeconds.value
                val oldSpeed = AppSettings.playbackSpeed.value
                AppSettings.setSmartFadeEnabled(false)
                AppSettings.setCrossfadeSeconds(0)
                AppSettings.setPlaybackSpeed(1f)
                try {
                    for (mode in PlaylistTransitionMode.entries) {
                        var active = ExoPlayer.Builder(context).build()
                        var spare = ExoPlayer.Builder(context).build()
                        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                        var handoffs = 0
                        var analysisRequests = 0
                        var settled = false
                        val draft = PlaylistTransitionDraft(enabled = true, mode = mode, overlapSeconds = 3f,
                            outgoingStartSeconds = 5f, incomingStartSeconds = 1f)
                        val songs = listOf("first", "second", "third").map { id ->
                            Song(id, id, "Test", null, durationText = "0:12", localUri = file.toURI().toString(),
                                playbackSourceType = PlaybackSourceType.PLAYLIST, playbackSourceId = "fixture")
                        }
                        val controller = CrossfadeController(scope, active = { active }, standby = { spare },
                            onHandoff = { out, into ->
                                assertEquals("first", out.currentMediaItem?.mediaId)
                                assertEquals("second", into.currentMediaItem?.mediaId)
                                handoffs++
                                active = into; spare = out
                            },
                            customTransitionFor = { out, into ->
                                draft.takeIf { out.mediaId == "first" && into.mediaId == "second" }
                            },
                            requestAnalysis = { _, _ -> analysisRequests++ },
                            onSettled = { settled = true })
                        try {
                            active.setMediaItems(songs.map { it.toMediaItem() }, 0, 3000)
                            active.prepare()
                            controller.start()
                            active.play()
                            withTimeout(25_000) { while (!settled) delay(50) }
                            assertEquals("Handoff for $mode", 1, handoffs)
                            assertEquals("No retired model scheduling for $mode", 0, analysisRequests)
                            assertEquals("second", active.currentMediaItem?.mediaId)
                            assertEquals(1, active.currentMediaItemIndex)
                            assertEquals(3, active.mediaItemCount)
                            assertTrue("Incoming audio continues for $mode", active.currentPosition >= 2000)
                            assertEquals(1f, active.volume, .001f)
                            assertEquals(0, spare.mediaItemCount)
                            assertFalse(AppSettings.smartMixInProgress.value)
                        } finally {
                            controller.release(); scope.cancel(); active.release(); spare.release()
                        }
                    }
                } finally {
                    AppSettings.setSmartFadeEnabled(oldSmart)
                    AppSettings.setCrossfadeSeconds(oldCrossfade)
                    AppSettings.setPlaybackSpeed(oldSpeed)
                }
            }
        } finally { file.delete() }
    }
    @Test fun playlistModeAndStemSwitchPersistWithoutEnablingLegacyDrafts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = com.music.bitchord.data.settings.PlaylistTransitionDraftStore.get(context)
        val key = PlaylistTransitionKey("mode-roundtrip-fixture", "entry:a", "entry:b")
        val prefs = context.getSharedPreferences("playlist_transition_drafts_v1", android.content.Context.MODE_PRIVATE)
        val framed = listOf(key.playlistId, key.outgoingEntry, key.incomingEntry).joinToString("") { "${it.length}:$it" }
        val diskKey = java.security.MessageDigest.getInstance("SHA-256").digest(framed.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        try {
            for (mode in PlaylistTransitionMode.entries) {
                val draft = PlaylistTransitionDraft(enabled = true, mode = mode, separateVocals = true).sanitized()
                assertTrue(store.save(key, draft))
                assertEquals(draft, store.read(key))
                assertEquals(draft, store.snapshot(key))
            }
            // Same v1 preferences existed before mode/stem fields. Preserve their custom timings and disabled state.
            assertTrue(store.save(key, PlaylistTransitionDraft(outgoingStartSeconds = 15f)))
            val legacy = org.json.JSONObject(prefs.getString(diskKey, null)!!)
            legacy.remove("mode"); legacy.remove("separateVocals")
            assertTrue(prefs.edit().putString(diskKey, legacy.toString()).commit())
            val restored = store.read(key)!!
            assertEquals(PlaylistTransitionMode.CUSTOM, restored.mode)
            assertEquals(15f, restored.outgoingStartSeconds)
            assertFalse(restored.enabled)
            assertFalse(restored.separateVocals)
        } finally { prefs.edit().remove(diskKey).commit() }
    }

}
