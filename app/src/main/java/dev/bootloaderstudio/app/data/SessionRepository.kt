package dev.bootloaderstudio.app.data

import dev.bootloaderstudio.app.xiaomi.XiaomiSession

/**
 * Abstraction over the persisted Xiaomi session so the unlock flow can be
 * exercised in unit tests without Android SharedPreferences or WebView.
 */
interface SessionRepository {
    val session: XiaomiSession?

    fun save(session: XiaomiSession)

    fun update(transform: (XiaomiSession) -> XiaomiSession)

    fun clear()
}
