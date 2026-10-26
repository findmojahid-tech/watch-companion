package com.mojahid.watchcompanion

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Pure on-device LLM inference via Google's LiteRT-LM Kotlin runtime.
 * No API key, network call, root, ADB, or watch firmware involved.
 *
 * All work with Engine/Conversation is serialized on one dedicated background
 * executor shared by the activity and the optional wake listening service.
 */
object OfflineMomo {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "momo-local-ai").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var engine: Engine? = null
    private var convo: Conversation? = null
    private var loadedPath: String? = null

    suspend fun answer(context: Context, prompt: String): String = withContext(dispatcher) {
        val store = OfflineModelStore(context.applicationContext)
        if (!store.verified()) throw IllegalStateException(
            "Please download Momo's free offline AI brain from the main screen first."
        )
        val path = store.modelFile.absolutePath
        if (loadedPath != path || convo == null) {
            reset()
            val config = EngineConfig(
                modelPath = path,
                backend = Backend.CPU(),
                cacheDir = context.applicationContext.cacheDir.absolutePath
            )
            val newEngine = Engine(config)
            try {
                newEngine.initialize()
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are Momo, a cute and friendly wearable AI companion. " +
                            "Always speak English. Answer the user's questions helpfully " +
                            "in one to three short sentences. If unsure, admit it. " +
                            "Do not invent live news, make up real-world actions, or " +
                            "pretend to control a watch. This is a small offline model " +
                            "without live Internet, calendar access, or updated news."
                    ),
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.90, temperature = 0.65)
                )
                val newConvo = newEngine.createConversation(conversationConfig)
                engine = newEngine
                convo = newConvo
                loadedPath = path
            } catch (e: Exception) {
                runCatching { newEngine.close() }
                throw IllegalStateException("Offline AI couldn't initialize: " +
                    (e.message ?: "unknown runtime error"), e)
            }
        }
        try {
            val answer = convo!!.sendMessage(prompt.take(800), maxOutputToken = 160)
                .toString().trim()
                .replace(Regex("""(?s)<think>.*?</think>"""), "")
                .replace(Regex("""<\|[^>]+\|>"""), "")
                .trim()
            if (answer.isBlank()) {
                "I didn't get a complete answer. Could you ask that a different way?"
            } else {
                answer.take(850)
            }
        } catch (error: Exception) {
            // If the model gets into an invalid conversation state, discard it so
            // a later attempt can start with a clean conversation.
            reset()
            throw IllegalStateException(
                "Offline AI couldn't answer: " + (error.message ?: "try again")
            )
        }
    }

    private fun reset() {
        convo?.let { runCatching { it.close() } }
        convo = null
        engine?.let { runCatching { it.close() } }
        engine = null
        loadedPath = null
    }

    suspend fun unload() = withContext(dispatcher) { reset() }
}
