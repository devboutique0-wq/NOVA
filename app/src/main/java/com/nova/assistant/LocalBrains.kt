package com.nova.assistant

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Connects the Layer-2 slot to the model file the updater installed (UpdateManager.activeModelPath).
 * No inference engine is bundled yet: [factory] stays null until an engine (MediaPipe / LiteRT / llama.cpp) is
 * added AND compile-tested in CI. Until then [ask] always returns null and NOVA behaves exactly as before.
 */
object LocalBrains {
    /** An engine integration sets this: model file path -> a LocalBrain, or null if the file cannot be loaded. */
    @Volatile var factory: ((String) -> LocalBrain?)? = null

    private val lock = Any()
    private var cached: LocalBrain? = null
    private var cachedPath = ""
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "nova-localbrain").also { it.isDaemon = true } }

    fun active(ctx: Context): LocalBrain? = synchronized(lock) {
        val path = UpdateManager.activeModelPath(ctx)
        if (path.isEmpty()) { cached = null; cachedPath = ""; return@synchronized null }
        val f = factory ?: return@synchronized null
        if (path != cachedPath || cached == null) {
            cached = try { f(path) } catch (t: Throwable) { null }
            cachedPath = path
        }
        val c = cached
        if (c != null && c.ready) c else null
    }

    /** One safe command sentence for [text], or null (no model, too slow, failed, or the answer was refused). */
    fun ask(ctx: Context, text: String): String? {
        val brain = active(ctx) ?: return null
        val job = pool.submit(Callable<String?> { brain.complete(LocalBrainRules.buildPrompt(text)) })
        val raw: String? = try {
            job.get(LocalBrainRules.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            job.cancel(true)
            null
        }
        return LocalBrainRules.parseOutput(raw)
    }
}
