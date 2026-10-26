package com.mojahid.watchcompanion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.LocalDate
import java.util.Locale

data class GeminiStatus(val model: String, val testReply: String)
data class ChatTurn(val role: String, val text: String)

/**
 * Personal Android prototype, using a user-provided key encrypted locally with
 * Android Keystore. This key must never be put into this public GitHub project
 * or built into a publicly distributed APK.
 *
 * Key test calls the actual supported model. V9's separate "list models"
 * request was an extra point of failure, even when generateContent worked.
 * Stable Flash-Lite models are tested in order and only a 404 falls back.
 */
object GeminiClient {
    private val models = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")

    private class ApiFailure(val status: Int, message: String) : Exception(message)

    @Volatile private var cachedFingerprint: Int? = null
    @Volatile private var cachedModel: String? = null

    private fun errorFromGoogle(status: Int, json: String, key: String): ApiFailure {
        val original = runCatching {
            JSONObject(json).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        val detail = original.replace(key, "[REDACTED]")
            .replace(Regex("""AIza[A-Za-z0-9_-]{20,}"""), "[REDACTED]")
            .replace("\n", " ").take(180)
        val readable = when (status) {
            400 -> "Google rejected the AI request (400)."
            401 -> "Gemini API key was rejected (401)."
            403 -> "Google denied this key/project (403). Check key restrictions or region."
            404 -> "This Gemini model is unavailable for the API key (404)."
            429 -> "The project's Gemini quota or free limit is exhausted (429)."
            500, 502, 503, 504 -> "Google's AI service is temporarily unavailable."
            else -> "Gemini API failed (HTTP " + status + ")."
        }
        return ApiFailure(status, readable + if (detail.isBlank()) "" else " " + detail)
    }

    private fun post(model: String, key: String, prompt: String, history: List<ChatTurn>): String {
        val system = """
            You are Momo, a smart, cute and useful English-speaking watch companion.
            Answer everyday questions naturally and accurately. Always use English.
            Keep normal spoken answers to one to three useful short sentences; give
            longer detail only when explicitly requested. Avoid greetings before
            answering a question. Admit uncertainty. You have no live internet search,
            camera, physical watch-screen control or schedule integration.
            Do not pretend to finish tasks or issue commands on the watch.
            Local task management happens separately in Momo's app.
        """.trimIndent() + "\nToday's phone date is " + LocalDate.now().toString() + "."

        val contents = JSONArray()
        history.takeLast(6).forEach { turn ->
            contents.put(JSONObject().apply {
                put("role", if (turn.role == "model") "model" else "user")
                put("parts", JSONArray().put(JSONObject().put("text", turn.text.take(650))))
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", prompt.take(1800))))
        })

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", system))
            ))
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 400)
                put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
            })
        }

        val connection = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
        ).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 25000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", key)
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }

            val code = connection.responseCode
            val payload = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (code !in 200..299) throw errorFromGoogle(code, payload, key)

            val response = JSONObject(payload)
            val candidates = response.optJSONArray("candidates")
                ?: throw IllegalStateException("Gemini returned no response.")
            val candidate = candidates.optJSONObject(0)
                ?: throw IllegalStateException("Gemini returned no answer.")
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini returned no speakable text.")
            val result = buildString {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (!part.optBoolean("thought")) {
                        append(part.optString("text"))
                    }
                }
            }.trim()
            if (result.isBlank()) {
                throw IllegalStateException("Google returned an empty answer. Try again.")
            }
            return result.take(1500)
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("Gemini timed out. Check the connection and retry.")
        } finally {
            connection.disconnect()
        }
    }

    private fun runWithFallback(
        key: String,
        question: String,
        history: List<ChatTurn>,
        forceFresh: Boolean
    ): GeminiStatus {
        val normalized = key.trim()
        if (normalized.isBlank()) throw IllegalStateException("Paste a Gemini API key first.")
        if (normalized.length > 250) throw IllegalStateException("Invalid key length.")
        val fingerprint = normalized.hashCode()
        val preferred = if (!forceFresh && cachedFingerprint == fingerprint) {
            cachedModel
        } else null
        val candidates = listOfNotNull(preferred) + models.filterNot { it == preferred }

        var unavailable: Exception? = null
        for (model in candidates.distinct()) {
            try {
                val reply = post(model, normalized, question, history)
                cachedFingerprint = fingerprint
                cachedModel = model
                return GeminiStatus(model, reply)
            } catch (error: ApiFailure) {
                if (error.status == 404) {
                    unavailable = error
                    continue
                }
                throw error
            }
        }
        throw unavailable ?: IllegalStateException("No compatible Gemini Flash-Lite model was available.")
    }

    suspend fun verifyKey(key: String): GeminiStatus = withContext(Dispatchers.IO) {
        runWithFallback(
            key,
            "Reply in one simple English sentence: Hi, I'm Momo and I'm ready to help.",
            emptyList(),
            forceFresh = true
        )
    }

    suspend fun chat(
        text: String,
        history: List<ChatTurn>,
        key: String
    ): String = withContext(Dispatchers.IO) {
        runWithFallback(key, text, history, forceFresh = false).testReply
    }

    suspend fun interpret(
        utterance: String,
        tasks: List<TaskItem>,
        apiKey: String
    ): AiIntent = withContext(Dispatchers.IO) {
        val context = tasks.takeLast(6).map {
            ChatTurn(
                "user",
                "Existing task: " + it.title + if (it.done) " (completed)" else " (pending)"
            )
        }
        AiIntent("chat", "", chat(utterance, context, apiKey))
    }
}
