package com.nova.assistant

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects the Layer-2 slot to the model file the updater installed (UpdateManager.activeModelPath).
 * The engine is LlamaBrain (llama.cpp). [installEngine] sets [factory]; the model is only loaded when a model file
 * is installed AND a request needs it. If the native library or the file fails, [active] returns null and NOVA
 * behaves exactly as without a model.
 *
 * Only ONE request runs at a time ([busy]): a timed-out native call may still be running, so a second request is
 * refused instead of queued (no pile-up, no double RAM use).
 */
object LocalBrains {
    /** An engine integration sets this: model file path -> a LocalBrain, or null if the file cannot be loaded. */
    @Volatile var factory: ((String) -> LocalBrain?)? = null

    /** Installs the real engine (llama.cpp) once. Safe to call many times. Does not load any model yet. */
    fun installEngine(ctx: Context) {
        if (factory != null) return
        val resolver = ctx.applicationContext.contentResolver
        factory = { path -> LlamaBrain.create(resolver, path) }
    }

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
    fun chat(ctx: Context, text: String, en: Boolean, facts: List<String> = emptyList()): String? {
        val brain = active(ctx) ?: return null
        val raw = run(brain, LocalChat.buildPrompt(text, facts), LocalChat.TIMEOUT_MS)
        val clean = LocalChat.clean(raw) ?: return null
        return LocalChat.guard(text, clean, en)
    }
}
