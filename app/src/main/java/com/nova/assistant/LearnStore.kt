package com.nova.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v16 "KYA SEEKHA": notes NOVA learned from the web. Stored only in the app's private folder (learned.json), never uploaded.
 * A new note is PENDING: it is not used in any answer until the owner presses RAKHO in Settings. HATAO deletes it for good.
 * Evidence level: written + statically checked. Not compiled here, not phone tested.
 */
object LearnStore {
    private const val FILE = "learned.json"
    private const val MAX = 200

    class Note(val id: String, val topic: String, val text: String, val ts: Long, val src: String, val kept: Boolean)

    @Synchronized private fun load(ctx: Context): MutableList<Note> {
        val out = ArrayList<Note>()
        try {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return out
            val a = JSONArray(f.readText(Charsets.UTF_8))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val text = o.optString("text")
                if (id.isEmpty() || text.isEmpty()) continue
                out.add(Note(id, o.optString("topic"), text, o.optLong("ts"), o.optString("src"), o.optBoolean("kept")))
            }
        } catch (e: Exception) { }
        return out
    }

    @Synchronized private fun save(ctx: Context, list: List<Note>) {
        try {
            val a = JSONArray()
            for (n in list.takeLast(MAX)) {
                a.put(JSONObject().put("id", n.id).put("topic", n.topic).put("text", n.text).put("ts", n.ts).put("src", n.src).put("kept", n.kept))
            }
            File(ctx.filesDir, FILE).writeText(a.toString(), Charsets.UTF_8)
        } catch (e: Exception) { }
    }

    @Synchronized fun addPending(ctx: Context, topic: String, text: String, src: String): String {
        val list = load(ctx)
        val id = System.currentTimeMillis().toString()
        list.add(Note(id, topic, text, System.currentTimeMillis(), src, false))
        save(ctx, list)
        return id
    }

    @Synchronized fun keep(ctx: Context, id: String): Boolean {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        val n = list[i]
        list[i] = Note(n.id, n.topic, n.text, n.ts, n.src, true)
        save(ctx, list)
        return true
    }

    @Synchronized fun delete(ctx: Context, id: String): Boolean {
        val list = load(ctx)
        val keep = list.filter { it.id != id }
        if (keep.size == list.size) return false
        save(ctx, keep)
        return true
    }

    @Synchronized fun clear(ctx: Context) {
        try { File(ctx.filesDir, FILE).delete() } catch (e: Exception) { }
    }

    /** JSON for the Settings screen, newest first. */
    @Synchronized fun listJson(ctx: Context): String {
        val a = JSONArray()
        for (n in load(ctx).reversed()) {
            a.put(JSONObject().put("id", n.id).put("topic", n.topic).put("text", n.text).put("ts", n.ts).put("src", n.src).put("kept", n.kept))
        }
        return a.toString()
    }

    /** Best KEPT note for a spoken question, or null. Pending notes are never used. */
    fun answer(ctx: Context, said: String): String? {
        var best: Note? = null
        var bestScore = 0.0
        for (n in load(ctx)) {
            if (!n.kept) continue
            val s = LearnRules.score(said, n.topic)
            if (s > bestScore) { bestScore = s; best = n }
        }
        val b = best ?: return null
        return if (bestScore >= LearnRules.MIN_SCORE) b.text else null
    }
}
