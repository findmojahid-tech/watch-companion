package com.mojahid.watchcompanion

import org.junit.Assert.*
import org.junit.Test

/** Actual Pixel 7 / Watch 7 firmware 4.21 report, without MAC or private data. */
class Watch7GattProfileTest {
    private val services = listOf(
        "a6ed0401-d344-460a-8075-b9e8ec90d71b",
        "6e400004-b5a3-f393-e0a9-e50e24dcca9e",
        "6e40000a-b5a3-f393-e0a9-e50e24dcca9e"
    )
    private val properties = listOf(
        "a6ed0401-d344-460a-8075-b9e8ec90d71b -> a6ed0402-d344-460a-8075-b9e8ec90d71b [NOTIFY]",
        "a6ed0401-d344-460a-8075-b9e8ec90d71b -> a6ed0403-d344-460a-8075-b9e8ec90d71b [WRITE_NO_RESPONSE]",
        "a6ed0401-d344-460a-8075-b9e8ec90d71b -> a6ed0404-d344-460a-8075-b9e8ec90d71b [WRITE_NO_RESPONSE]",
        "6e400004-b5a3-f393-e0a9-e50e24dcca9e -> 6e400005-b5a3-f393-e0a9-e50e24dcca9e [READ|NOTIFY]",
        "6e400004-b5a3-f393-e0a9-e50e24dcca9e -> 6e400006-b5a3-f393-e0a9-e50e24dcca9e [READ|NOTIFY]",
        "6e400004-b5a3-f393-e0a9-e50e24dcca9e -> 6e400007-b5a3-f393-e0a9-e50e24dcca9e [READ|NOTIFY]",
        "6e400004-b5a3-f393-e0a9-e50e24dcca9e -> 6e400008-b5a3-f393-e0a9-e50e24dcca9e [READ|NOTIFY]",
        "6e400004-b5a3-f393-e0a9-e50e24dcca9e -> 6e400009-b5a3-f393-e0a9-e50e24dcca9e [READ|NOTIFY]",
        "6e40000a-b5a3-f393-e0a9-e50e24dcca9e -> 6e40000b-b5a3-f393-e0a9-e50e24dcca9e [WRITE|WRITE_NO_RESPONSE|NOTIFY]",
        "6e40000a-b5a3-f393-e0a9-e50e24dcca9e -> 6e40000c-b5a3-f393-e0a9-e50e24dcca9e [WRITE|WRITE_NO_RESPONSE|NOTIFY]",
        "6e40000a-b5a3-f393-e0a9-e50e24dcca9e -> 6e40000d-b5a3-f393-e0a9-e50e24dcca9e [NOTIFY]"
    )

    @Test fun realFirmware421ReportMatchesExactSignature() {
        val result = Watch7GattProfile.assess("4.21", "1.0", services, properties)
        assertTrue(result.exactFirmware421Profile)
        assertEquals(5, result.readOnlyVendorChannels)
        assertEquals(2, result.unverifiedVendorWriters)
        assertEquals(2, result.otaWriteChannels)
        assertFalse(result.dialTransferVerified)
    }

    @Test fun firmwareChangeIsNotSilentlyTrusted() {
        assertFalse(Watch7GattProfile.assess("4.22", "1.0", services, properties)
            .exactFirmware421Profile)
        assertFalse(Watch7GattProfile.assess("4.21", "2.0", services, properties)
            .exactFirmware421Profile)
    }

    @Test fun missingOrChangedCharacteristicFailsClosed() {
        assertFalse(Watch7GattProfile.assess("4.21", "1.0", services, properties.dropLast(1))
            .exactFirmware421Profile)
        val changed = properties.toMutableList()
        changed[8] = changed[8].replace("WRITE|WRITE_NO_RESPONSE|NOTIFY", "NOTIFY")
        assertFalse(Watch7GattProfile.assess("4.21", "1.0", services, changed)
            .exactFirmware421Profile)
    }

    @Test fun extraOrDifferentServiceFailsClosed() {
        val result = Watch7GattProfile.assess("4.21", "1.0",
            services + "6e400001-b5a3-f393-e0a9-e50e24dcca9e", properties)
        assertFalse(result.matchingServices)
        assertFalse(result.exactFirmware421Profile)
        assertFalse(result.dialTransferVerified)
    }

    @Test fun writeCapableUuidNeverMeansValidatedDialUpload() {
        val result = Watch7GattProfile.assess("4.21", "1.0", services, properties)
        assertTrue(result.explanation.contains("UNVERIFIED"))
        assertTrue(result.explanation.contains("blocked"))
        assertFalse(result.dialTransferVerified)
    }
}
