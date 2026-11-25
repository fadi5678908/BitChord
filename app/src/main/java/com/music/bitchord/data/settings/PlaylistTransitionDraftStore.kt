package com.music.bitchord.data.settings

import android.content.Context
import com.music.bitchord.playback.smart.PlaylistTransitionDraft
import com.music.bitchord.playback.smart.PlaylistTransitionKey
import com.music.bitchord.playback.smart.StemHandoff
import com.music.bitchord.playback.smart.TransitionStem
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

/** Device-local playlist timing. Playback reads an in-memory snapshot, never preferences on each tick. */
class PlaylistTransitionDraftStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("playlist_transition_drafts_v1", Context.MODE_PRIVATE)

    private val snapshots = ConcurrentHashMap<PlaylistTransitionKey, PlaylistTransitionDraft>()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            prefs.all.values.filterIsInstance<String>().forEach { encoded ->
                runCatching {
                    val root = JSONObject(encoded)
                    val key = PlaylistTransitionKey(root.getString("playlist"), root.getString("outgoing"), root.getString("incoming"))
                    read(key)?.let { snapshots.putIfAbsent(key, it) }
                }
            }
        }
    }

    fun active(key: PlaylistTransitionKey): PlaylistTransitionDraft? = snapshots[key]?.takeIf { it.enabled }

    fun read(key: PlaylistTransitionKey): PlaylistTransitionDraft? {
        if (!key.isValid) return null
        val encoded = prefs.getString(storageKey(key), null) ?: return null
        return runCatching {
            val root = JSONObject(encoded)
            require(root.getInt("version") == 1)
            require(root.getString("playlist") == key.playlistId)
            require(root.getString("outgoing") == key.outgoingEntry)
            require(root.getString("incoming") == key.incomingEntry)
            val layers = root.getJSONObject("stems")
            PlaylistTransitionDraft(
                overlapSeconds = root.getDouble("overlap").toFloat(),
                outgoingTrimSeconds = root.getDouble("trim").toFloat(),
                incomingCueSeconds = root.getDouble("cue").toFloat(),
                enabled = root.optBoolean("enabled", false),
                outgoingStartSeconds = if (root.has("outStart")) root.getDouble("outStart").toFloat() else null,
                incomingStartSeconds = if (root.has("inStart")) root.getDouble("inStart").toFloat() else null,
                stems = TransitionStem.entries.associateWith { stem ->
                    val layer = layers.getJSONObject(stem.name)
                    StemHandoff(layer.getDouble("until").toFloat(), layer.getDouble("from").toFloat())
                },
            ).sanitized()
        }.getOrNull()
    }

    /** Called off the main thread by the editor; only report success once the disk write succeeds. */
    fun save(key: PlaylistTransitionKey, draft: PlaylistTransitionDraft): Boolean {
        if (!key.isValid) return false
        val d = draft.sanitized()
        val layers = JSONObject()
        TransitionStem.entries.forEach { stem ->
            val handoff = d.handoff(stem)
            layers.put(stem.name, JSONObject().put("until", handoff.outgoingUntil).put("from", handoff.incomingFrom))
        }
        val encoded = JSONObject().put("enabled", d.enabled)
            .apply {
                d.outgoingStartSeconds?.let { put("outStart", it) }
                d.incomingStartSeconds?.let { put("inStart", it) }
            }.put("version", 1).put("playlist", key.playlistId)
            .put("outgoing", key.outgoingEntry).put("incoming", key.incomingEntry)
            .put("overlap", d.overlapSeconds).put("trim", d.outgoingTrimSeconds)
            .put("cue", d.incomingCueSeconds).put("stems", layers).toString()
        val saved = prefs.edit().putString(storageKey(key), encoded).commit()
        if (saved) snapshots[key] = d
        return saved
    }

    companion object {
        @Volatile private var instance: PlaylistTransitionDraftStore? = null
        fun get(context: Context): PlaylistTransitionDraftStore = instance ?: synchronized(this) {
            instance ?: PlaylistTransitionDraftStore(context.applicationContext).also { instance = it }
        }
    }

    private fun storageKey(key: PlaylistTransitionKey): String {
        // Length framing prevents collisions when IDs contain punctuation/separators.
        val framed = listOf(key.playlistId, key.outgoingEntry, key.incomingEntry).joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(framed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
