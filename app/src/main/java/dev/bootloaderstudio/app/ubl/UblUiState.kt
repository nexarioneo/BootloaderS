package dev.bootloaderstudio.app.ubl

import dev.bootloaderstudio.app.xiaomi.XiaomiAccount
import dev.bootloaderstudio.app.xiaomi.XiaomiSession

enum class DeviceState {
    Idle,
    Waiting,
    RequestingPermission,
    PermissionDenied,
    Connecting,
    Ready,
    Error
}

sealed interface UblEvent {
    data object SessionExpired : UblEvent
    data class IdentityRequired(val url: String) : UblEvent
}

enum class UnlockPhase {
    Idle,
    RequestingApproval,
    Staging,
    Unlocking,
    Success,
    Error
}

data class DeviceInfo(
    val product: String,
    val token: String,
    val unlocked: Boolean?,
    val tokenMasked: String,
    val handleId: Int,
    val tokenKey: String
)

data class UblUiState(
    val session: XiaomiSession? = null,
    val account: XiaomiAccount? = null,
    val accountLoading: Boolean = false,
    val accountError: String? = null,
    val identityUrl: String? = null,
    val identityCancelled: Boolean = false,
    val device: DeviceInfo? = null,
    val deviceState: DeviceState = DeviceState.Idle,
    val deviceMessage: String? = null,
    val preflightLoading: Boolean = false,
    val preflightNotice: String? = null,
    val preflightCleanOrNot: Int? = null,
    val preflightError: String? = null,
    val unlockPhase: UnlockPhase = UnlockPhase.Idle,
    val unlockMessage: String? = null,
    val unlockError: String? = null,
    val unlockErrorCode: Int? = null
)
