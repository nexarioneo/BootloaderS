package dev.bootloaderstudio.app.xiaomi

import dev.bootloaderstudio.app.R
import dev.bootloaderstudio.app.platform.StringProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiErrorMapperTest {

    @Test
    fun usesOfficialMessageForKnownCode() {
        val text = XiaomiErrorMapper.format(
            XiaomiApiException(10000, "param error"),
            FakeStrings()
        )
        assertTrue(text.startsWith("10000: Request parameter error"))
        assertTrue(text.contains("Server detail: param error"))
        assertTrue(text.contains("Check the account session"))
    }

    @Test
    fun includesOfficialBindingInstructions() {
        val text = XiaomiErrorMapper.format(
            XiaomiApiException(20031, "account not bound"),
            FakeStrings()
        )
        assertTrue(text.contains("20031:"))
        assertTrue(text.contains("Mi Unlock status"))
    }

    @Test
    fun unknownCodeKeepsServerMessage() {
        val text = XiaomiErrorMapper.format(
            XiaomiApiException(99999, "Custom server failure"),
            FakeStrings()
        )
        assertTrue(text.startsWith("Custom server failure"))
        assertTrue(text.contains("Code: 99999"))
    }

    @Test
    fun guidanceOnlyCodeStillShowsTheCode() {
        val text = XiaomiErrorMapper.format(
            XiaomiApiException(10013, "not activated"),
            FakeStrings()
        )
        assertTrue(text.startsWith("not activated"))
        assertTrue(text.contains("Code: 10013"))
        assertTrue(text.contains("activation data is incomplete"))
    }

    @Test
    fun clientSideErrorHasNoCodeSuffix() {
        val text = XiaomiErrorMapper.format(
            XiaomiApiException(-1, "Xiaomi did not return an unlock nonce."),
            FakeStrings()
        )
        assertTrue(text.startsWith("Xiaomi did not return an unlock nonce."))
        assertFalse(text.contains("Code:"))
    }

    @Test
    fun usesTheStringProviderForLocalizedWording() {
        val text = XiaomiErrorMapper.format(
            XiaomiApiException(10000, "param error"),
            FakeStrings(localized = true)
        )
        assertTrue(text.startsWith("10000: Kesalahan parameter permintaan"))
        assertTrue(text.contains("Detail server: param error"))
        assertTrue(text.contains("Periksa sesi akun"))
    }

    private class FakeStrings(
        private val localized: Boolean = false
    ) : StringProvider {
        override fun getString(id: Int): String = when (id) {
            R.string.xiaomi_error_10000_official ->
                if (localized) "Kesalahan parameter permintaan" else "Request parameter error"
            R.string.xiaomi_error_10000_guidance ->
                if (localized) {
                    "Periksa sesi akun, sambungkan kembali ponsel dalam mode fastboot."
                } else {
                    "Check the account session, reconnect the phone in fastboot mode."
                }
            R.string.xiaomi_error_10013_guidance ->
                "The device is not activated yet or its activation data is incomplete."
            R.string.xiaomi_error_20031_official ->
                "Add your account and device in MIUI's Settings > Developer options > " +
                    "Mi Unlock status"
            R.string.xiaomi_error_20031_guidance ->
                "Bind the same account on the phone."
            R.string.error_server_detail_prefix ->
                if (localized) "Detail server: " else "Server detail: "
            R.string.error_code_prefix -> if (localized) "Kode: " else "Code: "
            R.string.error_unknown -> "Unknown error. Try again later."
            else -> "string-$id"
        }
    }
}
