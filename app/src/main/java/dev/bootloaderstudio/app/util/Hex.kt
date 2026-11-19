package dev.bootloaderstudio.app.util

import okio.ByteString

object Hex {

    fun encode(bytes: ByteArray): String = ByteString.of(*bytes).hex()

    fun decode(value: String): ByteArray {
        val cleaned = value.replace(Regex("\\s"), "")
        require(cleaned.length % 2 == 0) { "Hex string must have an even length" }
        require(cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Hex string contains invalid characters"
        }
        return ByteArray(cleaned.length / 2) { index ->
            cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun md5(value: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
        return encode(digest)
    }
}
