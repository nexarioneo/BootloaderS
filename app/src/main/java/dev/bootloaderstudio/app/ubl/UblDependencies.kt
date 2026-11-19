package dev.bootloaderstudio.app.ubl

import dev.bootloaderstudio.app.data.SessionRepository
import dev.bootloaderstudio.app.fastboot.FastbootUsbService
import dev.bootloaderstudio.app.platform.CookieCleaner
import dev.bootloaderstudio.app.platform.StringProvider
import dev.bootloaderstudio.app.xiaomi.XiaomiAuthService
import dev.bootloaderstudio.app.xiaomi.XiaomiUnlockService
import kotlinx.coroutines.CoroutineDispatcher

interface UblDependencies {
    val sessionStore: SessionRepository
    val authRepository: XiaomiAuthService
    val unlockRepository: XiaomiUnlockService
    val usbManager: FastbootUsbService
    val cookieCleaner: CookieCleaner
    val stringProvider: StringProvider
    val ioDispatcher: CoroutineDispatcher
}
