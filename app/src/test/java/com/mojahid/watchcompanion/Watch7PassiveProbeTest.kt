package com.mojahid.watchcompanion

import org.junit.Assert.*
import org.junit.Test

class Watch7PassiveProbeTest {
    private val suffix = "-b5a3-f393-e0a9-e50e24dcca9e"

    @Test fun onlyFiveDocumentedVendorCharacteristicsAreRead() {
        assertEquals(5, Watch7PassiveProbe.readCharacteristics.size)
        assertEquals(8, Watch7PassiveProbe.notifyCharacteristics.size)
        for (n in 5..9) {
            assertTrue(Watch7PassiveProbe.canRead(
                Watch7PassiveProbe.READ_SERVICE, "6e40000$n$suffix", 0x12))
            assertTrue(Watch7PassiveProbe.canSubscribe(
                Watch7PassiveProbe.READ_SERVICE, "6e40000$n$suffix", 0x12))
        }
        assertFalse(Watch7PassiveProbe.canRead(
            Watch7PassiveProbe.VENDOR_SERVICE, "6e40000b$suffix", 0x1c))
        assertFalse(Watch7PassiveProbe.canRead(
            Watch7PassiveProbe.READ_SERVICE, "6e400004$suffix", 0x12))
    }

    @Test fun notificationAllowlistNeverIncludesOtaOrUnknownEndpoints() {
        for (suffixId in listOf("b", "c", "d")) {
            assertTrue(Watch7PassiveProbe.canSubscribe(
                Watch7PassiveProbe.VENDOR_SERVICE, "6e40000$suffixId$suffix", 0x10))
        }
        assertFalse(Watch7PassiveProbe.canSubscribe(
            "a6ed0401-d344-460a-8075-b9e8ec90d71b",
            "a6ed0402-d344-460a-8075-b9e8ec90d71b", 0x10))
        assertFalse(Watch7PassiveProbe.canSubscribe(
            Watch7PassiveProbe.VENDOR_SERVICE, "6e40000e$suffix", 0x10))
        assertFalse(Watch7PassiveProbe.canSubscribe(
            Watch7PassiveProbe.VENDOR_SERVICE, "6e40000b$suffix", 0x08))
    }

    @Test fun payloadPreviewIsBoundedAndHexOnly() {
        val bytes = ByteArray(128) { it.toByte() }
        val preview = Watch7PassiveProbe.briefHex(bytes)
        assertTrue(preview.startsWith("00 01 02"))
        assertTrue(preview.endsWith(" ..."))
        assertFalse(preview.contains("30 31 32"))
        assertTrue(preview.length < 150)
    }

    @Test fun captureHasFiniteLimits() {
        assertEquals(30_000L, Watch7PassiveProbe.WINDOW_MILLIS)
        assertTrue(Watch7PassiveProbe.MAX_EVENTS in 10..100)
        val line = Watch7PassiveProbe.entry(
            "NOTIFY", "6e40000d-b5a3-f393-e0a9-e50e24dcca9e", byteArrayOf(0x01, 0x02))
        assertEquals("NOTIFY 6e40000d (2 B): 01 02", line)
    }
    @Test fun optionalIdProbeOnlyOnTwoNonOtaWriteWithResponseCharacteristics() {
        for (name in listOf("b", "c")) {
            assertTrue(Watch7PassiveProbe.canProbe(
                Watch7PassiveProbe.VENDOR_SERVICE, "6e40000$name$suffix", 0x1c))
        }
        assertFalse(Watch7PassiveProbe.canProbe(
            Watch7PassiveProbe.VENDOR_SERVICE, "6e40000d$suffix", 0x10))
        assertFalse(Watch7PassiveProbe.canProbe(
            Watch7PassiveProbe.VENDOR_SERVICE, "6e40000b$suffix", 0x04))
        assertFalse(Watch7PassiveProbe.canProbe(
            "a6ed0401-d344-460a-8075-b9e8ec90d71b",
            "a6ed0403-d344-460a-8075-b9e8ec90d71b", 0x08))
    }

    @Test fun speculativeIdentityRequestIsFixedNonFirmwareQuery() {
        assertArrayEquals(
            byteArrayOf(0xBC.toByte(), 0x3C, 0x01, 0x01, 0x00, 0x00),
            Watch7PassiveProbe.speculativeProductIdQuery
        )
        assertEquals(6, Watch7PassiveProbe.speculativeProductIdQuery.size)
    }

}
