package com.nova.assistant

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v34 PART 3: ONE silent background runner for everything NOVA can prepare by itself once the owner has pressed "allow":
 *  1. the voice-check model (small download),
 *  2. a tiny health check of every online AI provider that has a key (Groq, Qwen via Groq, OpenRouter).
 * No pop-up, nothing spoken, no provider name in a chat reply. Results are only SELF TEST lines (Settings).
 * Retries use AutoSetupPlan's back-off. It cannot switch on Accessibility / Notification access / Overlay: Android forbids that.
 * Offline Qwen is NOT part of it (the llama.cpp engine does not build with the current toolchain, see v31 section 3).
 */
object AutoSetup {
    private val started = AtomicBoolean(false)
    @Volatile private var stopFlag = false
    private val lines = ArrayList<String>()

    private fun add(l: String) {
        synchronized(lines) {
            lines.add(l)
            while (lines.size > 40) lines.removeAt(0)
        }
    }

    /** Lines written by the background runner so far. */
    fun log(): List<String> = synchronized(lines) { ArrayList(lines) }

    /** Called when the mic service is running (permissions given). Safe to call again: only one runner exists. */
    fun start(ctx: Context) {
        if (!started.compareAndSet(false, true)) { stopFlag = false; return }   // already running (maybe about to stop): keep it
        stopFlag = false
        val app = ctx.applicationContext
        Thread({
            try {
                loop(app)
            } catch (e: Throwable) {
                add("WARN Auto setup ruk gaya - " + e.javaClass.simpleName)
            } finally {
                started.set(false)
            }
        }, "nova-autosetup").apply { isDaemon = true }.start()
    }

    fun stop() { stopFlag = true }

    private fun loop(app: Context) {
        var tasks = AutoSetupPlan.initial()
        while (!stopFlag) {
            val now = System.currentTimeMillis()
            val t = AutoSetupPlan.due(tasks, now)
            if (t == null) {
                val next = AutoSetupPlan.nextWake(tasks) ?: return
                try { Thread.sleep(minOf(maxOf(next - now, 1000L), 60_000L)) } catch (e: InterruptedException) { return }
                continue
            }
            val o = try {
                when (t.id) {
                    AutoSetupPlan.VOICE -> voiceTask(app)
                    AutoSetupPlan.PROVIDERS -> providersTask(app)
                    else -> AutoSetupPlan.Outcome.FATAL
                }
            } catch (e: Throwable) { AutoSetupPlan.Outcome.RETRY }
            val after = AutoSetupPlan.apply(t, o, System.currentTimeMillis())
            tasks = tasks.map { if (it.id == t.id) after else it }
        }
    }

    private fun voiceTask(app: Context): AutoSetupPlan.Outcome {
        if (!ExtCfg.autoModels(app)) { add("SKIP Awaaz model - auto band hai"); return AutoSetupPlan.Outcome.SKIP }
        if (VoiceLock.modelReady(app)) return AutoSetupPlan.Outcome.SKIP
        val err = ModelAuto.silentVoice(app)
        return if (err == null) { add("PASS Awaaz model - download ho gaya"); AutoSetupPlan.Outcome.OK }
        else { add("WARN Awaaz model - $err, dobara koshish hogi"); AutoSetupPlan.Outcome.RETRY }
    }

    /** Providers that have the key they need. Pollinations (no key) is left out: it is not a setup item. */
    private fun keyOf(ctx: Context, p: FreeProvider): String = when (p.keyName) {
        "groq" -> SecureStore.getGroq(ctx)
        "openrouter" -> SecureStore.getSlot(ctx, "openrouter")
        "zai" -> SecureStore.getSlot(ctx, "zai")
        else -> ""
    }.trim()

    /** One tiny request per keyed provider (first model only). Returns SELF TEST lines. Network: worker thread only. */
    fun checkProviders(ctx: Context): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for (p in FreeProviders.ordered()) {
            if (p.keyName == null) continue
            val key = keyOf(ctx, p)
            if (key.isEmpty()) continue
            val r = FreeHttp.post(p, FreeProviders.buildBody(p.models[0], "Reply with one word.", "ok", 16), key)
            out.add(Pair(p.label + " (" + p.models[0] + ")", r.code))
        }
        return out
    }

    private fun providersTask(app: Context): AutoSetupPlan.Outcome {
        val res = checkProviders(app)
        if (res.isEmpty()) return AutoSetupPlan.Outcome.SKIP
        var worst = AutoSetupPlan.Outcome.OK
        for ((label, code) in res) {
            add(AutoSetupPlan.line(label, code))
            if (AutoSetupPlan.outcomeFor(code) == AutoSetupPlan.Outcome.RETRY) worst = AutoSetupPlan.Outcome.RETRY
        }
        return worst
    }
}
