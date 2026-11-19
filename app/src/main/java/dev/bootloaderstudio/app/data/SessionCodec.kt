package dev.bootloaderstudio.app.data

import dev.bootloaderstudio.app.xiaomi.XiaomiSession
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Versioned, dependency-free encoding for the persisted Xiaomi session.
 *
 * Every field is Base64 (URL-safe, no padding) encoded so tokens can never
 * inject the line separator. The format is deliberately simple and pure Kotlin
 * so it can be round-tripped in JVM unit tests without Android classes.
 */
object SessionCodec {

    private const val VERSION = "v1"
    private const val SEPARATOR = "\n"
    private const val FIELD_COUNT = 8

    fun encode(session: XiaomiSession): ByteArray {
        val fields = listOf(
            session.passToken,
            session.deviceId,
            session.userId,
            session.region.orEmpty(),
            session.host.orEmpty(),
            session.ssecurity.orEmpty(),
            session.serviceToken.orEmpty(),
            session.resolvedAt?.toString().orEmpty()
        )
        val payload = buildString {
            append(VERSION)
            fields.forEach { field ->
                append(SEPARATOR)
                append(encodeField(field))
            }
        }
        return payload.toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(payload: ByteArray): XiaomiSession? {
        val lines = payload.toString(StandardCharsets.UTF_8).split(SEPARATOR)
        if (lines.size != FIELD_COUNT + 1 || lines[0] != VERSION) return null

        val fields = lines.drop(1).map { decodeField(it) ?: return null }
        val passToken = fields[0].takeIf { it.isNotBlank() } ?: return null
        val deviceId = fields[1].takeIf { it.isNotBlank() } ?: return null
        val userId = fields[2].takeIf { it.isNotBlank() } ?: return null

        return XiaomiSession(
            passToken = passToken,
            deviceId = deviceId,
            userId = userId,
            region = fields[3].takeIf { it.isNotBlank() },
            host = fields[4].takeIf { it.isNotBlank() },
            ssecurity = fields[5].takeIf { it.isNotBlank() },
            serviceToken = fields[6].takeIf { it.isNotBlank() },
            resolvedAt = fields[7].toLongOrNull()?.takeIf { it > 0L }
        )
    }

    /**
     * Rebuilds a session from the legacy plaintext preference values.
     *
     * Returns null unless the three mandatory credentials are present. This is
     * also the migration path used on first launch after upgrading from a build
     * that stored the session in plaintext.
     */
    fun fromLegacy(
        userId: String?,
        passToken: String?,
        deviceId: String?,
        region: String?,
        host: String?,
        ssecurity: String?,
        serviceToken: String?,
        resolvedAt: Long
    ): XiaomiSession? {
        val safeUserId = userId?.takeIf { it.isNotBlank() } ?: return null
        val safePassToken = passToken?.takeIf { it.isNotBlank() } ?: return null
        val safeDeviceId = deviceId?.takeIf { it.isNotBlank() } ?: return null

        return XiaomiSession(
            passToken = safePassToken,
            deviceId = safeDeviceId,
            userId = safeUserId,
            region = region?.takeIf { it.isNotBlank() },
            host = host?.takeIf { it.isNotBlank() },
            ssecurity = ssecurity?.takeIf { it.isNotBlank() },
            serviceToken = serviceToken?.takeIf { it.isNotBlank() },
            resolvedAt = resolvedAt.takeIf { it > 0L }
        )
    }

    private fun encodeField(value: String): String {
        if (value.isEmpty()) return ""
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeField(value: String): String? {
        if (value.isEmpty()) return ""
        return runCatching {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        }.getOrNull()
    }
}
