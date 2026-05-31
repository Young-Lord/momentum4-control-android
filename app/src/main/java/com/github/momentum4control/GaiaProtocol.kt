package com.github.momentum4control

import java.nio.ByteBuffer

object GaiaProtocol {
    val MAGIC = byteArrayOf(0xFF.toByte(), 0x03)
    const val VENDOR_SENNHEISER = 0x0495

    const val CMD_REGISTER_NOTIFICATION = 0x0007
    const val CMD_SET_ANC_STATUS = 0x1A04
    const val CMD_GET_ANC_STATUS = 0x1A05
    const val CMD_SET_TRANSPARENCY = 0x1A02
    const val CMD_GET_TRANSPARENCY = 0x1A03

    const val FEATURE_ANC = 13
    const val FEATURE_TRANSPARENCY = 12

    val COMMON_CHANNELS = intArrayOf(2, 1, 15, 14, 12, 3, 4, 5, 6, 7, 8, 9, 10, 11)

    fun build(command: Int, payload: ByteArray = ByteArray(0), vendor: Int = VENDOR_SENNHEISER): ByteArray {
        val buf = ByteBuffer.allocate(8 + payload.size)
        buf.put(MAGIC)
        buf.putShort(payload.size.toShort())
        buf.putShort(vendor.toShort())
        buf.putShort(command.toShort())
        buf.put(payload)
        return buf.array()
    }

    fun expectedResponse(command: Int): Int = command or 0x0100

    fun errorResponse(command: Int): Int = command or 0x0180

    data class Packet(val vendor: Int, val command: Int, val payload: ByteArray)

    data class ParseResult(val packets: List<Packet>, val leftover: ByteArray)

    fun parseMany(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): ParseResult {
        val packets = mutableListOf<Packet>()
        var pos = offset
        val end = offset + length

        while (end - pos >= 8) {
            if (buffer[pos] != 0xFF.toByte() || buffer[pos + 1] != 0x03.toByte()) {
                val next = findMagic(buffer, pos + 1, end)
                if (next == -1) {
                    val leftover = buffer.copyOfRange(pos, end)
                    return ParseResult(packets, leftover)
                }
                pos = next
                continue
            }
            val payloadLen = ((buffer[pos + 2].toInt() and 0xFF) shl 8) or (buffer[pos + 3].toInt() and 0xFF)
            val total = 8 + payloadLen
            if (end - pos < total) break

            val vendor = ((buffer[pos + 4].toInt() and 0xFF) shl 8) or (buffer[pos + 5].toInt() and 0xFF)
            val command = ((buffer[pos + 6].toInt() and 0xFF) shl 8) or (buffer[pos + 7].toInt() and 0xFF)
            val payload = buffer.copyOfRange(pos + 8, pos + total)
            packets.add(Packet(vendor, command, payload))
            pos += total
        }

        val leftover = if (pos < end) buffer.copyOfRange(pos, end) else ByteArray(0)
        return ParseResult(packets, leftover)
    }

    private fun findMagic(buffer: ByteArray, start: Int, end: Int): Int {
        for (i in start until end - 1) {
            if (buffer[i] == 0xFF.toByte() && buffer[i + 1] == 0x03.toByte()) return i
        }
        return -1
    }
}
