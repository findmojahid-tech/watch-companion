package com.mojahid.watchcompanion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.LocalDate

data class GeminiStatus(val model: String, val testReply: String)
data class ChatTurn(val role: String, val text: String)

/**
 * A user-keyed HTTPS client for plain English answers. This replaces V4's
 * JSON-only replies and restrictive 450 output-token budget.
 * No paid-only model is selected automatically. Never log API keys.
 */
object GeminiClient {
    private val freeTierEligible = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
    @Volatile private var lastKeyFingerprint = 0
    @Volatile private var cachedModel: String? = null

    private fun sanitizedError(response: String, apiKey: String): String {
        val detail = runCatching {
            JSONObject(response).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        return detail.replace(apiKey, "[redacted]")
            .replace(Regex("""(?i)AIza[A-Za-z0-9_-]{20,}"""), "[redacted]")
            .replace("\n", " ").take(250)
    }

    private fun errorMessage(code: Int, response: String, key: String): String {
        val detail = sanitizedError(response, key)
        val friendly = when (code) {
            400 -> "Gemini rejected this request (400)."
            401 -> "Invalid Gemini API key (401)."
            403 -> "Gemini blocked this key or project (403). Check API restrictions and project access."
            404 -> "The selected Gemini model is not available to this key (404)."
            429 -> "Gemini free-tier quota or rate limit reached (429)."
            500, 502, 503, 504 -> "Gemini is temporarily unavailable (" + code + ")."
            else -> "Gemini returned HTTP " + code + "."
        }
        return friendly + if (detail.isNotBlank()) " " + detail else ""
    }

    private fun request(url: String, method: String, key: String, json: JSONObject? = null): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12000
            connection.readTimeout = 30000
            connection.setRequestProperty("x-goog-api-key", key)
            connection.setRequestProperty("Accept", "application/json")
            if (json != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(json.toString()) }
            }
            val status = connection.responseCode
            val body = if (status in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            if (status !in 200..299) throw IllegalStateException(errorMessage(status, body, key))
            return runCatching { JSONObject(body) }.getOrElse {
                throw IllegalStateException("Gemini returned an invalid JSON transport response.")
            }
        } catch (error: SocketTimeoutException) {
            throw IllegalStateException("Gemini timed out. Check internet and try again.")
        } finally {
            connection.disconnect()
        }
    }

    private fun availableModel(key: String, refresh: Boolean): String {
        val fingerprint = key.hashCode()
        if (!refresh && lastKeyFingerprint == fingerprint && cachedModel != null) {
            return cachedModel!!
        }
        val response = request(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", "GET", key
        )
        val items = response.optJSONArray("models") ?: JSONArray()
        val available = mutableSetOf<String>()
        for (index in 0 until items.length()) {
            val model = items.optJSONObject(index) ?: continue
            val methods = model.optJSONArray("supportedGenerationMethods") ?: continue
            var canGenerate = false
            for (j in 0 until methods.length()) {
                if (methods.optString(j) == "generateContent") canGenerate = true
            }
            if (canGenerate) available += model.optString("name").removePrefix("models/")
        }
        val chosen = freeTierEligible.firstOrNull { it in available }
            ?: throw IllegalStateException(
                "This API key has no supported free-tier Flash-Lite model listed. " +
                    "Check the AI Studio project and quota. No paid model was called."
            )
        lastKeyFingerprint = fingerprint
        cachedModel = chosen
        return chosen
    }

    private fun generate(key: String, model: String, userMessage: String, history: List<ChatTurn>): String {
        val system = """
            You are Momo, a warm, smart, helpful personal companion.
            Answer general questions in clear, natural English even if the user speaks another language.
            Help with science, maths, creativity, planning, explanations, everyday questions and coding.
            Follow conversational context. Prefer 1-3 helpful sentences for voice conversations;
            provide a longer answer if it is genuinely needed.
            Be accurate and admit uncertainty. You cannot browse live news, access the camera,
            control the watch screen, trigger calls, or create scheduled reminders.
            Task saving/completion happens locally in this companion app.
            Never claim actions happened unless the app really performed them.
        """.trimIndent() + "\nThe phone's current date is " + LocalDate.now().toString() + "."

        val contents = JSONArray()
        history.takeLast(8).forEach { turn ->
            contents.put(JSONObject().apply {
                put("role", if (turn.role == "model") "model" else "user")
                put("parts", JSONArray().put(JSONObject().put("text", turn.text.take(900))))
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", userMessage.take(2400))))
        })

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", system))
            ))
            put("contents", contents)
            put("generationConfig", JSONObject().put("maxOutputTokens", 2048))
        }
        val response = request(
            "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent",
            "POST", key, body
        )
        val candidates = response.optJSONArray("candidates")
            ?: throw IllegalStateException("Gemini did not return an answer. Try again.")
        val candidate = candidates.optJSONObject(0)
            ?: throw IllegalStateException("Gemini returned an empty answer.")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException(
                "Gemini generated no text (finish reason: " +
                    candidate.optString("finishReason", "unknown") + ")."
            )
        val answer = buildString {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (!part.optBoolean("thought")) append(part.optString("text"))
            }
        }.trim()
        if (answer.isBlank()) throw IllegalStateException("Gemini returned no speakable text. Try again.")
        return answer.take(4500)
    }

    suspend fun verifyKey(key: String): GeminiStatus = withContext(Dispatchers.IO) {
        val normalized = key.trim()
        if (normalized.isBlank()) throw IllegalStateException("Paste your AI Studio API key first.")
        val model = availableModel(normalized, refresh = true)
        val response = generate(
            normalized, model, "Say hello in one short English sentence.", emptyList()
        )
        GeminiStatus(model, response)
    }

    suspend fun chat(text: String, history: List<ChatTurn>, key: String): String =
        withContext(Dispatchers.IO) {
            val normalized = key.trim()
            if (normalized.isBlank()) throw IllegalStateException("No API key configured.")
            val model = availableModel(normalized, refresh = false)
            generate(normalized, model, text, history)
        }

    // Compatibility for older V3 code; the V5 conversational flow uses chat().
    suspend fun interpret(
        utterance: String, tasks: List<TaskItem>, apiKey: String
    ): AiIntent = withContext(Dispatchers.IO) {
        val context = tasks.takeLast(8).map {
            ChatTurn("user", "Existing task: " + it.title +
                if (it.done) " (completed)" else " (pending)")
        }
        AiIntent("chat", "", chat(utterance, context, apiKey))
    }
}
