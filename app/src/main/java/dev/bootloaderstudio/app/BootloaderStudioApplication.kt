package dev.bootloaderstudio.app

import android.app.Application
import android.content.Context
import android.webkit.WebView
import dev.bootloaderstudio.app.data.SessionStore
import dev.bootloaderstudio.app.fastboot.AndroidFastbootUsbService
import dev.bootloaderstudio.app.platform.AndroidStringProvider
import dev.bootloaderstudio.app.platform.CookieCleaner
import dev.bootloaderstudio.app.platform.StringProvider
import dev.bootloaderstudio.app.platform.WebViewCookieCleaner
import dev.bootloaderstudio.app.ubl.UblDependencies
import dev.bootloaderstudio.app.xiaomi.XiaomiAuthRepository
import dev.bootloaderstudio.app.xiaomi.XiaomiUnlockRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class BootloaderStudioApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
}

class AppContainer(context: Context) : UblDependencies {

    private val appContext = context.applicationContext

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override val sessionStore = SessionStore(appContext)
    override val authRepository = XiaomiAuthRepository(okHttpClient)
    override val unlockRepository = XiaomiUnlockRepository(okHttpClient)
    override val usbManager = AndroidFastbootUsbService(appContext)
    override val cookieCleaner: CookieCleaner = WebViewCookieCleaner()
    override val stringProvider: StringProvider = AndroidStringProvider(appContext)
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
}
