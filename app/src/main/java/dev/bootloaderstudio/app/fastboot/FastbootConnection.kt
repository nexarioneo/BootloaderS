package dev.bootloaderstudio.app.fastboot

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException
import kotlin.math.min

class FastbootConnection(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
    private val defaultTimeoutMs: Int = 10_000
) : FastbootTransport {

    private var closed = false
    private val readBuffer = ByteArray(READ_BUFFER_SIZE)

    fun execute(command: String, timeoutMs: Int = defaultTimeoutMs): FastbootResponse {
        write(command.toByteArray(Charsets.UTF_8), timeoutMs)
        val response = readResponse(timeoutMs)
        response.error?.let { kind ->
            throw FastbootException(
                response.text.ifBlank { transportFailureMessage(kind) },
                kind
            )
        }
        return response
    }

    override fun getVar(name: String, timeoutMs: Int): String? {
        val response = execute("getvar:$name", timeoutMs)
        if (!response.ok) return null
        return FastbootProtocol.parseValue(response.text, name)
    }

    override fun oemGetToken(timeoutMs: Int): String? {
        val response = execute("oem get_token", timeoutMs)
        if (!response.ok) return null
        return FastbootProtocol.parseValue(response.text, "token")
            ?: FastbootProtocol.cleanLines(response.text)
                .lines()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
    }

    override fun download(data: ByteArray, timeoutMs: Int): FastbootResponse {
        if (data.isEmpty()) {
            throw FastbootException("There is no unlock data to send.")
        }
        if (data.size.toLong() > MAX_DOWNLOAD_BYTES) {
            throw FastbootException("The unlock data is too large for fastboot.")
        }

        val command = "download:${data.size.toString(16).padStart(8, '0')}"
        write(command.toByteArray(Charsets.UTF_8), timeoutMs)

        val reader = FastbootPacketReader()
        val dataPacket = readDownloadPacket(reader, timeoutMs)
        when (dataPacket.type) {
            FastbootPacketType.FAIL -> return dataPacket.toResponse(ok = false)
            FastbootPacketType.OKAY -> return dataPacket.toResponse(ok = true)
            FastbootPacketType.DATA -> Unit
            else -> throw FastbootException(
                "The device returned an unexpected response to the download command."
            )
        }

        val maxSize = dataPacket.maxSize
        if (maxSize <= 0) {
            throw FastbootException("The device returned an invalid download size.")
        }

        sendData(data, min(maxSize, MAX_CHUNK_SIZE), timeoutMs)
        val response = readResponse(maxOf(timeoutMs, FINAL_DOWNLOAD_TIMEOUT_MS))
        response.error?.let { kind ->
            throw FastbootException(
                response.text.ifBlank { transportFailureMessage(kind) },
                kind
            )
        }
        return response
    }

    override fun oemUnlock(timeoutMs: Int): FastbootResponse {
        return execute("oem unlock", timeoutMs)
    }

    private fun write(bytes: ByteArray, timeoutMs: Int) {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
        var offset = 0
        while (offset < bytes.size) {
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) {
                throw FastbootException(
                    transportFailureMessage(FastbootErrorKind.Timeout),
                    FastbootErrorKind.Timeout
                )
            }
            val sent = connection.bulkTransfer(
                endpointOut,
                bytes,
                offset,
                bytes.size - offset,
                remaining
            )
            if (sent <= 0) {
                val kind = if (remainingMillis(deadline) <= 0) {
                    FastbootErrorKind.Timeout
                } else {
                    FastbootErrorKind.Disconnected
                }
                throw FastbootException(transportFailureMessage(kind), kind)
            }
            offset += sent
        }
    }

    private fun readDownloadPacket(
        reader: FastbootPacketReader,
        timeoutMs: Int
    ): FastbootPacket {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
        val messages = StringBuilder()
        var failure: FastbootErrorKind? = null

        while (true) {
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) {
                failure = FastbootErrorKind.Timeout
                break
            }

            val read = readChunk(remaining)
            if (read <= 0) {
                failure = if (remainingMillis(deadline) <= 0) {
                    FastbootErrorKind.Timeout
                } else {
                    FastbootErrorKind.Disconnected
                }
                break
            }

            reader.feed(readBuffer, read).forEach { packet ->
                when (packet.type) {
                    FastbootPacketType.INFO,
                    FastbootPacketType.TEXT,
                    FastbootPacketType.UNKNOWN -> appendPacket(messages, packet)

                    FastbootPacketType.DATA,
                    FastbootPacketType.OKAY,
                    FastbootPacketType.FAIL -> return packet
                }
            }
        }

        reader.flush().forEach { packet ->
            when (packet.type) {
                FastbootPacketType.INFO,
                FastbootPacketType.TEXT,
                FastbootPacketType.UNKNOWN -> appendPacket(messages, packet)

                FastbootPacketType.DATA,
                FastbootPacketType.OKAY,
                FastbootPacketType.FAIL -> return packet
            }
        }

        val detail = FastbootProtocol.cleanLines(messages.toString())
        val kind = failure ?: FastbootErrorKind.Timeout
        throw FastbootException(
            detail.ifBlank { transportFailureMessage(kind) },
            kind
        )
    }

    private fun readResponse(timeoutMs: Int): FastbootResponse {
        val reader = FastbootPacketReader()
        val text = StringBuilder()
        var terminal: FastbootPacketType? = null
        var failure: FastbootErrorKind? = null
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI

        while (terminal == null) {
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) {
                failure = FastbootErrorKind.Timeout
                break
            }

            val read = readChunk(remaining)
            if (read <= 0) {
                failure = if (remainingMillis(deadline) <= 0) {
                    FastbootErrorKind.Timeout
                } else {
                    FastbootErrorKind.Disconnected
                }
                break
            }

            for (packet in reader.feed(readBuffer, read)) {
                appendPacket(text, packet)
                if (packet.type == FastbootPacketType.OKAY ||
                    packet.type == FastbootPacketType.FAIL
                ) {
                    terminal = packet.type
                    break
                }
            }
        }

        if (terminal == null) {
            for (packet in reader.flush()) {
                appendPacket(text, packet)
                if (packet.type == FastbootPacketType.OKAY ||
                    packet.type == FastbootPacketType.FAIL
                ) {
                    terminal = packet.type
                    break
                }
            }
        }

        val raw = text.toString()
        return FastbootResponse(
            ok = terminal == FastbootPacketType.OKAY,
            text = FastbootProtocol.cleanLines(raw),
            raw = raw,
            error = if (terminal == null) {
                failure ?: FastbootErrorKind.Timeout
            } else {
                null
            }
        )
    }

    private fun readChunk(timeoutMs: Int): Int {
        return connection.bulkTransfer(endpointIn, readBuffer, readBuffer.size, timeoutMs)
    }

    private fun appendPacket(builder: StringBuilder, packet: FastbootPacket) {
        if (packet.text.isEmpty()) return
        if (builder.isNotEmpty() && !builder.endsWith("\n")) {
            builder.append('\n')
        }
        builder.append(packet.text)
    }

    private fun sendData(data: ByteArray, chunkSize: Int, timeoutMs: Int) {
        val deadline = System.nanoTime() +
            maxOf(timeoutMs.toLong(), DOWNLOAD_TOTAL_TIMEOUT_MS) * NANOS_PER_MILLI
        var offset = 0

        while (offset < data.size) {
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) {
                throw FastbootException(
                    "Timed out while staging unlock data to the device.",
                    FastbootErrorKind.Timeout
                )
            }

            val length = min(chunkSize, data.size - offset)
            val sent = connection.bulkTransfer(
                endpointOut,
                data,
                offset,
                length,
                remaining
            )
            if (sent <= 0) {
                val kind = if (remainingMillis(deadline) <= 0) {
                    FastbootErrorKind.Timeout
                } else {
                    FastbootErrorKind.Disconnected
                }
                throw FastbootException(
                    "Failed to stage unlock data to the device.",
                    kind
                )
            }
            offset += sent
        }
    }

    private fun transportFailureMessage(kind: FastbootErrorKind): String = when (kind) {
        FastbootErrorKind.Timeout -> "The device did not respond in time."
        FastbootErrorKind.Disconnected -> "The device disconnected during USB communication."
        FastbootErrorKind.Other -> "USB communication failed."
    }

    private fun remainingMillis(deadlineNanos: Long): Int {
        val remaining = (deadlineNanos - System.nanoTime()) / NANOS_PER_MILLI
        return when {
            remaining <= 0 -> 0
            remaining > Int.MAX_VALUE -> Int.MAX_VALUE
            else -> remaining.toInt()
        }
    }

    private fun FastbootPacket.toResponse(ok: Boolean): FastbootResponse {
        return FastbootResponse(
            ok = ok,
            text = FastbootProtocol.cleanLines(text),
            raw = text
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            connection.releaseInterface(iface)
        } catch (_: Exception) {
            // Ignore cleanup failures.
        }
        try {
            connection.close()
        } catch (_: IOException) {
            // Ignore cleanup failures.
        }
    }

    companion object {
        private const val READ_BUFFER_SIZE = 16 * 1024
        private const val MAX_CHUNK_SIZE = 16 * 1024
        private const val MAX_DOWNLOAD_BYTES = 0xFFFFFFFFL
        private const val FINAL_DOWNLOAD_TIMEOUT_MS = 30_000
        private const val DOWNLOAD_TOTAL_TIMEOUT_MS = 60_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
