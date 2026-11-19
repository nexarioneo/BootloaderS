package dev.bootloaderstudio.app.fastboot

/**
 * Normalizes values read from fastboot before they are compared or sent to
 * Xiaomi. Fastboot answers can contain stray whitespace or different casing
 * between reads, so comparisons must not depend on the exact raw text.
 */
object FastbootValue {

    fun clean(value: String?): String? {
        val cleaned = value
            ?.trim()
            ?.replace(WHITESPACE, "")
            ?: return null
        return cleaned.ifBlank { null }
    }

    fun normalize(value: String?): String? = clean(value)?.lowercase()

    fun product(value: String?): String? = normalize(value)

    fun token(value: String?): String? = normalize(value)

    fun isSameProduct(expected: String?, actual: String?): Boolean {
        val expectedKey = product(expected) ?: return false
        val actualKey = product(actual) ?: return false
        return expectedKey == actualKey
    }

    fun isSameToken(expectedTokenKey: String?, actual: String?): Boolean {
        val expectedKey = token(expectedTokenKey) ?: return false
        val actualKey = token(actual) ?: return false
        return expectedKey == actualKey
    }

    private val WHITESPACE = Regex("\\s+")
}
