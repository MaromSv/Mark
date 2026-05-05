package com.example.emergency.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

enum class GemmaBackend { CPU, GPU, NPU }

data class GemmaLoadOptions(
    val modelPath: String,
    val backend: GemmaBackend = GemmaBackend.GPU,
    val systemInstruction: String? = "You are a helpful emergency assistant. Provide clear, concise guidance.",
    val topK: Int = 64,
    val topP: Double = 0.95,
    val temperature: Double = 1.0,
)

class ModelNotLoadedException : IllegalStateException("Model not loaded. Call load() first.")
class ModelFileMissingException(path: String) : IllegalStateException("Model file not found at: $path")

/**
 * Wrapper around LiteRT-LM Engine and Conversation for on-device Gemma inference.
 */
class GemmaLlm(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var conversationConfig: ConversationConfig? = null

    val isLoaded: Boolean get() = engine != null

    /**
     * Loads the model from [opts.modelPath]. Call from a background thread.
     */
    fun load(opts: GemmaLoadOptions) {
        if (!File(opts.modelPath).exists()) throw ModelFileMissingException(opts.modelPath)

        val backend = when (opts.backend) {
            GemmaBackend.CPU -> Backend.CPU()
            GemmaBackend.GPU -> Backend.GPU()
            GemmaBackend.NPU -> Backend.NPU(
                nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            )
        }

        unload()

        val newEngine = Engine(
            EngineConfig(
                modelPath = opts.modelPath,
                backend = backend,
                visionBackend = if (opts.backend == GemmaBackend.GPU) Backend.GPU() else Backend.CPU(),
                cacheDir = context.cacheDir.path,
            )
        )
        newEngine.initialize()

        val convConfig = ConversationConfig(
            systemInstruction = opts.systemInstruction?.let { Contents.of(it) },
            samplerConfig = SamplerConfig(
                topK = opts.topK,
                topP = opts.topP,
                temperature = opts.temperature,
            ),
        )

        conversation = newEngine.createConversation(convConfig)
        conversationConfig = convConfig
        engine = newEngine
    }

    /**
     * Drops the current conversation history and starts fresh, reusing the
     * loaded engine and the same system prompt + sampler config. Cheap -
     * no model reload.
     */
    fun resetConversation() {
        val e = engine ?: throw ModelNotLoadedException()
        val cfg = conversationConfig ?: throw ModelNotLoadedException()
        conversation?.close()
        conversation = e.createConversation(cfg)
    }

    fun unload() {
        conversation?.close()
        conversation = null
        conversationConfig = null
        engine?.close()
        engine = null
    }

    /** Emits each streamed token as it arrives. Completes when generation finishes. */
    fun generateStreaming(prompt: String): Flow<String> = flow {
        val c = conversation ?: throw ModelNotLoadedException()
        c.sendMessageAsync(prompt).collect { msg -> emit(msg.toString()) }
    }

    /** Emits each streamed token as it arrives, with optional images. Completes when generation finishes. */
    fun generateStreamingWithImages(prompt: String, imagePaths: List<String>): Flow<String> = flow {
        val c = conversation ?: throw ModelNotLoadedException()
        
        if (imagePaths.isEmpty()) {
            c.sendMessageAsync(prompt).collect { msg -> emit(msg.toString()) }
        } else {
            // Build multimodal content with images and text
            val contentParts = mutableListOf<Content>()
            
            // Add images first
            imagePaths.forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    contentParts.add(Content.ImageFile(path))
                }
            }
            
            // Add text prompt
            val textPrompt = if (prompt.isNotEmpty()) prompt else "Describe what you see in this image."
            contentParts.add(Content.Text(textPrompt))
            
            // Create Contents from parts and send
            val contents = Contents.of(*contentParts.toTypedArray())
            c.sendMessageAsync(contents).collect { msg -> emit(msg.toString()) }
        }
    }

    companion object {
        /** Default model location: app's external files dir. Push with adb. */
        fun defaultModelPath(context: Context): String =
            File(
                context.getExternalFilesDir(null) ?: context.filesDir,
                "gemma-4-E2B-it.litertlm",
            ).absolutePath
    }
}
