package dev.bootloaderstudio.app.platform

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.annotation.StringRes

interface CookieCleaner {
    fun clearCookies()
}

interface StringProvider {
    fun getString(@StringRes id: Int): String
}

/**
 * Clears both login cookies and WebView storage. The flush is performed from
 * the removal callback so it cannot race the asynchronous cookie deletion.
 */
class WebViewCookieCleaner : CookieCleaner {
    override fun clearCookies() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies { cookieManager.flush() }
        WebStorage.getInstance().deleteAllData()
    }
}

class AndroidStringProvider(
    private val context: Context
) : StringProvider {
    override fun getString(id: Int): String = context.getString(id)
}
