package com.mojahid.watchcompanion

/**
 * Fail-closed, read-only interpretation of the owner's actual Watch 7 GATT
 * discovery (Microwear W17 Pro, firmware 4.21, hardware 1.0).
 *
 * GATT properties describe permitted operations, NOT the proprietary command
 * or payload format. Nothing here authorizes dial, resource, or OTA transfers.
 */
object Watch7GattProfile {
    private const val GOODIX = "a6ed0401-d344-460a-8075-b9e8ec90d71b"
    private const val READ_GROUP = "6e400004-b5a3-f393-e0a9-e50e24dcca9e"
    private const val COMMAND_GROUP = "6e40000a-b5a3-f393-e0a9-e50e24dcca9e"
    private const val UUID_SUFFIX = "-b5a3-f393-e0a9-e50e24dcca9e"

    private val expected = buildMap<Pair<String, String>, Set<String>> {
        fun add(service: String, characteristic: String, vararg flags: String) {
            put(service to characteristic, flags.toSet())
        }
        add(GOODIX, "a6ed0402-d344-460a-8075-b9e8ec90d71b", "NOTIFY")
        add(GOODIX, "a6ed0403-d344-460a-8075-b9e8ec90d71b", "WRITE_NO_RESPONSE")
        add(GOODIX, "a6ed0404-d344-460a-8075-b9e8ec90d71b", "WRITE_NO_RESPONSE")
        for (n in 5..9) {
            add(READ_GROUP, "6e40000$n$UUID_SUFFIX", "READ", "NOTIFY")
        }
        for (n in listOf("b", "c")) {
            add(COMMAND_GROUP, "6e40000$n$UUID_SUFFIX", "WRITE", "WRITE_NO_RESPONSE", "NOTIFY")
        }
        add(COMMAND_GROUP, "6e40000d$UUID_SUFFIX", "NOTIFY")
    }

    private val entryPattern = Regex(
        """^([0-9a-fA-F-]{36})\s*->\s*([0-9a-fA-F-]{36})\s*\[([A-Z_|]+)]$"""
    )

    data class Assessment(
        val exactFirmware421Profile: Boolean,
        val matchingServices: Boolean,
        val readOnlyVendorChannels: Int,
        val unverifiedVendorWriters: Int,
        val otaWriteChannels: Int,
        /** False until a firmware-specific, reversible transfer is verified. */
        val dialTransferVerified: Boolean,
        val explanation: String
    )

    fun assess(
        firmware: String,
        hardware: String,
        vendorServices: List<String>,
        vendorCharacteristics: List<String>
    ): Assessment {
        val services = vendorServices.map { it.lowercase() }.toSet()
        val matchingServices = services == setOf(GOODIX, READ_GROUP, COMMAND_GROUP)
        val entries = vendorCharacteristics.mapNotNull { raw ->
            val match = entryPattern.matchEntire(raw.trim()) ?: return@mapNotNull null
            (match.groupValues[1].lowercase() to match.groupValues[2].lowercase()) to
                match.groupValues[3].split('|').toSet()
        }
        val observed = entries.toMap()
        val exact = firmware.trim() == "4.21" && hardware.trim() == "1.0" &&
            matchingServices && entries.size == expected.size && observed == expected
        val readOnly = observed.count { (key, flags) ->
            key.first == READ_GROUP && flags.contains("READ") &&
                flags.none { it == "WRITE" || it == "WRITE_NO_RESPONSE" }
        }
        val vendorWriters = observed.count { (key, flags) ->
            key.first == COMMAND_GROUP &&
                ("WRITE" in flags || "WRITE_NO_RESPONSE" in flags)
        }
        val otaWriters = observed.count { (key, flags) ->
            key.first == GOODIX && ("WRITE" in flags || "WRITE_NO_RESPONSE" in flags)
        }
        val explanation = when {
            exact -> "Watch 7 firmware 4.21 GATT signature matched: " +
                "$readOnly read-only vendor characteristics, " +
                "$vendorWriters UNVERIFIED vendor write endpoints, " +
                "$otaWriters Goodix-style OTA-associated write endpoints. " +
                "The 0402/0403 properties differ from Goodix's published GR551x layout. " +
                "No confirmed face-transfer command. Goodix OTA and arbitrary " +
                "vendor writes are blocked; the optional one-shot ID query is experimental."
            matchingServices -> "Watch 7 vendor service family detected, but firmware, " +
                "hardware, or characteristic flags differ from the recorded 4.21 profile. " +
                "No face-transfer command is verified; do not send vendor/OTA writes."
            else -> "Unknown watch BLE profile. Proprietary dial transfer is disabled; " +
                "only standard GATT information may be read."
        }
        return Assessment(
            exactFirmware421Profile = exact,
            matchingServices = matchingServices,
            readOnlyVendorChannels = readOnly,
            unverifiedVendorWriters = vendorWriters,
            otaWriteChannels = otaWriters,
            dialTransferVerified = false,
            explanation = explanation
        )
    }
}
