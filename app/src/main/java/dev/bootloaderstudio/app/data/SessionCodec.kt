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
 *
 * v2 appends the browser cookie map while still decoding v1 payloads written by
 * older builds.
 */
object SessionCodec {

    private const val VERSION = "v2"
    private const val LEGACY_VERSION = "v1"
    private const val SEPARATOR = "\n"
    private const val FIELD_COUNT = 9
    private const val LEGACY_FIELD_COUNT = 8
    private const val COOKIE_SEPARATOR = ","
    private const val COOKIE_PAIR_SEPARATOR = ":"

    fun encode(session: XiaomiSession): ByteArray {
        val fields = listOf(
            session.passToken,
            session.deviceId,
            session.userId,
            session.region.orEmpty(),
            session.host.orEmpty(),
            session.ssecurity.orEmpty(),
            session.serviceToken.orEmpty(),
            session.resolvedAt?.toString().orEmpty(),
            encodeCookies(session.cookies)
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
        if (lines.isEmpty()) return null

        val expectedFields = when (lines[0]) {
            VERSION -> FIELD_COUNT
            LEGACY_VERSION -> LEGACY_FIELD_COUNT
            else -> return null
        }
        if (lines.size != expectedFields + 1) return null

        val fields = lines.drop(1).map { decodeField(it) ?: return null }
        val passToken = fields[0].takeIf { it.isNotBlank() } ?: return null
        val deviceId = fields[1].takeIf { it.isNotBlank() } ?: return null
        val userId = fields[2].takeIf { it.isNotBlank() } ?: return null
        val cookies = if (fields.size > LEGACY_FIELD_COUNT) {
            decodeCookies(fields[LEGACY_FIELD_COUNT])
        } else {
            emptyMap()
        }

        return XiaomiSession(
            passToken = passToken,
            deviceId = deviceId,
            userId = userId,
            region = fields[3].takeIf { it.isNotBlank() },
            host = fields[4].takeIf { it.isNotBlank() },
            ssecurity = fields[5].takeIf { it.isNotBlank() },
            serviceToken = fields[6].takeIf { it.isNotBlank() },
            resolvedAt = fields[7].toLongOrNull()?.takeIf { it > 0L },
            cookies = cookies
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

    private fun encodeCookies(cookies: Map<String, String>): String {
        return cookies.entries
            .filter { (name, value) -> name.isNotBlank() && value.isNotBlank() }
            .joinToString(COOKIE_SEPARATOR) { (name, value) ->
                encodeField(name) + COOKIE_PAIR_SEPARATOR + encodeField(value)
            }
    }

    private fun decodeCookies(encoded: String): Map<String, String> {
        if (encoded.isBlank()) return emptyMap()
        return buildMap {
            encoded.split(COOKIE_SEPARATOR).forEach { pair ->
                val name = decodeField(pair.substringBefore(COOKIE_PAIR_SEPARATOR))
                val value = decodeField(
                    pair.substringAfter(COOKIE_PAIR_SEPARATOR, "")
                )
                if (!name.isNullOrBlank() && !value.isNullOrBlank()) {
                    put(name, value)
                }
            }
        }
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
