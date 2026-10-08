package com.nova.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phone-local storage for the Layer-1 brain (shortcuts, event log). One small JSON file in the app's private
 * folder: nothing here is uploaded anywhere. Thread-safe. A corrupt or missing file simply starts empty.
 */
class BrainStore private constructor(ctx: Context) {

    companion object {
        @Volatile private var inst: BrainStore? = null

        /** One shared instance for the service and the app screen, so they never disagree. */
        fun get(ctx: Context): BrainStore {
            val i = inst
            if (i != null) return i
            return synchronized(this) {
                val j = inst
                if (j != null) j else { val n = BrainStore(ctx.applicationContext); inst = n; n }
            }
        }
    }

    private val file = File(ctx.applicationContext.filesDir, "brain.json")
    private val lock = Any()
    private val skills = LinkedHashMap<String, Skill>()
    private val events = ArrayList<BrainEvent>()
    private val declined = HashSet<String>()
    private var lastNudgeAt = 0L

    init { load() }

    // ------------------------------------------------------------------ queries

    fun resolve(text: String): Skill? = synchronized(lock) { Brain.resolve(skills.values, text) }

    fun skill(name: String): Skill? = synchronized(lock) { skills[name] }

    fun skillCount(): Int = synchronized(lock) { skills.size }

    // ------------------------------------------------------------------ changes

    /** null = saved, otherwise the reason it was refused. */
    fun addSkill(s: Skill): String? = synchronized(lock) {
        val bad = Brain.validateSkill(s)
        if (bad != null) return@synchronized bad
        if (!skills.containsKey(s.name) && skills.size >= Brain.MAX_SKILLS) return@synchronized "too many shortcuts"
        skills[s.name] = s
        save()
        null
    }

    /** Removes every skill whose source starts with [prefix] (used to roll back a skill pack). */
    fun removeBySource(prefix: String): Int = synchronized(lock) {
        val gone = skills.values.filter { it.source.startsWith(prefix) }.map { it.name }
        for (n in gone) skills.remove(n)
        if (gone.isNotEmpty()) save()
        gone.size
    }

    /** kind: "local" (a built-in command worked), "skill", "unknown" (nothing local understood it). */
    fun record(now: Long, heard: String, kind: String, cmd: String): Unit = synchronized(lock) {
        if (heard.isBlank()) return@synchronized
        Brain.record(events, BrainEvent(now, heard.take(120), kind, cmd))
        save()
    }

    /** The next shortcut to offer, at most once per [Brain.NUDGE_GAP_MS]. Asking counts even if ignored. */
    fun nextProposal(now: Long): Proposal? = synchronized(lock) {
        if (!Brain.canNudge(now, lastNudgeAt)) return@synchronized null
        val taken = HashSet<String>()
        for (s in skills.values) for (t in s.triggers) taken.add(Logic.norm(t))
        val p = Brain.proposals(events, taken, declined).firstOrNull() ?: return@synchronized null
        lastNudgeAt = now
        save()
        p
    }

    fun decline(p: Proposal): Unit = synchronized(lock) {
        declined.add(Brain.key(p.phrase, p.command))
        save()
    }

    /** null = saved, otherwise the reason. */
    fun accept(p: Proposal): String? = addSkill(Brain.skillFrom(p))

    fun clear(): Unit = synchronized(lock) {
        skills.clear(); events.clear(); declined.clear(); lastNudgeAt = 0L
        save()
    }

    fun statsJson(): String = synchronized(lock) {
        JSONObject()
            .put("skills", skills.size)
            .put("events", events.size)
            .put("learned", skills.values.count { it.source == "learned" })
            .toString()
    }

    // ------------------------------------------------------------------ file

    private fun load(): Unit = synchronized(lock) {
        try {
            if (!file.exists()) return@synchronized
            val o = JSONObject(file.readText())
            val sk = o.optJSONArray("skills") ?: JSONArray()
            for (i in 0 until sk.length()) {
                val j = sk.optJSONObject(i) ?: continue
                val s = Skill(
                    j.optString("name"), strings(j.optJSONArray("triggers")),
                    strings(j.optJSONArray("steps")), j.optString("source", "user")
                )
                if (Brain.validateSkill(s) == null) skills[s.name] = s     // re-validate: never trust the file blindly
            }
            val ev = o.optJSONArray("events") ?: JSONArray()
            for (i in 0 until ev.length()) {
                val j = ev.optJSONObject(i) ?: continue
                events.add(BrainEvent(j.optLong("ts"), j.optString("heard"), j.optString("kind"), j.optString("cmd")))
            }
            while (events.size > Brain.MAX_EVENTS) events.removeAt(0)
            declined.addAll(strings(o.optJSONArray("declined")))
            lastNudgeAt = o.optLong("lastNudgeAt", 0L)
        } catch (e: Exception) {
            skills.clear(); events.clear(); declined.clear(); lastNudgeAt = 0L   // corrupt file: start fresh
        }
    }

    private fun save() {
        try {
            val o = JSONObject()
            val sk = JSONArray()
            for (s in skills.values) {
                sk.put(
                    JSONObject().put("name", s.name).put("source", s.source)
                        .put("triggers", JSONArray(s.triggers)).put("steps", JSONArray(s.steps))
                )
            }
            val ev = JSONArray()
            for (e in events) ev.put(JSONObject().put("ts", e.ts).put("heard", e.heard).put("kind", e.kind).put("cmd", e.cmd))
            o.put("skills", sk).put("events", ev).put("declined", JSONArray(declined.toList())).put("lastNudgeAt", lastNudgeAt)
            val tmp = File(file.parentFile, "brain.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.writeText(o.toString()); tmp.delete() }
        } catch (e: Exception) {
            // disk problem: the in-memory brain keeps working, it just will not survive a restart
        }
    }

    private fun strings(a: JSONArray?): List<String> {
        val out = ArrayList<String>()
        if (a == null) return out
        for (i in 0 until a.length()) out.add(a.optString(i))
        return out
    }
}
