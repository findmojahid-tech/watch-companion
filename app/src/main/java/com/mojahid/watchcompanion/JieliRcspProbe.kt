package com.mojahid.watchcompanion

/**
 * SPECULATIVE read-only Jieli RCSP capability probe.
 *
 * Some Microwear W17 Pro listings identify a JieLi AC6954 MCU; this does
 * NOT establish that firmware 4.21 exposes RCSP over our 6e40000a GATT
 * service. Send only after user opts into a watch-side experiment.
 *
 * Frame: FE DC BA | opcode 03 (GET_TARGET_FEATURE) | response-required
 * flag 02 | BE payload length 0000 | EF trailer.
 *
 * No firmware, file, watchface, flash address or OS setting is transmitted.
 */
object JieliRcspProbe {
    val readOnlyFeatureQuery: ByteArray
        get() = byteArrayOf(
            0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0x03,
            0x02, 0x00, 0x00, 0xEF.toByte()
        )

    data class Frame(val opcode: Int, val flags: Int, val payload: ByteArray) {
        val isResponse get() = flags and 0x01 != 0
        val requestsResponse get() = flags and 0x02 != 0
    }

    /**
     * Only parse COMPLETE frames with valid sentinel and length.
     * A notification that merely contains FE DC BA does not prove RCSP.
     */
    fun parse(data: ByteArray): Frame? {
        if (data.size !in 8..256) return null
        if (data[0] != 0xFE.toByte() ||
            data[1] != 0xDC.toByte() ||
            data[2] != 0xBA.toByte() ||
            data.last() != 0xEF.toByte()) return null

        val length = ((data[5].toInt() and 0xff) shl 8) or
            (data[6].toInt() and 0xff)
        if (length != data.size - 8) return null
        val opcode = data[3].toInt() and 0xff
        val flags = data[4].toInt() and 0xff
        return Frame(opcode, flags, data.copyOfRange(7, data.size - 1))
    }

    /** Stronger evidence than GATT write success; still not a dial API proof. */
    fun isFeatureReply(data: ByteArray): Boolean {
        val frame = parse(data) ?: return false
        return frame.opcode == 0x03 && frame.isResponse
    }
}
