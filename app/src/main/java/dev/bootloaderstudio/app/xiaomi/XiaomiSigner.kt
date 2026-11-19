package dev.bootloaderstudio.app.xiaomi

import okhttp3.FormBody
import okio.ByteString
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reimplementation of the signing scheme used by Xiaomi's Windows Mi Unlock client.
 *
 * This is an unofficial client. Xiaomi's server remains the authority: the request
 * is rejected when the account, region, device, waiting period, or quota do not
 * satisfy the current policy.
 */
object XiaomiSigner {

    const val SID = "miui_unlocktool_client"

    private const val IV = "0102030405060708"
    private const val CIPHER = "AES/CBC/PKCS5Padding"
    private const val HMAC_ALGORITHM = "HmacSHA1"
    private const val HMAC_KEY =
        "2tBeoEyJTunmWUGq7bQH2Abn0k2NhhurOaqBfyxCuLVgn4AVj7swcawe53uDUno"

    fun sign(
        path: String,
        paramOrder: List<String>,
        params: Map<String, String>,
        ssecurity: String
    ): FormBody {
        val key = decodeBase64(ssecurity)
        val values = params.toMutableMap()

        values["data"]?.let { rawData ->
            values["data"] = Base64.getEncoder()
                .encodeToString(rawData.toByteArray(Charsets.UTF_8))
        }
        if (!values.containsKey("sid")) {
            values["sid"] = SID
        }

        val order = paramOrder.toMutableList()
        if ("sid" in values && "sid" !in order) {
            order += "sid"
        }

        val signParams = order.joinToString("&") { name -> "$name=${values.getValue(name)}" }
        val signSource = "POST\n$path\n$signParams"

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        val hmacHex = ByteString.of(*mac.doFinal(signSource.toByteArray(Charsets.UTF_8))).hex()
        val sign = encryptString(hmacHex, key)

        val encryptedParams = order.map { name ->
            "$name=${encryptString(values.getValue(name), key)}"
        }
        val signatureSource =
            "POST&$path&${encryptedParams.joinToString("&")}&sign=$sign&$ssecurity"
        val signature = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA1")
                .digest(signatureSource.toByteArray(Charsets.UTF_8))
        )

        return FormBody.Builder().apply {
            order.forEach { name ->
                add(name, encryptString(values.getValue(name), key))
            }
            add("sign", sign)
            add("signature", signature)
        }.build()
    }

    fun decryptResponse(body: String, ssecurity: String): String {
        val key = decodeBase64(ssecurity)
        val firstPass = decryptBase64(body, key)
        val secondBase64 = String(firstPass, Charsets.UTF_8)
        return String(decodeBase64(secondBase64), Charsets.UTF_8)
    }

    internal fun encryptString(value: String, key: ByteArray): String {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        )
        return Base64.getEncoder()
            .encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    internal fun decryptBase64(value: String, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        )
        return cipher.doFinal(decodeBase64(value))
    }

    internal fun decodeBase64(value: String): ByteArray {
        val cleaned = value.replace(Regex("\\s"), "")
        if (cleaned.isEmpty()) return ByteArray(0)
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        return try {
            Base64.getDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            Base64.getUrlDecoder().decode(padded)
        }
    }
}
