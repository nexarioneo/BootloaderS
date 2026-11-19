package dev.bootloaderstudio.app.xiaomi

/**
 * Account/session resolution contract. Kept separate from the OkHttp
 * implementation so the ViewModel can be tested with fakes.
 */
interface XiaomiAuthService {
    suspend fun resolveAccount(session: XiaomiSession): XiaomiAccount
}

/**
 * Server-authoritative unlock API contract. The implementation must never
 * bypass Xiaomi's account, region, waiting-period, quota, or Community checks.
 */
interface XiaomiUnlockService {
    suspend fun requestNonce(account: XiaomiAccount): String

    suspend fun clearDevice(
        account: XiaomiAccount,
        product: String,
        nonce: String
    ): ClearResult

    suspend fun requestUnlock(
        account: XiaomiAccount,
        product: String,
        deviceToken: String,
        deviceId: String,
        nonce: String
    ): UnlockResult
}
