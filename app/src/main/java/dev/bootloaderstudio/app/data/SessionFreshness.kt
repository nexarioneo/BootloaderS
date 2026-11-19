package dev.bootloaderstudio.app.data

import dev.bootloaderstudio.app.xiaomi.XiaomiSession

/**
 * Decides whether the cached Xiaomi service token can still be used without
 * contacting the account server again. Xiaomi can invalidate tokens at any
 * time, so the unlock flow still handles authentication errors; this only
 * avoids reusing a token that is old enough to be almost certainly stale.
 */
object SessionFreshness {

    const val TTL_MS: Long = 6 * 60 * 60 * 1000L

    fun isFresh(session: XiaomiSession?, now: Long = System.currentTimeMillis()): Boolean {
        if (session == null) return false
        if (session.ssecurity.isNullOrBlank() || session.serviceToken.isNullOrBlank()) return false

        val resolvedAt = session.resolvedAt ?: return false
        val age = now - resolvedAt
        return age in 0 until TTL_MS
    }
}
