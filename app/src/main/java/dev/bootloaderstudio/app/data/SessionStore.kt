package dev.bootloaderstudio.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.webkit.CookieManager
import dev.bootloaderstudio.app.xiaomi.XiaomiSession

/**
 * Persistent Session Store.
 *
 * Persists Xiaomi authentication credentials across app restarts using an
 * Android Keystore AES/GCM protected SharedPreferences blob. A session stored
 * in plaintext by older builds is migrated on first read and the legacy keys
 * are removed. If no saved session exists, the store recovers one from the
 * WebView CookieManager.
 */
class SessionStore(
    context: Context,
    private val cipher: SessionCipher = AndroidKeystoreSessionCipher()
) : SessionRepository {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    override var session: XiaomiSession? = null
        private set

    init {
        session = loadFromStorage()
            ?.let { enrichWithWebViewCookies(it) }
            ?: recoverFromCookies()
    }

    private fun loadFromStorage(): XiaomiSession? {
        val stored = prefs.getString(KEY_ENCRYPTED_SESSION, null)
        if (!stored.isNullOrBlank()) {
            val decoded = runCatching {
                SessionCodec.decode(
                    cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP))
                )
            }.getOrNull()

            if (decoded != null) {
                prefs.edit().removeLegacyKeys().apply()
                return decoded
            }

            // The key may have been invalidated or the blob corrupted. Drop it
            // and fall through to the legacy migration / cookie recovery path.
            prefs.edit().remove(KEY_ENCRYPTED_SESSION).apply()
        }

        val legacy = SessionCodec.fromLegacy(
            userId = prefs.getString(KEY_USER_ID, null),
            passToken = prefs.getString(KEY_PASS_TOKEN, null),
            deviceId = prefs.getString(KEY_DEVICE_ID, null),
            region = prefs.getString(KEY_REGION, null),
            host = prefs.getString(KEY_HOST, null),
            ssecurity = prefs.getString(KEY_SSECURITY, null),
            serviceToken = prefs.getString(KEY_SERVICE_TOKEN, null),
            resolvedAt = prefs.getLong(KEY_RESOLVED_AT, 0L)
        ) ?: return null

        save(legacy)
        return legacy
    }

    private fun enrichWithWebViewCookies(session: XiaomiSession): XiaomiSession {
        if (session.cookies.isNotEmpty()) return session
        val cookieManager = CookieManager.getInstance()
        val cookieString = cookieManager.getCookie("https://account.xiaomi.com")
            ?: return session
        val cookies = parseCookieString(cookieString)
        if (cookies.isEmpty()) return session

        // Sessions written by older builds only contain the passToken
        // triplet. Pick up the remaining browser cookies (cUserId,
        // passport_*, unlockApi_*) without forcing another sign-in.
        val enriched = session.copy(cookies = session.cookies + cookies)
        save(enriched)
        return enriched
    }

    private fun recoverFromCookies(): XiaomiSession? {
        val cookieManager = CookieManager.getInstance()
        val cookieString = cookieManager.getCookie("https://account.xiaomi.com") ?: return null
        val cookies = parseCookieString(cookieString)

        val passToken = cookies["passToken"] ?: return null
        val deviceId = cookies["deviceId"] ?: return null
        val userId = cookies["userId"] ?: return null

        val recovered = XiaomiSession(
            passToken = passToken,
            deviceId = deviceId,
            userId = userId,
            cookies = cookies
        )
        save(recovered)
        return recovered
    }

    private fun parseCookieString(cookieString: String): Map<String, String> {
        return buildMap {
            cookieString.split(";").forEach { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty() || !trimmed.contains("=")) return@forEach
                val name = trimmed.substringBefore("=").trim()
                val value = trimmed.substringAfter("=", "").trim()
                if (name.isEmpty() || value.isEmpty()) return@forEach
                if (value.equals("null", ignoreCase = true) ||
                    value.equals("EXPIRED", ignoreCase = true)
                ) {
                    return@forEach
                }
                put(name, value)
            }
        }
    }

    override fun save(session: XiaomiSession) {
        this.session = session

        val encrypted = runCatching {
            Base64.encodeToString(
                cipher.encrypt(SessionCodec.encode(session)),
                Base64.NO_WRAP
            )
        }.getOrNull()

        val editor = prefs.edit().removeLegacyKeys()
        if (encrypted != null) {
            editor.putString(KEY_ENCRYPTED_SESSION, encrypted)
        } else {
            // Never leave a stale encrypted session behind when the current
            // one could not be written. The in-memory session still works.
            editor.remove(KEY_ENCRYPTED_SESSION)
        }
        editor.apply()
    }

    override fun update(transform: (XiaomiSession) -> XiaomiSession) {
        val current = session ?: return
        val updated = transform(current)
        save(updated)
    }

    override fun clear() {
        session = null
        prefs.edit().clear().apply()
    }

    private fun SharedPreferences.Editor.removeLegacyKeys(): SharedPreferences.Editor {
        return remove(KEY_USER_ID)
            .remove(KEY_PASS_TOKEN)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_REGION)
            .remove(KEY_HOST)
            .remove(KEY_SSECURITY)
            .remove(KEY_SERVICE_TOKEN)
            .remove(KEY_RESOLVED_AT)
    }

    companion object {
        private const val PREFS_NAME = "xiaomi_session_pref"
        private const val KEY_ENCRYPTED_SESSION = "encrypted_session"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_PASS_TOKEN = "pass_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_REGION = "region"
        private const val KEY_HOST = "host"
        private const val KEY_SSECURITY = "ssecurity"
        private const val KEY_SERVICE_TOKEN = "service_token"
        private const val KEY_RESOLVED_AT = "resolved_at"
    }
}
