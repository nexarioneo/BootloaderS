package dev.bootloaderstudio.app.fastboot

enum class FastbootPacketType {
    OKAY,
    FAIL,
    INFO,
    DATA,
    TEXT,
    UNKNOWN
}

data class FastbootPacket(
    val type: FastbootPacketType,
    val text: String = "",
    val maxSize: Int = 0
)

enum class FastbootErrorKind {
    Disconnected,
    Timeout,
    Other
}

data class FastbootResponse(
    val ok: Boolean,
    val text: String,
    val raw: String,
    val error: FastbootErrorKind? = null
)

class FastbootException(
    message: String,
    val kind: FastbootErrorKind = FastbootErrorKind.Other,
    cause: Throwable? = null
) : Exception(message, cause)

object FastbootProtocol {

    fun parse(buffer: ByteArray, length: Int, offset: Int = 0): FastbootPacket? {
        if (length < 4) return null
        if (offset < 0 || offset + length > buffer.size) return null

        val prefix = String(buffer, offset, 4, Charsets.UTF_8)
        val payload = if (length > 4) {
            String(buffer, offset + 4, length - 4, Charsets.UTF_8)
                .trim('\u0000', '\n', '\r', ' ')
        } else {
            ""
        }

        return when (prefix) {
            "OKAY" -> FastbootPacket(FastbootPacketType.OKAY, payload)
            "FAIL" -> FastbootPacket(FastbootPacketType.FAIL, payload)
            "INFO" -> FastbootPacket(FastbootPacketType.INFO, payload)
            "TEXT" -> FastbootPacket(FastbootPacketType.TEXT, payload)
            "DATA" -> FastbootPacket(
                type = FastbootPacketType.DATA,
                text = payload,
                maxSize = payload.take(8).trim().toIntOrNull(16) ?: 0
            )
            else -> FastbootPacket(
                type = FastbootPacketType.UNKNOWN,
                text = String(buffer, offset, length, Charsets.UTF_8)
            )
        }
    }

    fun parseValue(text: String, name: String): String? {
        val cleaned = cleanLines(text)
        if (cleaned.isBlank()) return null

        val pattern = Regex(
            "(?im)^\\s*${Regex.escape(name)}\\s*[:=]\\s*(.+?)\\s*$"
        )
        pattern.find(cleaned)?.groupValues?.getOrNull(1)?.let { value ->
            return value.trim().takeIf { it.isNotEmpty() }
        }

        val inlinePattern = Regex(
            "(?i)${Regex.escape(name)}\\s*[:=]\\s*([^\\s]+)"
        )
        inlinePattern.find(cleaned)?.groupValues?.getOrNull(1)?.let { value ->
            return value.trim().takeIf { it.isNotEmpty() }
        }

        if (!cleaned.contains("FAIL", true) && !cleaned.contains("OKAY", true)) {
            return cleaned.lines().firstOrNull { it.isNotBlank() }?.trim()
        }
        return null
    }

    fun cleanLines(text: String): String {
        return text.lineSequence()
            .map { line ->
                line.trim()
                    .removePrefix("INFO")
                    .removePrefix("(bootloader)")
                    .removePrefix("OKAY")
                    .removePrefix("FAIL")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
