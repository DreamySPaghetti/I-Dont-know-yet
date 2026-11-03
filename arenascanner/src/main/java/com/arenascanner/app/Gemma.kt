package com.arenascanner.app

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingMode
import java.io.File
import java.util.concurrent.Executors

/**
 * Gemma 4 E2B running on the phone through Google's LiteRT-LM library. Nothing leaves the device.
 *
 * Each question uses a fresh conversation: LiteRT-LM has a known crash on the second image in one
 * conversation (google-ai-edge/LiteRT-LM#2056), and every board position is a new question anyway.
 */
class Gemma(private val ctx: Context) : AutoCloseable {
    companion object {
        const val FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val DOWNLOAD_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$FILE_NAME"
        private const val PREFS = "coach"
        const val KEY_DOWNLOAD_ID = "model_download_id"

        fun modelFile(ctx: Context) = File(ctx.getExternalFilesDir(null), "models/$FILE_NAME")

        fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** The model file is there (about 2.6 GB) and no download is still writing it. */
        fun isModelReady(ctx: Context) =
            modelFile(ctx).length() > 1_000_000_000L && prefs(ctx).getLong(KEY_DOWNLOAD_ID, -1L) == -1L
    }

    private var engine: Engine? = null
    private val closer = Executors.newSingleThreadExecutor()

    /** Which processor ended up running the model, for the status line. */
    var backendName = ""
        private set

    /**
     * Loads the model. Blocking, about 10 seconds: call it off the main thread.
     * Tries the GPU first (much faster at reading the image and prompt), then falls back to the CPU.
     */
    fun load() {
        val path = modelFile(ctx).absolutePath
        // Speculative decoding (Gemma 4's built-in drafter) speeds up writing the answer; ADAPTIVE only
        // uses it while it's actually faster. Files without a drafter fail that attempt and fall through.
        val spec = SpeculativeDecodingConfig(SpeculativeDecodingMode.ADAPTIVE)
        val attempts = listOf(
            Attempt("GPU + speculative", Backend.GPU(), Backend.GPU(), spec),
            Attempt("GPU", Backend.GPU(), Backend.GPU(), null),
            Attempt("CPU + GPU vision", Backend.CPU(), Backend.GPU(), null),
            Attempt("CPU", Backend.CPU(), Backend.CPU(), null),
        )
        var last: Throwable? = null
        for ((name, main, vision, specCfg) in attempts) {
            var e: Engine? = null
            try {
                e = Engine(
                    EngineConfig(
                        modelPath = path,
                        backend = main,
                        visionBackend = vision,
                        audioBackend = null, // never used; skipping it saves memory next to Arena
                        maxNumTokens = 4096,
                        maxNumImages = 1,
                        cacheDir = ctx.cacheDir.path,
                        speculativeDecodingConfig = specCfg,
                    )
                )
                e.initialize()
                engine = e
                backendName = name
                // If the fast path starts but can't answer, drop to the next setup instead of failing every question.
                if (!warmUp() && specCfg != null) throw IllegalStateException("speculative decoding failed its first answer")
                return
            } catch (t: Throwable) {
                last = t
                runCatching { e?.close() } // free the failed attempt's memory before the next one
                engine = null
            }
        }
        throw IllegalStateException("Couldn't start Gemma: ${last?.message}", last)
    }

    /**
     * Asks about one screenshot. [onText] gets the whole answer so far each time more arrives.
     * Callbacks come on LiteRT-LM's thread.
     */
    fun ask(image: ByteArray, prompt: String, onText: (String) -> Unit, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        val e = engine ?: return onError(IllegalStateException("The model isn't loaded yet."))
        val conv: Conversation = try {
            e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(listOf(Content.Text(CoachPrompt.SYSTEM))),
                    // Low temperature: the same board should get the same advice.
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.3),
                    // PLAY + WHY fit easily; a cap stops a rambling answer from holding up the next one.
                    maxOutputToken = 200,
                )
            )
        } catch (t: Throwable) {
            return onError(t)
        }
        val answer = StringBuilder()
        // The image must come before the text.
        val contents = Contents.of(listOf(Content.ImageBytes(image), Content.Text(prompt)))
        conv.sendMessageAsync(contents, object : MessageCallback {
            override fun onMessage(message: Message) {
                answer.append(message.toString())
                onText(answer.toString())
            }

            override fun onDone() {
                closer.execute { runCatching { conv.close() } }
                onDone()
            }

            override fun onError(throwable: Throwable) {
                closer.execute { runCatching { conv.close() } }
                onError(throwable)
            }
        }, emptyMap())
    }

    private data class Attempt(val name: String, val main: Backend, val vision: Backend, val spec: SpeculativeDecodingConfig?)

    /**
     * One tiny question at load time so the GPU programs for both the image and text paths are
     * built now (part of the 10 s "Loading") instead of slowing down the first real answer.
     */
    private fun warmUp(): Boolean {
        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        val jpg = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val done = java.util.concurrent.CountDownLatch(1)
        var ok = false
        runCatching { ask(jpg, "Reply with OK.", {}, { ok = true; done.countDown() }, { done.countDown() }) }.onFailure { done.countDown() }
        return done.await(30, java.util.concurrent.TimeUnit.SECONDS) && ok
    }

    override fun close() {
        closer.execute { runCatching { engine?.close() }; engine = null }
        closer.shutdown()
    }
}
