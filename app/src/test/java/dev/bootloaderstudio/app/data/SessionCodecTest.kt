package dev.bootloaderstudio.app.data

import dev.bootloaderstudio.app.xiaomi.XiaomiSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SessionCodecTest {

    @Test
    fun fullSessionRoundTrips() {
        val session = XiaomiSession(
            passToken = "pass-token",
            deviceId = "device-id",
            userId = "user-id",
            region = "ID",
            host = "unlock.update.miui.com",
            ssecurity = "ssecurity",
            serviceToken = "service-token",
            resolvedAt = 1_700_000_000_000L,
            cookies = mapOf(
                "cUserId" to "c-user",
                "passport_ph" to "ph-value",
                "weird" to "x,y:z\nline"
            )
        )

        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
    }

    @Test
    fun minimalSessionRoundTrips() {
        val session = XiaomiSession(
            passToken = "pass-token",
            deviceId = "device-id",
            userId = "user-id"
        )

        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
    }

    @Test
    fun fieldsContainingSeparatorsRoundTripSafely() {
        val session = XiaomiSession(
            passToken = "pass\ntoken",
            deviceId = "device\r\nid",
            userId = "user=id;with=separators",
            region = "ID",
            host = "host",
            ssecurity = "ssecurity",
            serviceToken = "service-token",
            resolvedAt = 1L
        )

        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
    }

    @Test
    fun missingMandatoryCredentialIsRejected() {
        val session = XiaomiSession(
            passToken = "",
            deviceId = "device-id",
            userId = "user-id"
        )

        assertNull(SessionCodec.decode(SessionCodec.encode(session)))
    }

    @Test
    fun unknownVersionIsRejected() {
        val payload = SessionCodec.encode(minimalSession())
            .toString(Charsets.UTF_8)
            .replaceFirst("v2", "v9")
        assertNull(SessionCodec.decode(payload.toByteArray()))
    }

    @Test
    fun legacyV1PayloadStillDecodes() {
        val fields = listOf(
            "pass-token",
            "device-id",
            "user-id",
            "ID",
            "unlock.update.miui.com",
            "ssecurity",
            "service-token",
            "1700000000000"
        )
        val payload = buildString {
            append("v1")
            fields.forEach { field ->
                append('\n')
                append(
                    Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(field.toByteArray(Charsets.UTF_8))
                )
            }
        }

        val decoded = SessionCodec.decode(payload.toByteArray())

        assertNotNull(decoded)
        assertEquals("pass-token", decoded?.passToken)
        assertEquals("ID", decoded?.region)
        assertTrue(decoded?.cookies?.isEmpty() == true)
    }

    @Test
    fun truncatedPayloadIsRejected() {
        val encoded = SessionCodec.encode(minimalSession()).toString(Charsets.UTF_8)
        val truncated = encoded.substringBeforeLast('\n')
        assertNull(SessionCodec.decode(truncated.toByteArray()))
    }

    @Test
    fun invalidBase64FieldIsRejected() {
        val lines = SessionCodec.encode(minimalSession())
            .toString(Charsets.UTF_8)
            .split("\n")
            .toMutableList()
        lines[1] = "!!!"
        assertNull(SessionCodec.decode(lines.joinToString("\n").toByteArray()))
    }

    @Test
    fun legacyMigrationPreservesResolvedSession() {
        val migrated = SessionCodec.fromLegacy(
            userId = "user-id",
            passToken = "pass-token",
            deviceId = "device-id",
            region = "ID",
            host = "unlock.update.miui.com",
            ssecurity = "ssecurity",
            serviceToken = "service-token",
            resolvedAt = 1_700_000_000_000L
        )

        assertEquals(
            XiaomiSession(
                passToken = "pass-token",
                deviceId = "device-id",
                userId = "user-id",
                region = "ID",
                host = "unlock.update.miui.com",
                ssecurity = "ssecurity",
                serviceToken = "service-token",
                resolvedAt = 1_700_000_000_000L
            ),
            migrated
        )
    }

    @Test
    fun legacyMigrationRejectsIncompleteSession() {
        assertNull(
            SessionCodec.fromLegacy(
                userId = null,
                passToken = "pass-token",
                deviceId = "device-id",
                region = null,
                host = null,
                ssecurity = null,
                serviceToken = null,
                resolvedAt = 0L
            )
        )
        assertNull(
            SessionCodec.fromLegacy(
                userId = "user-id",
                passToken = " ",
                deviceId = "device-id",
                region = null,
                host = null,
                ssecurity = null,
                serviceToken = null,
                resolvedAt = 0L
            )
        )
        assertNull(
            SessionCodec.fromLegacy(
                userId = "user-id",
                passToken = "pass-token",
                deviceId = "",
                region = null,
                host = null,
                ssecurity = null,
                serviceToken = null,
                resolvedAt = 0L
            )
        )
    }

    private fun minimalSession() = XiaomiSession(
        passToken = "pass-token",
        deviceId = "device-id",
        userId = "user-id"
    )
}
