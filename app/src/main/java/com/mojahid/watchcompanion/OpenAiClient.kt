package com.mojahid.watchcompanion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.LocalDate

/** A personal-device prototype; never hardcode or publish a user's OpenAI API key. */
data class OpenAiStatus(val model: String, val testReply: String)

object OpenAiClient {
    // Lightweight model for quick conversational replies; change here after measuring latency.
    const val MODEL = "gpt-6-luna"
    private const val ENDPOINT = "https://api.openai.com/v1/responses"

    internal fun extractReply(payload: String): String {
        val response = JSONObject(payload)
        val output = response.optJSONArray("output") ?: JSONArray()
        val text = buildString {
            for (i in 0 until output.length()) {
                val message = output.optJSONObject(i) ?: continue
                if (message.optString("type") != "message") continue
                val parts = message.optJSONArray("content") ?: continue
                for (j in 0 until parts.length()) {
                    val part = parts.optJSONObject(j) ?: continue
                    if (part.optString("type") == "output_text") {
                        if (isNotEmpty()) append("\n")
                        append(part.optString("text"))
                    }
                }
            }
        }.trim()
        if (text.isBlank()) throw IllegalStateException(
            "OpenAI returned no text. Check the model access or output-token limit."
        )
        return text.take(1800)
    }

    internal fun messageForFailure(status: Int, response: String): String {
        val description = when (status) {
            400 -> "OpenAI rejected the request (400)."
            401 -> "OpenAI key is invalid or expired (401)."
            403 -> "This OpenAI project is not permitted to use the requested model (403)."
            404 -> "The selected OpenAI model is unavailable (404)."
            429 -> "OpenAI quota, credits, or rate limit reached (429)."
            in 500..599 -> "OpenAI service is temporarily unavailable."
            else -> "OpenAI request failed (HTTP " + status + ")."
        }
        // Never echo raw provider errors: they may contain credentials or account metadata.
        return description
    }

    private fun request(prompt: String, history: List<ChatTurn>, key: String): OpenAiStatus {
        val secret = key.trim()
        require(secret.length in 12..250) { "Enter a valid OpenAI API key." }
        val messages = JSONArray()
        history.takeLast(6).forEach { turn ->
            messages.put(JSONObject()
                .put("role", if (turn.role == "model") "assistant" else "user")
                .put("content", turn.text.take(800)))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt.take(1800)))

        val instructions = "You are Tiffy, a fast, helpful, friendly personal JARVIS-style " +
            "companion talking through an Android phone and a Bluetooth-call watch. " +
            "Speak in the language the user uses, including English or Bengali. " +
            "Answer short questions in one or two direct sentences without a greeting. " +
            "Provide more detail only when requested. Be accurate and admit uncertainty. " +
            "The phone app manages local tasks separately. You have no connected smart-home " +
            "devices, live browsing, camera, calendar, or ability to control the physical watch " +
            "display. Never claim an action happened unless its tool confirmed success. " +
            "Today is " + LocalDate.now().toString() + " on the phone."

        val body = JSONObject()
            .put("model", MODEL)
            .put("instructions", instructions)
            .put("input", messages)
            .put("max_output_tokens", 260)
            .put("store", false)

        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 9000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer " + secret)
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
                it.write(body.toString())
            }
            val code = connection.responseCode
            val payload = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (code !in 200..299) {
                throw IllegalStateException(messageForFailure(code, payload))
            }
            return OpenAiStatus(MODEL, extractReply(payload))
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("OpenAI request timed out. Check your internet connection.")
        } finally {
            connection.disconnect()
        }
    }

    suspend fun verifyKey(key: String): OpenAiStatus = withContext(Dispatchers.IO) {
        request("Introduce yourself as Tiffy in five words.", emptyList(), key)
    }

    suspend fun chat(text: String, history: List<ChatTurn>, key: String): String =
        withContext(Dispatchers.IO) {
            request(text, history, key).testReply
        }
}
