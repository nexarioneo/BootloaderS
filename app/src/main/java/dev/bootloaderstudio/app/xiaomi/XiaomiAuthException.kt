package dev.bootloaderstudio.app.xiaomi

import java.io.IOException

/**
 * The Xiaomi account exchange failed.
 *
 * [rejected] is `true` only when Xiaomi explicitly answered that the saved
 * credentials are no longer valid: a sign-in HTML page or HTTP 401/403 on an
 * account endpoint. JSON error codes, a missing STS token, a missing
 * `ssecurity`, and a rejected STS request are recoverable: the caller must
 * keep the saved session and let the user retry instead of forcing a new
 * sign-in.
 */
class XiaomiAuthException(
    message: String,
    cause: Throwable? = null,
    val rejected: Boolean = false
) : IOException(message, cause)
