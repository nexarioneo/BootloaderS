package dev.bootloaderstudio.app.xiaomi

data class XiaomiSession(
    val passToken: String,
    val deviceId: String,
    val userId: String,
    val region: String? = null,
    val host: String? = null,
    val ssecurity: String? = null,
    val serviceToken: String? = null,
    val resolvedAt: Long? = null,
    /**
     * Additional browser cookies captured after the WebView login (for
     * example cUserId, passport_*, and unlockApi_*). Xiaomi's unlockApi
     * serviceLogin exchange expects the account cookie set, not only the
     * passToken/deviceId/userId triplet.
     */
    val cookies: Map<String, String> = emptyMap()
)

data class XiaomiAccount(
    val userId: String,
    val region: String,
    val host: String,
    val ssecurity: String,
    val serviceToken: String,
    val cookies: Map<String, String> = emptyMap()
)
