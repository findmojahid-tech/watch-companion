package com.mojahid.watchcompanion

/**
 * Conservative interpretation of REAL Watch 7 (firmware 4.21) observations.
 *
 * This does not guess dial packets or change watch state. In particular a GATT
 * successful write is NOT an application-level acknowledgement from the watch.
 */
object Watch7PacketAnalysis {
    private val version = Regex("""\d{1,3}\.\d{1,3}(?:\.\d{1,3})?""")
    private val modelCode = Regex("""[A-Z][0-9A-Z]{2,7}""")

    private fun asciiChunks(bytes: ByteArray): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.length in 3..24) chunks += current.toString()
            current.clear()
        }
        bytes.forEach {
            val b = it.toInt() and 0xff
            if (b in 32..126) current.append(b.toChar()) else flush()
        }
        flush()
        return chunks
    }

    /** Candidate text, NOT verified feature/firmware semantics. */
    fun explainRead(uuid: String, bytes: ByteArray): String? {
        val id = uuid.lowercase().substringBefore('-')
        if (id != "6e400006" && id != "6e400007") return null
        val parts = asciiChunks(bytes)
        val versions = parts.filter { version.matches(it) }.distinct()
        val models = if (id == "6e400007")
            parts.filter { modelCode.matches(it) }.distinct()
        else emptyList()
        val fragments = mutableListOf<String>()
        if (models.isNotEmpty())
            fragments += "device-code candidate: " + models.joinToString(", ")
        if (versions.isNotEmpty())
            fragments += "version-like: " + versions.joinToString(", ")
        return fragments.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    data class QueryEvidence(
        val queryRecorded: Boolean,
        val gattWriteAccepted: Boolean,
        val postQueryNotifications: Int,
        val postQueryCommandGroupNotifications: Int,
        val explanation: String
    )

    /**
     * The event list is ordered by the app's log, not the watch clock.
     * A notification after a query is only a temporal candidate, not a
     * confirmed response; notifications before the query cannot be responses.
     */
    fun evaluate(events: List<String>): QueryEvidence {
        val queryIndex = events.indexOfLast { it.contains("TX ID QUERY on") }
        if (queryIndex == -1) {
            return QueryEvidence(false, false, 0, 0,
                "No ID query in this capture; passive watch notifications only.")
        }
        val after = events.drop(queryIndex + 1)
        val notifications = after.filter { it.contains("NOTIFY 6e40000") }
        val commandGroup = notifications.count {
            it.contains("NOTIFY 6e40000b") ||
                it.contains("NOTIFY 6e40000c") ||
                it.contains("NOTIFY 6e40000d")
        }
        val accepted = after.any { it.contains("ID query GATT accepted") }
        val explanation = when {
            commandGroup > 0 ->
                "Found " + commandGroup + " command-group notification(s) after ID query. " +
                    "These are possible replies, NOT decoded/verified acknowledgements."
            notifications.isNotEmpty() ->
                "Found " + notifications.size + " watch notification(s) after ID query, " +
                    "but none on 0b/0c/0d. No command response has been identified."
            accepted ->
                "GATT write succeeded, but NO watch notification followed the ID query. " +
                    "This does not prove the watch recognized the NJJ command."
            else ->
                "No confirmed GATT write acknowledgement or watch reply to the ID query."
        }
        return QueryEvidence(true, accepted, notifications.size, commandGroup, explanation)
    }
}
