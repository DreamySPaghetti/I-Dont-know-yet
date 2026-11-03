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
        val attempts = listOf(
            Triple("GPU", Backend.GPU(), Backend.GPU()),
            Triple("CPU + GPU vision", Backend.CPU(), Backend.GPU()),
            Triple("CPU", Backend.CPU(), Backend.CPU()),
        )
        var last: Throwable? = null
        for ((name, main, vision) in attempts) {
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = path,
                        backend = main,
                        visionBackend = vision,
                        audioBackend = Backend.CPU(),
                        maxNumTokens = 4096,
                        cacheDir = ctx.cacheDir.path,
                    )
                )
                e.initialize()
                engine = e
                backendName = name
                return
            } catch (t: Throwable) {
                last = t
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

    override fun close() {
        closer.execute { runCatching { engine?.close() }; engine = null }
        closer.shutdown()
    }
}
