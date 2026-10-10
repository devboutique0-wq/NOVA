package com.nova.assistant

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects the Layer-2 slot to the model file the updater installed (UpdateManager.activeModelPath).
 * No inference engine is bundled yet: [factory] stays null until an engine (MediaPipe / LiteRT / llama.cpp) is
 * added AND compile-tested in CI. Until then [ask] and [chat] always return null and NOVA behaves exactly as before.
 *
 * Only ONE request runs at a time ([busy]): a timed-out native call may still be running, so a second request is
 * refused instead of queued (no pile-up, no double RAM use).
 */
object LocalBrains {
    /** An engine integration sets this: model file path -> a LocalBrain, or null if the file cannot be loaded. */
    @Volatile var factory: ((String) -> LocalBrain?)? = null

    private val lock = Any()
    private var cached: LocalBrain? = null
    private var cachedPath = ""
    private val busy = AtomicBoolean(false)
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "nova-localbrain").also { it.isDaemon = true } }

    /** true when an engine exists AND a model file is installed (cheap check, loads nothing). */
    fun available(ctx: Context): Boolean = factory != null && UpdateManager.activeModelPath(ctx).isNotEmpty()

    fun active(ctx: Context): LocalBrain? = synchronized(lock) {
        val path = UpdateManager.activeModelPath(ctx)
        if (path.isEmpty()) { dropLocked(); return@synchronized null }
        val f = factory ?: return@synchronized null
        if (path != cachedPath || cached == null) {
            dropLocked()
            cached = try { f(path) } catch (t: Throwable) { null }
            cachedPath = path
        }
        val c = cached
        if (c != null && c.ready) c else null
    }

    private fun dropLocked() {
        val old = cached
        cached = null
        cachedPath = ""
        if (old != null) try { old.close() } catch (t: Throwable) { }
    }

    /** Unloads the model (low memory / service stopping). The next request loads it again. */
    fun release() = synchronized(lock) { dropLocked() }

    /** Runs [prompt] with a time limit. null = refused (busy), failed or too slow. */
    private fun run(brain: LocalBrain, prompt: String, timeoutMs: Long): String? {
        if (!busy.compareAndSet(false, true)) return null
        val job = pool.submit(Callable<String?> {
            try { brain.complete(prompt) } finally { busy.set(false) }
        })
        return try {
            job.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            job.cancel(true)
            null
        }
    }

    /** One safe command sentence for [text], or null (no model, too slow, failed, or the answer was refused). */
    fun ask(ctx: Context, text: String): String? {
        val brain = active(ctx) ?: return null
        val raw = run(brain, LocalBrainRules.buildPrompt(text), LocalBrainRules.TIMEOUT_MS)
        return LocalBrainRules.parseOutput(raw)
    }

    /** A short TEXT answer (never an action) for [text], or null. [en] = answer language for the safety line. */
    fun chat(ctx: Context, text: String, en: Boolean): String? {
        val brain = active(ctx) ?: return null
        val raw = run(brain, LocalChat.buildPrompt(text), LocalChat.TIMEOUT_MS)
        val clean = LocalChat.clean(raw) ?: return null
        return LocalChat.guard(text, clean, en)
    }
}
