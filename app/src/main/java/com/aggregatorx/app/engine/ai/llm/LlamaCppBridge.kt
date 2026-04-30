package com.aggregatorx.app.engine.ai.llm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native bridge to llama.cpp / kotlinllamacpp.
 *
 * The bridge is intentionally **fail-soft**: the JNI library is loaded
 * lazily and any UnsatisfiedLinkError is caught so the app can still run
 * (with the LLM features disabled) when the .so files or the GGUF model
 * aren't present on a given build.
 *
 * To enable real generation you need:
 *   1. The kotlinllamacpp / llama.cpp native libraries on the JNI path
 *      (typically dropped under `app/src/main/jniLibs/<abi>/libllama.so`).
 *   2. The Dolphin 3.0 Llama 3.1 8B GGUF model file at
 *      `assets/models/dolphin-3.0-llama3.1-8b.Q4_K_M.gguf` (or any GGUF
 *      named the same — see [MODEL_ASSET_NAME]). The model is staged from
 *      `assets/` into the app's private files dir on first init because
 *      llama.cpp's mmap loader needs a real file path.
 *
 * If either piece is missing, [isAvailable] returns false and
 * [generate] degrades to a passthrough echo of the prompt. Callers should
 * check [isAvailable] first to decide whether to call into the model.
 */
class LlamaCppBridge private constructor() {

    private val initialized = AtomicBoolean(false)
    private var nativeHandle: Long = 0L
    private var nativeAvailable: Boolean = false

    init {
        nativeAvailable = try {
            System.loadLibrary("llama")           // libllama.so from llama.cpp
            // Some builds ship the JNI shim as a separate lib; load both
            // optimistically — it's fine if the second one isn't there.
            try { System.loadLibrary("kotlinllamacpp") } catch (_: UnsatisfiedLinkError) {}
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "llama.cpp native libraries not present — LLM disabled (${e.message})")
            false
        } catch (e: Throwable) {
            Log.w(TAG, "Unexpected error loading llama.cpp natives — LLM disabled", e)
            false
        }
    }

    /** True iff both the native libs and the GGUF model are usable. */
    fun isAvailable(): Boolean = nativeAvailable && initialized.get() && nativeHandle != 0L

    /**
     * Initialize the model. Idempotent. Returns true on success.
     *
     * Heavy: loads the multi-GB GGUF file and primes the context. Call from
     * a background dispatcher (Dispatchers.IO) only.
     */
    fun initialize(context: Context, contextLengthTokens: Int = 2048): Boolean {
        if (!nativeAvailable) return false
        if (initialized.get()) return nativeHandle != 0L

        val modelFile = stageModelFromAssets(context) ?: run {
            Log.w(TAG, "GGUF model not found in assets at $MODEL_ASSET_NAME — LLM disabled")
            return false
        }

        return try {
            nativeHandle = nativeInit(modelFile.absolutePath, contextLengthTokens)
            initialized.set(true)
            nativeHandle != 0L
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "JNI symbols missing for nativeInit — LLM disabled", e)
            nativeAvailable = false
            false
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to initialize Llama context", e)
            false
        }
    }

    /**
     * Run a single completion. Returns the model's reply, or — if the
     * model isn't available — a passthrough of the prompt so the calling
     * pipeline still produces something usable in fallback mode.
     */
    fun generate(prompt: String, maxTokens: Int = 128, temperature: Float = 0.7f): String {
        if (!isAvailable()) return prompt
        return try {
            nativeGenerate(nativeHandle, prompt, maxTokens, temperature)
        } catch (e: Throwable) {
            Log.w(TAG, "nativeGenerate failed — falling back to passthrough", e)
            prompt
        }
    }

    /** Releases the underlying llama.cpp context. Safe to call multiple times. */
    fun release() {
        if (!nativeAvailable || nativeHandle == 0L) return
        try {
            nativeRelease(nativeHandle)
        } catch (_: Throwable) { /* swallow */ }
        nativeHandle = 0L
        initialized.set(false)
    }

    // ── JNI symbols ──────────────────────────────────────────────────────
    // These must be implemented by the kotlinllamacpp / custom JNI shim.
    // See `app/src/main/cpp/llama_jni.cpp` if you ship your own.

    private external fun nativeInit(modelPath: String, contextLen: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float): String
    private external fun nativeRelease(handle: Long)

    // ── Model staging ────────────────────────────────────────────────────

    private fun stageModelFromAssets(context: Context): File? {
        val out = File(context.filesDir, MODEL_FILE_NAME)
        if (out.exists() && out.length() > 0) return out

        return try {
            context.assets.open(MODEL_ASSET_NAME).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            out
        } catch (_: java.io.FileNotFoundException) {
            null
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to stage GGUF model into files dir", e)
            null
        }
    }

    companion object {
        private const val TAG = "LlamaCppBridge"

        /** Path inside `assets/` where the GGUF lives. */
        const val MODEL_ASSET_NAME = "models/dolphin-3.0-llama3.1-8b.Q4_K_M.gguf"

        /** File name used after staging the model into the app's files dir. */
        const val MODEL_FILE_NAME  = "dolphin-3.0-llama3.1-8b.Q4_K_M.gguf"

        @Volatile private var INSTANCE: LlamaCppBridge? = null
        fun get(): LlamaCppBridge = INSTANCE ?: synchronized(this) {
            INSTANCE ?: LlamaCppBridge().also { INSTANCE = it }
        }
    }
}
