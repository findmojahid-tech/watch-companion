package com.mojahid.watchcompanion

/**
 * Pure OFFLINE inspector for Goodix GR551x OTA packets captured from a
 * manufacturer-approved BLE trace. It cannot open Bluetooth connections and
 * deliberately implements NO serialization, writes, erase or DFU commands.
 *
 * Source: Goodix GR551x DFU Application Note Rev 1.8, sections 2.4-2.5.
 * This is NOT a Watch 7 resource/dial layout or transfer implementation.
 */
object GoodixDfuInspector {
    enum class Risk {
        READ_REQUEST,
        FLASH_WRITE,
        FLASH_CONFIGURATION,
        UNKNOWN
    }

    data class Packet(
        val command: Int,
        val commandName: String,
        val payloadLength: Int,
        val rawChecksum: Int,
        val risk: Risk,
        val flashType: String? = null,
        val address: Long? = null,
        val responseAccepted: Boolean? = null
    )

    private val names = mapOf(
        0x0023 to "Program Start",
        0x0024 to "Program Flash",
        0x0025 to "Program End",
        0x002A to "Configure External Flash",
        0x002B to "Get Flash Information"
    )

    private fun le16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun le32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xffL) or
            ((data[offset + 1].toLong() and 0xffL) shl 8) or
            ((data[offset + 2].toLong() and 0xffL) shl 16) or
            ((data[offset + 3].toLong() and 0xffL) shl 24)

    /** Accepts hex with spaces, dashes, colons or an optional 0x prefix. */
    fun fromHex(input: String): ByteArray? {
        val normalized = input.replace(
            Regex("""(?i)0x"""), ""
        ).replace(Regex("""[\s,:\-]"""), "")
        if (normalized.length < 16 || normalized.length % 2 != 0 ||
            normalized.length > 2 * (2048 + 8) ||
            !normalized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        ) return null
        return runCatching {
            ByteArray(normalized.length / 2) { index ->
                normalized.substring(2 * index, 2 * index + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    fun inspect(data: ByteArray): Packet? {
        if (data.size < 8 || data[0] != 0x47.toByte() ||
            data[1] != 0x44.toByte()
        ) return null

        val command = le16(data, 2)
        val declaredLength = le16(data, 4)
        if (declaredLength > 2048 || declaredLength + 8 != data.size) return null

        val response = if (declaredLength == 1) {
            when (data[6].toInt() and 0xff) {
                1 -> true
                2 -> false
                else -> null
            }
        } else null

        val risk = when (command) {
            0x0023, 0x0024, 0x0025 -> Risk.FLASH_WRITE
            0x002A -> Risk.FLASH_CONFIGURATION
            0x002B -> Risk.READ_REQUEST
            else -> Risk.UNKNOWN
        }
        val request = response == null
        val flashType = if (request && command == 0x0023 && declaredLength >= 9) {
            when (data[6].toInt() and 0xff) {
                0 -> "internal"
                1 -> "external"
                else -> "unrecognized"
            }
        } else null

        // The Program Start resource variant and Program Flash command encode
        // memory addresses at different offsets, per the published Goodix spec.
        val address = when {
            request && command == 0x0023 && declaredLength == 9 -> le32(data, 7)
            request && command == 0x0024 && declaredLength >= 7 -> le32(data, 7)
            else -> null
        }

        return Packet(
            command = command,
            commandName = names[command] ?: "Undocumented GR551x command",
            payloadLength = declaredLength,
            rawChecksum = le16(data, data.lastIndex - 1),
            risk = risk,
            flashType = flashType,
            address = address,
            responseAccepted = response
        )
    }
}
