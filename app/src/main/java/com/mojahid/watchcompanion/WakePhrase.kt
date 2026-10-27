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
    /**
     * Speech recognizers return hypotheses ordered by confidence, but the first
     * candidate can omit a short wake word. Prefer an exact wake match when
     * available; otherwise preserve the highest-ranked phrase for follow-up
     * commands after Momo has explicitly asked "Yes?".
     */
    fun selectHypothesis(hypotheses: List<String>): String =
        hypotheses.firstOrNull { parse(it).detected }
            ?: hypotheses.firstOrNull().orEmpty()

}
