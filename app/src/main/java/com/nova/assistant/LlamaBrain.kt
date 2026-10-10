package com.nova.assistant

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.nehuatl.llamacpp.LlamaHelper

/**
 * The real offline engine: llama.cpp through io.github.ljcamargo:llamacpp-kotlin (LlamaHelper).
 * API names taken from the library's own demo app (MainViewModel.kt): LlamaHelper(contentResolver, scope, sharedFlow),
 * load(path, contextLength, mmprojPath){...}, predict(prompt), stopPrediction(), abort(), release(),
 * events Started / Ongoing(word) / Done / Error.
 *
 * It only turns a prompt into text. It has no access to tools: NovaService decides what to do with the text
 * (a chat answer is spoken, a translator answer must still pass LocalBrainRules + the spoken "yes" gate).
 * LocalBrains guarantees that only ONE request runs at a time.
 */
class LlamaBrain private constructor(
    private val scope: CoroutineScope,
    private val flow: MutableSharedFlow<LlamaHelper.LLMEvent>,
    private val helper: LlamaHelper
) : LocalBrain {

    private val loadedLatch = CountDownLatch(1)
    @Volatile private var loaded = false
    @Volatile private var closed = false

    override val ready: Boolean get() = loaded && !closed

    override fun complete(prompt: String): String? {
        if (closed) return null
        if (!loadedLatch.await(LOAD_WAIT_MS, TimeUnit.MILLISECONDS) || !loaded) return null
        val text = StringBuilder()
        val finished = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        val tooLong = AtomicBoolean(false)
        // The flow has no replay: the collector must be subscribed BEFORE predict() starts.
        val job = scope.launch {
            flow.collect { ev ->
                when (ev) {
                    is LlamaHelper.LLMEvent.Ongoing -> {
                        synchronized(text) { text.append(ev.word) }
                        val len = synchronized(text) { text.length }
                        if (len > MAX_CHARS || synchronized(text) { text.contains(STOP) }) {
                            tooLong.set(true)
                            finished.countDown()
                        }
                    }
                    is LlamaHelper.LLMEvent.Done -> finished.countDown()
                    is LlamaHelper.LLMEvent.Error -> { failed.set(true); finished.countDown() }
                    else -> { }
                }
            }
        }
        try {
            var waited = 0
            while (flow.subscriptionCount.value == 0 && waited < 1000) { Thread.sleep(10); waited += 10 }
            helper.predict(prompt)
            val inTime = finished.await(GENERATE_WAIT_MS, TimeUnit.MILLISECONDS)
            if (!inTime || tooLong.get()) {
                try { helper.abort() } catch (t: Throwable) { }
            } else {
                try { helper.stopPrediction() } catch (t: Throwable) { }
            }
        } catch (t: Throwable) {
            failed.set(true)
        } finally {
            job.cancel()
        }
        val out = synchronized(text) { text.toString() }
        if (out.isBlank()) return null
        if (failed.get() && out.length < 4) return null
        return out
    }

    override fun close() {
        if (closed) return
        closed = true
        try { helper.abort() } catch (t: Throwable) { }
        try { helper.release() } catch (t: Throwable) { }
        try { scope.cancel() } catch (t: Throwable) { }
    }

    companion object {
        const val CONTEXT = 1536
        const val LOAD_WAIT_MS = 25_000L
        const val GENERATE_WAIT_MS = 28_000L
        const val MAX_CHARS = 700
        const val STOP = "<|im_end|>"

        /** Loads the GGUF at [path]. null = file missing/too small, native library missing, or load failed. */
        fun create(resolver: ContentResolver, path: String): LlamaBrain? {
            val f = File(path)
            if (!f.isFile || f.length() < 1_000_000L) return null
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val flow = MutableSharedFlow<LlamaHelper.LLMEvent>(
                replay = 0,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
            return try {
                val helper = LlamaHelper(contentResolver = resolver, scope = scope, sharedFlow = flow)
                val brain = LlamaBrain(scope, flow, helper)
                helper.load(path = Uri.fromFile(f).toString(), contextLength = CONTEXT) {
                    brain.loaded = true
                    brain.loadedLatch.countDown()
                }
                brain.loadedLatch.await(LOAD_WAIT_MS, TimeUnit.MILLISECONDS)
                brain
            } catch (t: Throwable) {
                try { scope.cancel() } catch (e: Throwable) { }
                null
            }
        }
    }
}
