package com.mojahid.watchcompanion

/** Exact English trigger matching without treating "momoring" as "Momo". */
data class WakePhraseResult(val detected: Boolean, val command: String)

object WakePhrase {
    private val prefix = Regex(
        """^(?:(?:hey|hello|hi)\s+)?momo(?=$|[\s,.:!?])[\s,.:!?]*""",
        RegexOption.IGNORE_CASE
    )

    fun parse(utterance: String): WakePhraseResult {
        val trimmed = utterance.trim()
        val match = prefix.find(trimmed)
            ?: return WakePhraseResult(false, trimmed)
        return WakePhraseResult(
            detected = true,
            command = trimmed.substring(match.range.last + 1).trim()
        )
    }
}
