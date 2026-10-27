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

    data class FaceChangeEvidence(
        val markerRecorded: Boolean,
        val notificationsAfterMarker: Int,
        val characteristicsAfterMarker: List<String>,
        val explanation: String
    )

    /**
     * Groups only events AFTER the local tap marker. Correlation is temporal;
     * a changed packet does NOT imply watch-face protocol support.
     */
    fun evaluateFaceChange(events: List<String>): FaceChangeEvidence {
        val index = events.indexOfLast {
            it.contains("MARK: user is changing the physical WATCH face now")
        }
        if (index == -1) {
            return FaceChangeEvidence(false, 0, emptyList(),
                "No physical watch-face action was marked.")
        }
        val uuidPrefix = Regex("""NOTIFY (6e40000[5-9bcd])""")
        val channels = events.drop(index + 1).mapNotNull { event ->
            uuidPrefix.find(event)?.groupValues?.getOrNull(1)
        }
        val found = channels.distinct()
        return FaceChangeEvidence(true, channels.size, found,
            if (channels.isEmpty()) {
                "No notifications followed the face-change marker. This does not " +
                    "prove that the watch lacks a local face setting."
            } else {
                channels.size.toString() + " notification(s) followed the marker on " +
                    found.joinToString(", ") + ". Timing alone does NOT prove " +
                    "those messages encode a watch-face change."
            })
    }

    data class QueryEvidence(
        val queryRecorded: Boolean,
        val gattWriteAccepted: Boolean,
        val postQueryNotifications: Int,
        val postQueryCommandGroupNotifications: Int,
        val explanation: String,
        val jieliFeatureReply: Boolean = false
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
        val jieliReply = after.any { it.contains("JIELI RCSP feature reply frame") }
        val explanation = when {
            jieliReply ->
                "Valid JieLi GET_TARGET_FEATURE reply frame received after query. " +
                    "This supports RCSP transport only; watchface compatibility is NOT proven."
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
        return QueryEvidence(true, accepted, notifications.size, commandGroup,
            explanation, jieliFeatureReply = jieliReply)
    }
}
