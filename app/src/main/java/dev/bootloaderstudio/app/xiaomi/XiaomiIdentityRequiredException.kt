package dev.bootloaderstudio.app.xiaomi

/**
 * Xiaomi accepted the saved `passToken` but requires the user to complete an
 * identity check (SMS / e-mail) before it will issue an unlock `ssecurity`.
 *
 * This is not an expired session: the account cookies are still valid. The
 * caller must open [notificationUrl] in a WebView that shares the same cookie
 * jar, let the user finish the check, and then resolve the account again.
 */
class XiaomiIdentityRequiredException(
    val notificationUrl: String,
    val securityStatus: Int = 0
) : Exception(
    if (securityStatus != 0) {
        "Xiaomi requires identity verification for the unlock service (securityStatus $securityStatus)."
    } else {
        "Xiaomi requires identity verification for the unlock service."
    }
)
