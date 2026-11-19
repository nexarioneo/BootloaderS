package dev.bootloaderstudio.app.xiaomi

import java.io.IOException

/**
 * The Xiaomi account session is no longer accepted (expired cookies, missing
 * service token, or an HTTP 401/403 from the account server). This is distinct
 * from a transient network failure so callers can refresh once and then ask
 * the user to sign in again instead of retrying forever.
 */
class XiaomiAuthException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
