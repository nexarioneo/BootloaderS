package dev.bootloaderstudio.app.xiaomi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class XiaomiSignerTest {

    private val ssecurity = Base64.getEncoder()
        .encodeToString("0123456789abcdef".toByteArray(Charsets.UTF_8))

    @Test
    fun signEncryptsParametersAndAddsSignature() {
        val body = XiaomiSigner.sign(
            path = "/api/v2/nonce",
            paramOrder = listOf("r", "sid"),
            params = linkedMapOf(
                "r" to "abcdefghijklmnop",
                "sid" to XiaomiSigner.SID
            ),
            ssecurity = ssecurity
        )

        val names = (0 until body.size).map { body.name(it) }
        assertEquals(listOf("r", "sid", "sign", "signature"), names)
        assertNotEquals("abcdefghijklmnop", body.value(0))
        assertNotEquals(XiaomiSigner.SID, body.value(1))
        assertTrue(body.value(0).isNotBlank())
        assertTrue(body.value(1).isNotBlank())

        val signature = Base64.getDecoder().decode(body.value(3))
        assertEquals(20, signature.size)
    }

    @Test
    fun signAddsSidWhenMissingFromOrder() {
        val body = XiaomiSigner.sign(
            path = "/api/v2/unlock/device/clear",
            paramOrder = listOf("data", "nonce"),
            params = linkedMapOf(
                "data" to "{\"product\":\"fuxi\"}",
                "nonce" to "nonce-value"
            ),
            ssecurity = ssecurity
        )

        val names = (0 until body.size).map { body.name(it) }
        assertTrue("sid" in names)
        assertEquals(listOf("data", "nonce", "sid", "sign", "signature"), names)
    }

    @Test
    fun base64DataIsEncodedBeforeEncryption() {
        val rawData = "{\"product\":\"fuxi\"}"
        val body = XiaomiSigner.sign(
            path = "/api/v2/unlock/device/clear",
            paramOrder = listOf("data", "nonce", "sid"),
            params = linkedMapOf(
                "data" to rawData,
                "nonce" to "nonce-value",
                "sid" to XiaomiSigner.SID
            ),
            ssecurity = ssecurity
        )

        val key = XiaomiSigner.decodeBase64(ssecurity)
        val decrypted = String(
            XiaomiSigner.decryptBase64(body.value(0), key),
            Charsets.UTF_8
        )
        val expected = Base64.getEncoder().encodeToString(rawData.toByteArray(Charsets.UTF_8))
        assertEquals(expected, decrypted)
        assertFalse(decrypted.contains("\"product\""))
    }

    @Test
    fun decryptResponseReversesTwoLayerEncoding() {
        val key = XiaomiSigner.decodeBase64(ssecurity)
        val json = "{\"code\":0,\"nonce\":\"abc\"}"
        val firstPass = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val encrypted = XiaomiSigner.encryptString(firstPass, key)

        assertEquals(json, XiaomiSigner.decryptResponse(encrypted, ssecurity))
    }

    @Test
    fun decodeBase64AcceptsMissingPadding() {
        val encoded = Base64.getEncoder().encodeToString("hello".toByteArray(Charsets.UTF_8))
        val unpadded = encoded.trimEnd('=')
        assertEquals("hello", String(XiaomiSigner.decodeBase64(unpadded), Charsets.UTF_8))
    }
}
