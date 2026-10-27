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
    @Test fun notificationBeforeFaceChangeMarkerIsIgnoredForCorrelation() {
        val result = Watch7PacketAnalysis.evaluateFaceChange(listOf(
            "[+50ms] NOTIFY 6e400007 (12 B): 01 03",
            "[+100ms] MARK: user is changing the physical WATCH face now",
            "[+140ms] NOTIFY 6e400009 (20 B): 00 00",
            "[+160ms] NOTIFY 6e400009 (20 B): 01 00"
        ))
        assertTrue(result.markerRecorded)
        assertEquals(2, result.notificationsAfterMarker)
        assertEquals(listOf("6e400009"), result.characteristicsAfterMarker)
        assertTrue(result.explanation.contains("does NOT prove"))
    }

    @Test fun absentFaceMarkerDoesNotClaimFaceChangeEvidence() {
        val result = Watch7PacketAnalysis.evaluateFaceChange(
            listOf("NOTIFY 6e400007 (12 B): 01 03"))
        assertFalse(result.markerRecorded)
        assertEquals(0, result.notificationsAfterMarker)
    }

    @Test fun validJieliFeatureReplyAfterQueryIsReportedButNotCalledDialSupport() {
        val events = listOf(
            "[+100ms] TX ID QUERY on 6e40000b [JIELI_RCSP]: FE DC BA 03 02 00 00 EF",
            "[+125ms] ID query GATT accepted; watch reply still unverified.",
            "[+155ms] NOTIFY 6e40000d (10 B): FE DC BA 03 01 00 02 08 01 EF",
            "[+155ms] JIELI RCSP feature reply frame from 6e40000d (valid framed device response; dial upload still unverified)"
        )
        val parsed = Watch7PacketAnalysis.evaluate(events)
        assertTrue(parsed.queryRecorded)
        assertTrue(parsed.gattWriteAccepted)
        assertTrue(parsed.jieliFeatureReply)
        assertEquals(1, parsed.postQueryCommandGroupNotifications)
        assertTrue(parsed.explanation.contains("watchface compatibility is NOT proven"))
    }

}
