package com.mojahid.watchcompanion

import org.junit.Assert.*
import org.junit.Test

/** Fixtures transcribed from the owner's actual Watch 7, firmware 4.21. */
class Watch7PacketAnalysisTest {
    private fun bytes(hex: String) = hex.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test fun readsVersionCandidateFrom006() {
        val data = bytes("01 32 2E 30 2E 34 00 00 00 00 00 00 00")
        val result = Watch7PacketAnalysis.explainRead(
            "6e400006-b5a3-f393-e0a9-e50e24dcca9e", data)
        assertEquals("version-like: 2.0.4", result)
    }

    @Test fun readsTwoVersionsAndBoardCodeCandidateFrom007() {
        // Partial 36-byte prefix of the 40-byte READ, enough for three text fragments.
        val data = bytes(
            "01 01 01 01 01 00 F0 01 1B 48 30 31 50 00 00 00 00 00 00 " +
                "00 00 00 34 2E 32 31 00 00 32 2E 30 2E 34 00 00 00"
        )
        val result = Watch7PacketAnalysis.explainRead(
            "6e400007-b5a3-f393-e0a9-e50e24dcca9e", data)
        assertEquals("device-code candidate: H01P; version-like: 4.21, 2.0.4", result)
    }

    @Test fun ignoresUnverifiedNumericPayloadsAndRandomTokens() {
        assertNull(Watch7PacketAnalysis.explainRead(
            "6e400009-b5a3-f393-e0a9-e50e24dcca9e",
            bytes("0C 00 0F 01 2A 00 4E 00 00 00")))
        assertNull(Watch7PacketAnalysis.explainRead(
            "6e400007-b5a3-f393-e0a9-e50e24dcca9e",
            bytes("01 03 00 00 61 CF A7 E1 61 CF B6 21")))
    }

    @Test fun sixNotificationsBeforeQueryAreNotMistakenForReply() {
        val events = listOf(
            "READ 6e400006 (13 B): 01 32 2E 30 2E 34",
            "NOTIFY 6e400006 (18 B): 01 01 00 00",
            "NOTIFY 6e400007 (12 B): 01 03 00 00",
            "NOTIFY 6e400007 (12 B): 02 03 00 01",
            "NOTIFY 6e400007 (19 B): 03 03 01 01",
            "NOTIFY 6e400008 (32 B): 19 61 D0 8C",
            "NOTIFY 6e400009 (20 B): 00 00 00 00",
            "TX ID QUERY on 6e40000b: BC 3C 01 01 00 00",
            "ID query GATT accepted; watch reply still unverified."
        )
        val result = Watch7PacketAnalysis.evaluate(events)
        assertTrue(result.queryRecorded)
        assertTrue(result.gattWriteAccepted)
        assertEquals(0, result.postQueryNotifications)
        assertEquals(0, result.postQueryCommandGroupNotifications)
        assertTrue(result.explanation.contains("NO watch notification"))
    }

    @Test fun postQueryNotificationIsOnlyCandidateNotProvenAck() {
        val result = Watch7PacketAnalysis.evaluate(listOf(
            "TX ID QUERY on 6e40000b: BC 3C 01 01 00 00",
            "ID query GATT accepted; watch reply still unverified.",
            "[+120ms] NOTIFY 6e40000d (3 B): 01 02 03"
        ))
        assertEquals(1, result.postQueryNotifications)
        assertEquals(1, result.postQueryCommandGroupNotifications)
        assertTrue(result.explanation.contains("NOT decoded/verified"))
    }

    @Test fun passiveOnlyCaptureDoesNotClaimAck() {
        val result = Watch7PacketAnalysis.evaluate(listOf(
            "NOTIFY 6e400007 (12 B): 01 03 00 00"
        ))
        assertFalse(result.queryRecorded)
        assertFalse(result.gattWriteAccepted)
    }
}
