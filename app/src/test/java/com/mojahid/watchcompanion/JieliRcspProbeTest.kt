package com.mojahid.watchcompanion

import org.junit.Assert.*
import org.junit.Test

class JieliRcspProbeTest {
    @Test fun sendsOnlyGetTargetFeatureWithZeroLengthPayload() {
        assertArrayEquals(
            byteArrayOf(
                0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(),
                0x03, 0x02, 0x00, 0x00, 0xEF.toByte()
            ),
            JieliRcspProbe.readOnlyFeatureQuery
        )
    }

    @Test fun parsesCompleteFeatureReplyButNotBareGattAcceptance() {
        val reply = byteArrayOf(
            0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(),
            0x03, 0x01, 0x00, 0x02, 0x08, 0x01, 0xEF.toByte()
        )
        val frame = JieliRcspProbe.parse(reply)
        assertNotNull(frame)
        assertTrue(frame!!.isResponse)
        assertEquals(3, frame.opcode)
        assertArrayEquals(byteArrayOf(0x08, 0x01), frame.payload)
        assertTrue(JieliRcspProbe.isFeatureReply(reply))
        assertFalse(JieliRcspProbe.isFeatureReply(JieliRcspProbe.readOnlyFeatureQuery))
    }

    @Test fun refusesMalformedOrPartialNotifications() {
        val bad = byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(),
            0x03, 0x01, 0x00, 0x02, 0x08, 0xEF.toByte())
        assertNull(JieliRcspProbe.parse(bad)) // claims 2 payload bytes, only 1 present
        assertNull(JieliRcspProbe.parse(byteArrayOf(0x01, 0x03, 0x00, 0x00)))
        assertNull(JieliRcspProbe.parse(ByteArray(260)))
    }

    @Test fun aWatchReadOrNotificationMustNotBeMistakenForFeatureAck() {
        val previousRealWatch = byteArrayOf(
            0x01, 0x03, 0x00, 0x00, 0x61,
            0xCF.toByte(), 0xA7.toByte(), 0xE1.toByte(),
            0x61, 0xCF.toByte(), 0xB6.toByte(), 0x21
        )
        assertFalse(JieliRcspProbe.isFeatureReply(previousRealWatch))
    }
}
