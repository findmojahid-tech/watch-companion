package com.mojahid.watchcompanion

import org.junit.Assert.*
import org.junit.Test

class GoodixDfuInspectorTest {
    /** Synthetic test data only; this helper is not part of the Android app. */
    private fun testFrame(command: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(
            0x47, 0x44,
            (command and 0xff).toByte(), (command shr 8).toByte(),
            (payload.size and 0xff).toByte(), (payload.size shr 8).toByte()
        )
        // Checksum bytes are placeholders. Goodix's checksum implementation
        // must be verified before it could ever be used for a device protocol.
        return header + payload + byteArrayOf(0, 0)
    }

    @Test fun inspectGetFlashInformationWithoutWriting() {
        val packet = GoodixDfuInspector.inspect(testFrame(0x002B, byteArrayOf()))!!
        assertEquals("Get Flash Information", packet.commandName)
        assertEquals(GoodixDfuInspector.Risk.READ_REQUEST, packet.risk)
        assertNull(packet.address)
    }

    @Test fun findDangerousProgramFlashAndAddress() {
        val payload = byteArrayOf(
            0x10,             // External flash, erase + program
            0x78, 0x56, 0x34, 0x12,
            0x01, 0x00,
            0xFF.toByte()
        )
        val packet = GoodixDfuInspector.inspect(testFrame(0x0024, payload))!!
        assertEquals(GoodixDfuInspector.Risk.FLASH_WRITE, packet.risk)
        assertEquals(0x12345678L, packet.address)
    }

    @Test fun classifyDataProgramStartAsPotentiallyDestructive() {
        val payload = byteArrayOf(
            0x01,             // External flash
            0x00, 0x00, 0x04, 0x01, // 0x01040000
            0x04, 0x00, 0x00, 0x00
        )
        val packet = GoodixDfuInspector.inspect(testFrame(0x0023, payload))!!
        assertEquals(GoodixDfuInspector.Risk.FLASH_WRITE, packet.risk)
        assertEquals("external", packet.flashType)
        assertEquals(0x01040000L, packet.address)
    }

    @Test fun parseAckAsResponse() {
        val packet = GoodixDfuInspector.inspect(
            testFrame(0x0024, byteArrayOf(0x01))
        )!!
        assertEquals(true, packet.responseAccepted)
        assertNull(packet.address)
    }

    @Test fun rejectBadHeadersAndTruncatedFrames() {
        assertNull(GoodixDfuInspector.inspect(byteArrayOf()))
        assertNull(GoodixDfuInspector.inspect(byteArrayOf(0x47, 0x44)))
        val valid = testFrame(0x0024, byteArrayOf(0x01, 0x02))
        assertNull(GoodixDfuInspector.inspect(valid.copyOfRange(0, valid.size - 1)))
        assertNull(GoodixDfuInspector.inspect(valid.copyOf().apply { this[0] = 0x00 }))
    }

    @Test fun rejectOversizedPayloads() {
        assertNull(GoodixDfuInspector.inspect(
            testFrame(0x0024, ByteArray(2049))
        ))
    }

    @Test fun parseTextHexWithoutSendingIt() {
        val bytes = GoodixDfuInspector.fromHex("47-44-2b-00-00-00-00-00")
        assertNotNull(bytes)
        assertEquals(0x002B, GoodixDfuInspector.inspect(bytes!!)!!.command)
        assertNull(GoodixDfuInspector.fromHex("hack something"))
        assertNull(GoodixDfuInspector.fromHex("47:44:2B"))
    }
}
