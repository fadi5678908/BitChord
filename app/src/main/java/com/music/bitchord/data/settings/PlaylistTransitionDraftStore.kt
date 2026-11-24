package com.music.bitchord.data.settings

import android.content.Context
import com.music.bitchord.playback.smart.PlaylistTransitionDraft
import com.music.bitchord.playback.smart.PlaylistTransitionKey
import com.music.bitchord.playback.smart.StemHandoff
import com.music.bitchord.playback.smart.TransitionStem
import org.json.JSONObject
import java.security.MessageDigest

/** Private, device-local drafts. No playback overrides and no audio processing are scheduled here. */
class PlaylistTransitionDraftStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("playlist_transition_drafts_v1", Context.MODE_PRIVATE)

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
        val encoded = JSONObject().put("version", 1).put("playlist", key.playlistId)
            .put("outgoing", key.outgoingEntry).put("incoming", key.incomingEntry)
            .put("overlap", d.overlapSeconds).put("trim", d.outgoingTrimSeconds)
            .put("cue", d.incomingCueSeconds).put("stems", layers).toString()
        return prefs.edit().putString(storageKey(key), encoded).commit()
    }

    private fun storageKey(key: PlaylistTransitionKey): String {
        // Length framing prevents collisions when IDs contain punctuation/separators.
        val framed = listOf(key.playlistId, key.outgoingEntry, key.incomingEntry).joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(framed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
