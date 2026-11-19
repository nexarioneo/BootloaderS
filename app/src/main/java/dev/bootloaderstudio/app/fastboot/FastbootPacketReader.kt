package dev.bootloaderstudio.app.fastboot

import java.io.ByteArrayOutputStream

/**
 * Decodes the byte stream returned by a fastboot device into individual packets.
 *
 * A fastboot response is framed as a four-byte ASCII header followed by an
 * optional payload. A single USB transfer can contain several packets (for
 * example `INFO...` immediately followed by `OKAY`), and a packet can also be
 * split across transfers. This reader keeps an incomplete tail buffered until
 * more bytes arrive, or until [flush] is called when the response deadline
 * expires.
 */
class FastbootPacketReader {

    private val pending = ByteArrayOutputStream()

    /**
     * Appends [length] bytes from [data] and returns every packet that is now
     * complete. The last packet of a transfer is emitted when its framing makes
     * it safe to do so (terminal packets, fixed-size DATA headers, or INFO/TEXT
     * lines ending with a newline). Otherwise it stays buffered.
     */
    fun feed(data: ByteArray, length: Int = data.size): List<FastbootPacket> {
        if (length <= 0 || data.isEmpty()) return emptyList()
        pending.write(data, 0, minOf(length, data.size))
        return drain(flush = false)
    }

    /**
     * Emits whatever remains in the buffer, even when it still looks
     * incomplete. Call this once the response deadline has expired so a
     * truncated payload is not silently lost.
     */
    fun flush(): List<FastbootPacket> = drain(flush = true)

    fun reset() {
        pending.reset()
    }

    private fun drain(flush: Boolean): List<FastbootPacket> {
        val bytes = pending.toByteArray()
        val packets = mutableListOf<FastbootPacket>()
        var cursor = 0

        while (bytes.size - cursor >= HEADER_LENGTH) {
            val type = typeAt(bytes, cursor)
            if (type == null) {
                // Resynchronise after noise or a truncated header.
                cursor++
                continue
            }

            val nextHeader = findNextHeader(bytes, cursor + HEADER_LENGTH)
            if (nextHeader == null) {
                val remaining = bytes.size - cursor
                if (!flush && !isComplete(type, bytes, cursor, remaining)) break
                FastbootProtocol.parse(bytes, remaining, cursor)?.let(packets::add)
                cursor = bytes.size
                break
            }

            val packetLength = nextHeader - cursor
            FastbootProtocol.parse(bytes, packetLength, cursor)?.let(packets::add)
            cursor = nextHeader
        }

        pending.reset()
        if (cursor < bytes.size) {
            pending.write(bytes, cursor, bytes.size - cursor)
        }
        return packets
    }

    private fun isComplete(
        type: FastbootPacketType,
        bytes: ByteArray,
        offset: Int,
        length: Int
    ): Boolean {
        return when (type) {
            FastbootPacketType.OKAY,
            FastbootPacketType.FAIL -> true

            FastbootPacketType.DATA -> length >= HEADER_LENGTH + DATA_SIZE_LENGTH

            FastbootPacketType.INFO,
            FastbootPacketType.TEXT -> length == HEADER_LENGTH || endsWithNewline(bytes, offset, length)

            FastbootPacketType.UNKNOWN -> true
        }
    }

    private fun endsWithNewline(bytes: ByteArray, offset: Int, length: Int): Boolean {
        val last = bytes[offset + length - 1].toInt()
        return last == '\n'.code || last == '\r'.code
    }

    private fun findNextHeader(bytes: ByteArray, fromIndex: Int): Int? {
        var index = fromIndex
        while (index + HEADER_LENGTH <= bytes.size) {
            if (typeAt(bytes, index) != null) return index
            index++
        }
        return null
    }

    private fun typeAt(bytes: ByteArray, offset: Int): FastbootPacketType? {
        if (offset < 0 || offset + HEADER_LENGTH > bytes.size) return null
        return when (String(bytes, offset, HEADER_LENGTH, Charsets.US_ASCII)) {
            "OKAY" -> FastbootPacketType.OKAY
            "FAIL" -> FastbootPacketType.FAIL
            "INFO" -> FastbootPacketType.INFO
            "DATA" -> FastbootPacketType.DATA
            "TEXT" -> FastbootPacketType.TEXT
            else -> null
        }
    }

    companion object {
        private const val HEADER_LENGTH = 4
        private const val DATA_SIZE_LENGTH = 8
    }
}
