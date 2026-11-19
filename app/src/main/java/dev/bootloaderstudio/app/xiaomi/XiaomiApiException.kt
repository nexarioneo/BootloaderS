package dev.bootloaderstudio.app.xiaomi

import dev.bootloaderstudio.app.R
import dev.bootloaderstudio.app.platform.StringProvider

class XiaomiApiException(
    val code: Int,
    val serverMessage: String,
    val rawResponse: String? = null
) : Exception(serverMessage)

/**
 * Maps server error codes to the wording used by Xiaomi's official Mi Unlock
 * client (miflash_unlock-en-6.5.224.28). Every human-readable string is read
 * from resources so the message follows the app locale. Unknown codes keep the
 * server message so the app never hides what Xiaomi actually returned.
 */
object XiaomiErrorMapper {

    fun format(error: XiaomiApiException, strings: StringProvider): String {
        val server = error.serverMessage.trim()
        val official = officialMessageFor(error.code)?.let(strings::getString)
        val guidance = guidanceFor(error.code)?.let(strings::getString).orEmpty()

        return buildString {
            when {
                official != null -> {
                    append(error.code)
                    append(": ")
                    append(official)
                }
                server.isNotBlank() -> append(server)
                else -> append(strings.getString(R.string.error_unknown))
            }

            if (official != null && server.isNotBlank() &&
                !server.equals(official, ignoreCase = true)
            ) {
                append("\n\n")
                append(strings.getString(R.string.error_server_detail_prefix))
                append(server)
            }

            if (guidance.isNotBlank()) {
                append("\n\n")
                append(guidance)
            }

            if (official == null && error.code != -1) {
                append("\n\n")
                append(strings.getString(R.string.error_code_prefix))
                append(error.code)
            }
        }
    }

    private fun officialMessageFor(code: Int): Int? = when (code) {
        10000 -> R.string.xiaomi_error_10000_official
        10001 -> R.string.xiaomi_error_10001_official
        10002 -> R.string.xiaomi_error_10002_official
        10003 -> R.string.xiaomi_error_10003_official
        10004 -> R.string.xiaomi_error_10004_official
        10005 -> R.string.xiaomi_error_10005_official
        10006 -> R.string.xiaomi_error_10006_official
        20030 -> R.string.xiaomi_error_20030_official
        20031 -> R.string.xiaomi_error_20031_official
        20032 -> R.string.xiaomi_error_20032_official
        20033 -> R.string.xiaomi_error_20033_official
        20034 -> R.string.xiaomi_error_20034_official
        20035 -> R.string.xiaomi_error_20035_official
        20036 -> R.string.xiaomi_error_20036_official
        20037 -> R.string.xiaomi_error_20037_official
        20040 -> R.string.xiaomi_error_20040_official
        else -> null
    }

    private fun guidanceFor(code: Int): Int? = when (code) {
        10000 -> R.string.xiaomi_error_10000_guidance
        10001 -> R.string.xiaomi_error_10001_guidance
        10002 -> R.string.xiaomi_error_10002_guidance
        10003 -> R.string.xiaomi_error_10003_guidance
        10004 -> R.string.xiaomi_error_10004_guidance
        10005 -> R.string.xiaomi_error_10005_guidance
        10006 -> R.string.xiaomi_error_10006_guidance
        10013 -> R.string.xiaomi_error_10013_guidance
        10023 -> R.string.xiaomi_error_10023_guidance
        20030 -> R.string.xiaomi_error_20030_guidance
        20031 -> R.string.xiaomi_error_20031_guidance
        20032 -> R.string.xiaomi_error_20032_guidance
        20033 -> R.string.xiaomi_error_20033_guidance
        20034 -> R.string.xiaomi_error_20034_guidance
        20035 -> R.string.xiaomi_error_20035_guidance
        20036 -> R.string.xiaomi_error_20036_guidance
        20037 -> R.string.xiaomi_error_20037_guidance
        20038 -> R.string.xiaomi_error_20038_guidance
        20039 -> R.string.xiaomi_error_20039_guidance
        20040 -> R.string.xiaomi_error_20040_guidance
        20041 -> R.string.xiaomi_error_20041_guidance
        20045 -> R.string.xiaomi_error_20045_guidance
        30002 -> R.string.xiaomi_error_30002_guidance
        else -> null
    }
}
