package com.mojahid.watchcompanion

/**
 * Fixed, device-specific READ/NOTIFY capture plan for the physical Watch 7
 * firmware 4.21 report. No proprietary characteristic WRITE is authorized.
 * Subscriptions only write the standard 0x2902 CCCD, not firmware or dial data.
 */
object Watch7PassiveProbe {
    const val WINDOW_MILLIS = 30_000L
    const val MAX_EVENTS = 48
    private const val SUFFIX = "-b5a3-f393-e0a9-e50e24dcca9e"

    const val READ_SERVICE = "6e400004-b5a3-f393-e0a9-e50e24dcca9e"
    const val VENDOR_SERVICE = "6e40000a-b5a3-f393-e0a9-e50e24dcca9e"

    val readCharacteristics: List<String> =
        (5..9).map { "6e40000${it}$SUFFIX" }

    val notifyCharacteristics: List<String> = readCharacteristics +
        listOf("b", "c", "d").map { "6e40000${it}$SUFFIX" }

    fun canRead(service: String, characteristic: String, flags: Int): Boolean =
        service.equals(READ_SERVICE, ignoreCase = true) &&
            characteristic.lowercase() in readCharacteristics &&
            flags and 0x02 != 0 // BluetoothGattCharacteristic.PROPERTY_READ

    fun canSubscribe(service: String, characteristic: String, flags: Int): Boolean =
        ((service.equals(READ_SERVICE, ignoreCase = true) &&
            characteristic.lowercase() in readCharacteristics) ||
            (service.equals(VENDOR_SERVICE, ignoreCase = true) &&
                characteristic.lowercase() in notifyCharacteristics.takeLast(3))) &&
            flags and 0x10 != 0 // PROPERTY_NOTIFY

    /** Safe, bounded log; raw captured bytes never leave app memory automatically. */
    fun briefHex(bytes: ByteArray, maxBytes: Int = 36): String {
        val preview = bytes.take(maxBytes.coerceIn(1, 64))
            .joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
        return preview + if (bytes.size > maxBytes.coerceIn(1, 64)) " ..." else ""
    }

    fun entry(kind: String, uuid: String, data: ByteArray): String =
        kind.take(12) + " " + uuid.substringBefore('-').lowercase() +
            " (" + data.size + " B): " + briefHex(data)
}
