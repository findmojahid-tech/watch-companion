package com.mojahid.watchcompanion

import android.content.Context

/**
 * Prefer the owner's OpenAI key, then the existing Gemini key, then the on-device model.
 * Existing Momo tasks and watch data are not migrated or reset.
 */
object TiffyBrain {
    fun cloudLabel(context: Context): String {
        val openAi = AiKeyStore(context, "openai").read()
        val gemini = AiKeyStore(context).read()
        return when {
            !openAi.isNullOrBlank() && !gemini.isNullOrBlank() ->
                "OpenAI connected · Gemini fallback"
            !openAi.isNullOrBlank() -> "OpenAI connected · quick answers"
            !gemini.isNullOrBlank() -> "Gemini connected · OpenAI optional"
            else -> "Connect an AI brain below"
        }
    }

    suspend fun answer(context: Context, text: String, history: List<ChatTurn>): String {
        val openAiKey = AiKeyStore(context, "openai").read()
        val geminiKey = AiKeyStore(context).read()
        var cloudError: Exception? = null

        if (!openAiKey.isNullOrBlank()) {
            try {
                return OpenAiClient.chat(text, history, openAiKey)
            } catch (error: Exception) {
                cloudError = error
            }
        }
        if (!geminiKey.isNullOrBlank()) {
            try {
                return GeminiClient.chat(text, history, geminiKey)
            } catch (error: Exception) {
                cloudError = error
            }
        }
        if (OfflineModelStore(context).verified()) {
            return OfflineMomo.answer(context, text)
        }
        if (cloudError != null) {
            throw IllegalStateException(
                "Cloud AI unavailable: " + (cloudError.message ?: "check key or network.")
            )
        }
        throw IllegalStateException(
            "No AI connected. Add an OpenAI API key in AI Brain or download the offline model."
        )
    }
}
