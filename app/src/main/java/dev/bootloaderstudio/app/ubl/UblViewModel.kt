package dev.bootloaderstudio.app.ubl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bootloaderstudio.app.R
import dev.bootloaderstudio.app.data.SessionFreshness
import dev.bootloaderstudio.app.fastboot.FastbootDeviceHandle
import dev.bootloaderstudio.app.fastboot.FastbootErrorKind
import dev.bootloaderstudio.app.fastboot.FastbootException
import dev.bootloaderstudio.app.fastboot.FastbootTransport
import dev.bootloaderstudio.app.fastboot.FastbootValue
import dev.bootloaderstudio.app.util.Hex
import dev.bootloaderstudio.app.xiaomi.ClearResult
import dev.bootloaderstudio.app.xiaomi.XiaomiAccount
import dev.bootloaderstudio.app.xiaomi.XiaomiApiException
import dev.bootloaderstudio.app.xiaomi.XiaomiAuthException
import dev.bootloaderstudio.app.xiaomi.XiaomiErrorMapper
import dev.bootloaderstudio.app.xiaomi.XiaomiIdentityRequiredException
import dev.bootloaderstudio.app.xiaomi.XiaomiSession
import dev.bootloaderstudio.app.xiaomi.UnlockResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class UblViewModel(
    private val container: UblDependencies,
    private val now: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        run {
            val initialSession = container.sessionStore.session
            val initialAccount = initialSession?.let { s ->
                if (!s.region.isNullOrBlank() && !s.host.isNullOrBlank() &&
                    !s.ssecurity.isNullOrBlank() && !s.serviceToken.isNullOrBlank()
                ) {
                    XiaomiAccount(
                        userId = s.userId,
                        region = s.region,
                        host = s.host,
                        ssecurity = s.ssecurity,
                        serviceToken = s.serviceToken
                    )
                } else null
            }
            UblUiState(
                session = initialSession,
                account = initialAccount
            )
        }
    )
    val uiState: StateFlow<UblUiState> = _uiState.asStateFlow()

    private val _events = Channel<UblEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var accountJob: Job? = null
    private var deviceJob: Job? = null
    private var preflightJob: Job? = null
    private var unlockJob: Job? = null
    private var cachedPreflight: CachedPreflight? = null

    fun onLoginSuccess(session: XiaomiSession) {
        accountJob?.cancel()
        preflightJob?.cancel()
        unlockJob?.cancel()
        cachedPreflight = null
        container.sessionStore.save(session)
        _uiState.update {
            it.copy(
                session = session,
                account = null,
                accountLoading = false,
                accountError = null,
                identityUrl = null,
                identityCancelled = false,
                device = null,
                deviceState = DeviceState.Idle,
                deviceMessage = null,
                preflightLoading = false,
                preflightNotice = null,
                preflightCleanOrNot = null,
                preflightError = null,
                unlockPhase = UnlockPhase.Idle,
                unlockMessage = null,
                unlockError = null,
                unlockErrorCode = null
            )
        }
    }

    fun loadAccount(force: Boolean = false) {
        if (!force && _uiState.value.identityCancelled) return
        val session = container.sessionStore.session ?: _uiState.value.session ?: run {
            _uiState.update {
                it.copy(accountError = container.stringProvider.getString(R.string.account_no_session))
            }
            return
        }
        if (!force && _uiState.value.accountError == null &&
            _uiState.value.account != null && SessionFreshness.isFresh(session)
        ) {
            return
        }
        if (accountJob?.isActive == true) return

        accountJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    accountLoading = true,
                    accountError = null,
                    identityCancelled = false
                )
            }
            try {
                val account = resolveAccountWithRecovery(session)
                val resolvedSession = persistResolvedAccount(account)
                _uiState.update {
                    it.copy(
                        session = resolvedSession,
                        account = account,
                        accountLoading = false,
                        accountError = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: XiaomiIdentityRequiredException) {
                requestIdentityVerification(error)
            } catch (error: SessionExpiredException) {
                clearSessionAfterExpiry()
                _uiState.update {
                    it.copy(
                        accountLoading = false,
                        accountError = sessionExpiredMessage(error)
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        accountLoading = false,
                        accountError = friendlyError(error)
                    )
                }
            }
        }
    }

    fun startDeviceDetection(force: Boolean = false) {
        if (!force && _uiState.value.device != null) return
        if (!container.usbManager.hasUsbHost()) {
            _uiState.update {
                it.copy(
                    deviceState = DeviceState.Error,
                    deviceMessage = container.stringProvider.getString(
                        R.string.device_error_no_usb_host
                    )
                )
            }
            return
        }

        if (force) {
            deviceJob?.cancel()
            deviceJob = null
            cachedPreflight = null
            _uiState.update {
                it.copy(
                    device = null,
                    deviceState = DeviceState.Waiting,
                    deviceMessage = container.stringProvider.getString(
                        R.string.device_waiting_hint
                    ),
                    preflightLoading = false,
                    preflightNotice = null,
                    preflightCleanOrNot = null,
                    preflightError = null
                )
            }
        } else if (deviceJob?.isActive == true) {
            val state = _uiState.value.deviceState
            if (state != DeviceState.PermissionDenied && state != DeviceState.Error) return
            deviceJob?.cancel()
            deviceJob = null
        }

        deviceJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    device = null,
                    deviceState = DeviceState.Waiting,
                    deviceMessage = container.stringProvider.getString(
                        R.string.device_waiting_hint
                    )
                )
            }

            // After a denial, do not prompt again for the same attached device.
            // A detach/attach or an explicit Retry clears this guard.
            var deniedDeviceId: Int? = null

            while (isActive) {
                val handle = container.usbManager.findFastbootDevice()
                if (handle == null) {
                    deniedDeviceId = null
                    _uiState.update {
                        it.copy(
                            deviceState = DeviceState.Waiting,
                            deviceMessage = container.stringProvider.getString(
                                R.string.device_waiting_hint
                            )
                        )
                    }
                    delay(1_200)
                    continue
                }

                if (deniedDeviceId == handle.id) {
                    _uiState.update {
                        it.copy(
                            deviceState = DeviceState.PermissionDenied,
                            deviceMessage = null
                        )
                    }
                    delay(500)
                    continue
                }

                if (!container.usbManager.hasPermission(handle)) {
                    _uiState.update {
                        it.copy(
                            deviceState = DeviceState.RequestingPermission,
                            deviceMessage = container.stringProvider.getString(
                                R.string.device_permission_hint
                            )
                        )
                    }
                    val granted = container.usbManager.requestPermission(handle)
                    if (!granted) {
                        deniedDeviceId = handle.id
                        _uiState.update {
                            it.copy(
                                deviceState = DeviceState.PermissionDenied,
                                deviceMessage = null
                            )
                        }
                        delay(500)
                        continue
                    }
                }

                deniedDeviceId = null
                _uiState.update {
                    it.copy(
                        deviceState = DeviceState.Connecting,
                        deviceMessage = container.stringProvider.getString(
                            R.string.device_reading_hint
                        )
                    )
                }

                try {
                    val info = withContext(container.ioDispatcher) {
                        readDeviceInfo(handle)
                    }
                    _uiState.update {
                        it.copy(
                            device = info,
                            deviceState = DeviceState.Ready,
                            deviceMessage = null
                        )
                    }
                    return@launch
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _uiState.update {
                        it.copy(
                            deviceState = DeviceState.Error,
                            deviceMessage = friendlyError(error)
                        )
                    }
                    delay(2_000)
                }
            }
        }
    }

    fun stopDeviceDetection() {
        deviceJob?.cancel()
        deviceJob = null
        if (_uiState.value.deviceState != DeviceState.Ready) {
            _uiState.update {
                it.copy(deviceState = DeviceState.Idle, deviceMessage = null)
            }
        }
    }

    fun onDeviceAttached() {
        if (deviceJob?.isActive == true) return
        startDeviceDetection(force = _uiState.value.device != null)
    }

    fun onDeviceDetached() {
        deviceJob?.cancel()
        deviceJob = null
        cachedPreflight = null
        _uiState.update {
            it.copy(
                device = null,
                deviceState = DeviceState.Waiting,
                deviceMessage = container.stringProvider.getString(
                    R.string.device_waiting_hint
                ),
                preflightLoading = false,
                preflightNotice = null,
                preflightCleanOrNot = null,
                preflightError = null
            )
        }
        startDeviceDetection(force = true)
    }

    fun prepareUnlock() {
        if (preflightJob?.isActive == true) return
        val device = _uiState.value.device
        val session = container.sessionStore.session
        if (device == null || session == null) {
            _uiState.update {
                it.copy(
                    preflightError = container.stringProvider.getString(
                        R.string.error_account_or_device_missing
                    )
                )
            }
            return
        }
        if (device.unlocked == true) {
            _uiState.update {
                it.copy(
                    preflightError = container.stringProvider.getString(
                        R.string.device_already_unlocked_desc
                    )
                )
            }
            return
        }
        if (session.deviceId.isBlank()) {
            _uiState.update {
                it.copy(
                    preflightError = container.stringProvider.getString(
                        R.string.error_device_id_missing
                    )
                )
            }
            return
        }
        if (device.product.isBlank() || device.tokenKey.isBlank()) {
            val message = container.stringProvider.getString(R.string.device_error_changed)
            clearStaleDevice(message)
            _uiState.update { it.copy(preflightError = message) }
            return
        }
        if (!isDeviceStillAttached(device)) {
            val message = container.stringProvider.getString(R.string.device_error_changed)
            clearStaleDevice(message)
            _uiState.update { it.copy(preflightError = message) }
            return
        }

        // Reuse a recent successful eligibility check for the same device.
        // Errors and device changes always fall through to a fresh request.
        val cached = reusablePreflight(device)
        if (cached != null &&
            _uiState.value.account != null &&
            SessionFreshness.isFresh(container.sessionStore.session, now())
        ) {
            _uiState.update {
                it.copy(
                    preflightLoading = false,
                    preflightNotice = cached.notice,
                    preflightCleanOrNot = cached.cleanOrNot,
                    preflightError = null
                )
            }
            return
        }

        preflightJob = viewModelScope.launch {
            cachedPreflight = null
            _uiState.update {
                it.copy(
                    preflightLoading = true,
                    preflightNotice = null,
                    preflightCleanOrNot = null,
                    preflightError = null
                )
            }
            try {
                var account = ensureFreshAccount()
                var authRetried = false
                var nonceRetried = false

                while (true) {
                    try {
                        val preflight = requestPreflight(account, device)
                        cachedPreflight = CachedPreflight(
                            product = device.product,
                            token = device.token,
                            notice = preflight.notice,
                            cleanOrNot = preflight.cleanOrNot,
                            createdAt = now()
                        )
                        _uiState.update {
                            it.copy(
                                preflightLoading = false,
                                preflightNotice = preflight.notice,
                                preflightCleanOrNot = preflight.cleanOrNot,
                                preflightError = null
                            )
                        }
                        return@launch
                    } catch (error: XiaomiApiException) {
                        when {
                            isAuthenticationError(error) && !authRetried -> {
                                authRetried = true
                                account = refreshAccountAfterAuthError()
                            }
                            isNonceExpiredError(error) && !nonceRetried -> {
                                // Xiaomi rejected the nonce. One retry is allowed
                                // and requestPreflight always asks for a fresh one.
                                nonceRetried = true
                            }
                            else -> throw error
                        }
                    } catch (error: XiaomiAuthException) {
                        // The unlock endpoints answer 401/403 when the service
                        // token has been invalidated. Resolve the account once
                        // more before giving up on the session.
                        if (authRetried) throw error
                        authRetried = true
                        account = refreshAccountAfterAuthError()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: XiaomiIdentityRequiredException) {
                cachedPreflight = null
                requestIdentityVerification(error)
            } catch (error: SessionExpiredException) {
                clearSessionAfterExpiry()
                cachedPreflight = null
                _uiState.update {
                    it.copy(
                        preflightLoading = false,
                        preflightError = sessionExpiredMessage(error)
                    )
                }
            } catch (error: Exception) {
                cachedPreflight = null
                _uiState.update {
                    it.copy(
                        preflightLoading = false,
                        preflightError = friendlyError(error),
                        unlockErrorCode = (error as? XiaomiApiException)?.code
                    )
                }
            }
        }
    }

    fun confirmUnlock() {
        if (unlockJob?.isActive == true) return
        val account = _uiState.value.account
        val device = _uiState.value.device
        val session = container.sessionStore.session
        if (account == null || device == null || session == null) {
            _uiState.update {
                it.copy(
                    unlockPhase = UnlockPhase.Error,
                    unlockError = container.stringProvider.getString(
                        R.string.error_account_or_device_missing
                    )
                )
            }
            return
        }
        if (cachedPreflight == null) {
            _uiState.update {
                it.copy(
                    unlockPhase = UnlockPhase.Error,
                    unlockError = container.stringProvider.getString(
                        R.string.error_unlock_not_prepared
                    )
                )
            }
            return
        }

        val deviceId = session.deviceId.trim()
        if (deviceId.isBlank()) {
            _uiState.update {
                it.copy(
                    unlockPhase = UnlockPhase.Error,
                    unlockError = container.stringProvider.getString(
                        R.string.error_device_id_missing
                    )
                )
            }
            return
        }
        if (device.product.isBlank() || device.tokenKey.isBlank()) {
            val message = container.stringProvider.getString(R.string.device_error_changed)
            clearStaleDevice(message)
            _uiState.update {
                it.copy(
                    unlockPhase = UnlockPhase.Error,
                    unlockError = message
                )
            }
            return
        }
        if (!isDeviceStillAttached(device)) {
            val message = container.stringProvider.getString(R.string.device_error_changed)
            clearStaleDevice(message)
            _uiState.update {
                it.copy(
                    unlockPhase = UnlockPhase.Error,
                    unlockError = message
                )
            }
            return
        }

        val confirmedAccount: XiaomiAccount = account
        val confirmedDeviceId: String = deviceId
        // The eligibility result stays valid, but its nonce was consumed by
        // clearDevice. Xiaomi's nonce is single-use, so confirmation must ask
        // for a new one right before ahaUnlock.
        cachedPreflight = null

        unlockJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    unlockPhase = UnlockPhase.RequestingApproval,
                    unlockMessage = container.stringProvider.getString(
                        R.string.unlock_message_requesting
                    ),
                    unlockError = null,
                    unlockErrorCode = null
                )
            }

            try {
                var activeAccount = confirmedAccount
                var authRetried = false
                var nonceRetried = false
                var unlockResult: UnlockResult? = null

                while (true) {
                    try {
                        // /api/v2/nonce is single-use. clearDevice already
                        // consumed the preflight nonce, so every ahaUnlock
                        // attempt needs its own fresh nonce.
                        val activeNonce =
                            container.unlockRepository.requestNonce(activeAccount)
                        unlockResult = container.unlockRepository.requestUnlock(
                            account = activeAccount,
                            product = device.product,
                            deviceToken = device.token,
                            deviceId = confirmedDeviceId,
                            nonce = activeNonce
                        )
                        break
                    } catch (error: XiaomiApiException) {
                        when {
                            isAuthenticationError(error) && !authRetried -> {
                                authRetried = true
                                activeAccount = refreshAccountAfterAuthError()
                            }
                            isNonceExpiredError(error) && !nonceRetried -> {
                                // The nonce was rejected or already consumed.
                                // The next iteration requests a fresh one; do
                                // not run clearDevice again because that would
                                // consume the new nonce before ahaUnlock.
                                nonceRetried = true
                            }
                            else -> throw error
                        }
                    } catch (error: XiaomiAuthException) {
                        if (authRetried) throw error
                        authRetried = true
                        activeAccount = refreshAccountAfterAuthError()
                    }
                }

                val result = unlockResult
                    ?: throw FastbootException(
                        container.stringProvider.getString(R.string.unlock_error_no_data)
                    )

                _uiState.update {
                    it.copy(
                        unlockPhase = UnlockPhase.Staging,
                        unlockMessage = container.stringProvider.getString(
                            R.string.unlock_message_staging
                        )
                    )
                }

                val verification = withContext(container.ioDispatcher) {
                    val handle = container.usbManager.findFastbootDevice()
                        ?: throw FastbootException(
                            container.stringProvider.getString(
                                R.string.device_error_disconnected
                            ),
                            FastbootErrorKind.Disconnected
                        )
                    if (handle.id != device.handleId) {
                        throw FastbootException(
                            container.stringProvider.getString(R.string.device_error_changed)
                        )
                    }
                    if (!container.usbManager.hasPermission(handle)) {
                        val granted = container.usbManager.requestPermission(handle)
                        if (!granted) {
                            throw FastbootException(
                                container.stringProvider.getString(
                                    R.string.device_error_permission_denied
                                )
                            )
                        }
                    }

                    val connection = container.usbManager.openDevice(handle)
                        ?: throw FastbootException(
                            container.stringProvider.getString(R.string.device_error_open)
                        )

                    try {
                        val currentProduct = FastbootValue.product(
                            connection.getVar("product")
                        )
                        if (currentProduct != null &&
                            !FastbootValue.isSameProduct(device.product, currentProduct)
                        ) {
                            throw FastbootException(
                                container.stringProvider.getString(
                                    R.string.device_error_changed
                                )
                            )
                        }

                        val currentToken = FastbootValue.token(
                            connection.getVar("token")
                                ?: connection.oemGetToken()
                        )

                        if (currentToken == null) {
                            throw FastbootException(
                                container.stringProvider.getString(
                                    R.string.device_error_token_reread
                                )
                            )
                        }
                        if (!FastbootValue.isSameToken(device.tokenKey, currentToken)) {
                            throw FastbootException(
                                container.stringProvider.getString(
                                    R.string.device_error_token_changed
                                )
                            )
                        }

                        val bytes = Hex.decode(result.encryptData)
                        val staged = connection.download(bytes)
                        if (!staged.ok) {
                            throw FastbootException(
                                staged.text.ifBlank {
                                    container.stringProvider.getString(
                                        R.string.unlock_error_data_rejected
                                    )
                                }
                            )
                        }

                        _uiState.update {
                            it.copy(
                                unlockPhase = UnlockPhase.Unlocking,
                                unlockMessage = container.stringProvider.getString(
                                    R.string.unlock_message_unlocking
                                )
                            )
                        }

                        val unlocked = connection.oemUnlock()
                        if (!unlocked.ok) {
                            throw FastbootException(
                                unlocked.text.ifBlank {
                                    container.stringProvider.getString(
                                        R.string.unlock_error_command_rejected
                                    )
                                }
                            )
                        }

                        verifyUnlockState(connection)
                    } finally {
                        connection.close()
                    }
                }

                when (verification) {
                    UnlockVerification.Verified -> _uiState.update {
                        it.copy(
                            unlockPhase = UnlockPhase.Success,
                            unlockMessage = container.stringProvider.getString(
                                R.string.unlock_success_verified
                            )
                        )
                    }

                    UnlockVerification.Unknown -> _uiState.update {
                        it.copy(
                            unlockPhase = UnlockPhase.Success,
                            unlockMessage = container.stringProvider.getString(
                                R.string.unlock_success_unverified
                            )
                        )
                    }

                    UnlockVerification.StillLocked -> _uiState.update {
                        it.copy(
                            unlockPhase = UnlockPhase.Error,
                            unlockError = container.stringProvider.getString(
                                R.string.unlock_result_still_locked
                            ),
                            unlockErrorCode = null
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: XiaomiIdentityRequiredException) {
                requestIdentityVerification(error)
            } catch (error: SessionExpiredException) {
                clearSessionAfterExpiry()
                _uiState.update {
                    it.copy(
                        unlockPhase = UnlockPhase.Error,
                        unlockError = sessionExpiredMessage(error)
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        unlockPhase = UnlockPhase.Error,
                        unlockError = friendlyError(error),
                        unlockErrorCode = (error as? XiaomiApiException)?.code
                    )
                }
            }
        }
    }

    fun reset() {
        accountJob?.cancel()
        deviceJob?.cancel()
        preflightJob?.cancel()
        unlockJob?.cancel()
        cachedPreflight = null
        _uiState.value = UblUiState(session = container.sessionStore.session)
    }

    fun logout() {
        accountJob?.cancel()
        deviceJob?.cancel()
        preflightJob?.cancel()
        unlockJob?.cancel()
        cachedPreflight = null
        container.sessionStore.clear()
        container.cookieCleaner.clearCookies()
        _uiState.value = UblUiState()
    }

    /**
     * Called after the identity WebView reaches Xiaomi's STS success page.
     * The passToken may have been rotated during verification, so refresh it
     * from the cookies the WebView just observed before resolving the account
     * again. The session itself is still valid; only the derived unlock
     * credentials are discarded.
     */
    fun onIdentityVerified(session: XiaomiSession? = null) {
        accountJob?.cancel()
        accountJob = null
        preflightJob?.cancel()
        preflightJob = null
        unlockJob?.cancel()
        unlockJob = null
        cachedPreflight = null

        if (session != null) {
            container.sessionStore.update { stored ->
                stored.copy(
                    passToken = session.passToken.ifBlank { stored.passToken },
                    deviceId = session.deviceId.ifBlank { stored.deviceId },
                    userId = session.userId.ifBlank { stored.userId },
                    ssecurity = null,
                    serviceToken = null,
                    resolvedAt = null,
                    cookies = stored.cookies + session.cookies
                )
            }
        } else {
            container.sessionStore.update {
                it.copy(ssecurity = null, serviceToken = null, resolvedAt = null)
            }
        }

        _uiState.update {
            it.copy(
                identityUrl = null,
                identityCancelled = false,
                account = null,
                accountLoading = false,
                accountError = null,
                preflightLoading = false,
                preflightNotice = null,
                preflightCleanOrNot = null,
                preflightError = null,
                unlockPhase = UnlockPhase.Idle,
                unlockMessage = null,
                unlockError = null,
                unlockErrorCode = null
            )
        }
        loadAccount(force = true)
    }

    /**
     * The user backed out of the identity WebView. Keep the session (the
     * passToken is still valid) and offer Retry on the account screen.
     */
    fun cancelIdentityVerification() {
        if (_uiState.value.identityUrl == null) return
        _uiState.update {
            it.copy(
                identityUrl = null,
                identityCancelled = true,
                accountError = container.stringProvider.getString(
                    R.string.account_identity_cancelled
                )
            )
        }
    }

    private suspend fun requestPreflight(
        account: XiaomiAccount,
        device: DeviceInfo
    ): ClearResult {
        // This nonce belongs only to clearDevice. It must not be reused for
        // ahaUnlock because Xiaomi made /api/v2/nonce single-use.
        val freshNonce = container.unlockRepository.requestNonce(account)
        return container.unlockRepository.clearDevice(
            account = account,
            product = device.product,
            nonce = freshNonce
        )
    }

    private fun reusablePreflight(device: DeviceInfo): CachedPreflight? {
        val cached = cachedPreflight ?: return null
        if (cached.product != device.product || cached.token != device.token) return null

        val age = now() - cached.createdAt
        if (age < 0L || age > PREFLIGHT_CACHE_TTL_MS) return null
        return cached
    }

    /**
     * Re-reads the lock state after `oem unlock` returns OKAY.
     *
     * A device that reboots or disconnects immediately is reported as Unknown
     * and still treated as a successful unlock request. Only a definitive
     * "locked" answer from a connected device is a failure.
     */
    private suspend fun verifyUnlockState(connection: FastbootTransport): UnlockVerification {
        repeat(UNLOCK_VERIFY_ATTEMPTS) { attempt ->
            val value = try {
                connection.getVar("unlocked", UNLOCK_VERIFY_TIMEOUT_MS)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return UnlockVerification.Unknown
            }?.trim()?.lowercase()

            when (value) {
                "yes", "true", "1" -> return UnlockVerification.Verified
                "no", "false", "0" -> {
                    if (attempt < UNLOCK_VERIFY_ATTEMPTS - 1) {
                        delay(UNLOCK_VERIFY_RETRY_DELAY_MS)
                    }
                }
                else -> return UnlockVerification.Unknown
            }
        }
        return UnlockVerification.StillLocked
    }

    private suspend fun ensureFreshAccount(): XiaomiAccount {
        val session = container.sessionStore.session
            ?: throw SessionExpiredException(sessionExpiredMessage())

        val cached = _uiState.value.account
        if (cached != null && SessionFreshness.isFresh(session)) return cached

        val account = resolveAccountWithRecovery(session)
        val resolvedSession = persistResolvedAccount(account)
        _uiState.update {
            it.copy(session = resolvedSession, account = account, accountError = null)
        }
        return account
    }

    private suspend fun resolveAccountWithRecovery(session: XiaomiSession): XiaomiAccount {
        return try {
            container.authRepository.resolveAccount(session)
        } catch (error: XiaomiAuthException) {
            container.sessionStore.update {
                it.copy(ssecurity = null, serviceToken = null, resolvedAt = null)
            }
            val retrySession = container.sessionStore.session
                ?: throw SessionExpiredException(sessionExpiredMessage(error), error)
            try {
                container.authRepository.resolveAccount(retrySession)
            } catch (retryError: XiaomiAuthException) {
                // Keep the stage-specific reason (region check, login
                // exchange, or STS) instead of collapsing every failure into
                // the generic "session expired" text. Only an explicit
                // rejection clears the saved login session; protocol and
                // network failures must stay retryable without a new sign-in.
                if (retryError.rejected) {
                    throw SessionExpiredException(sessionExpiredMessage(retryError), retryError)
                }
                throw retryError
            }
        }
    }

    private suspend fun refreshAccountAfterAuthError(): XiaomiAccount {
        container.sessionStore.update {
            it.copy(ssecurity = null, serviceToken = null, resolvedAt = null)
        }
        val session = container.sessionStore.session
            ?: throw SessionExpiredException(sessionExpiredMessage())

        val account = try {
            container.authRepository.resolveAccount(session)
        } catch (error: XiaomiAuthException) {
            if (error.rejected) {
                throw SessionExpiredException(sessionExpiredMessage(error), error)
            }
            throw error
        }

        val resolvedSession = persistResolvedAccount(account)
        _uiState.update {
            it.copy(session = resolvedSession, account = account, accountError = null)
        }
        return account
    }

    private fun persistResolvedAccount(account: XiaomiAccount): XiaomiSession? {
        val resolvedAt = System.currentTimeMillis()
        container.sessionStore.update { current ->
            val mergedCookies = current.cookies + account.cookies
            current.copy(
                passToken = mergedCookies["passToken"]
                    ?.takeIf { it.isNotBlank() }
                    ?: current.passToken,
                deviceId = mergedCookies["deviceId"]
                    ?.takeIf { it.isNotBlank() }
                    ?: current.deviceId,
                region = account.region,
                host = account.host,
                ssecurity = account.ssecurity,
                serviceToken = account.serviceToken,
                resolvedAt = resolvedAt,
                cookies = mergedCookies
            )
        }
        return container.sessionStore.session
    }

    private fun clearSessionAfterExpiry() {
        container.sessionStore.clear()
        // Xiaomi rejected the stored credentials. Drop the WebView cookies as
        // well so the next login does not silently reuse the same dead
        // passToken/deviceId pair.
        container.cookieCleaner.clearCookies()
        _uiState.update {
            it.copy(
                session = null,
                account = null,
                identityUrl = null,
                identityCancelled = false
            )
        }
        _events.trySend(UblEvent.SessionExpired)
    }

    private fun requestIdentityVerification(error: XiaomiIdentityRequiredException) {
        _uiState.update {
            it.copy(
                identityUrl = error.notificationUrl,
                identityCancelled = false,
                accountLoading = false,
                accountError = null,
                preflightLoading = false,
                preflightNotice = null,
                preflightCleanOrNot = null,
                preflightError = null,
                unlockPhase = UnlockPhase.Idle,
                unlockMessage = null,
                unlockError = null,
                unlockErrorCode = null
            )
        }
        _events.trySend(UblEvent.IdentityRequired(error.notificationUrl))
    }

    private fun sessionExpiredMessage(error: Throwable? = null): String {
        return error?.message?.takeIf { it.isNotBlank() }
            ?: container.stringProvider.getString(R.string.account_session_expired)
    }

    private fun isAuthenticationError(error: XiaomiApiException): Boolean {
        return error.code == AUTH_ERROR_CODE
    }

    private fun isNonceExpiredError(error: XiaomiApiException): Boolean {
        return error.code == NONCE_EXPIRED_ERROR_CODE ||
            error.code == INVALID_NONCE_ERROR_CODE
    }

    private fun isDeviceStillAttached(device: DeviceInfo): Boolean {
        return try {
            container.usbManager.findFastbootDevice()?.id == device.handleId
        } catch (_: Exception) {
            false
        }
    }

    private fun clearStaleDevice(message: String) {
        cachedPreflight = null
        _uiState.update {
            it.copy(
                device = null,
                deviceState = DeviceState.Waiting,
                deviceMessage = message,
                preflightLoading = false,
                preflightNotice = null,
                preflightCleanOrNot = null
            )
        }
    }

    private fun readDeviceInfo(device: FastbootDeviceHandle): DeviceInfo {
        val connection = container.usbManager.openDevice(device)
            ?: throw FastbootException(
                container.stringProvider.getString(R.string.device_error_open)
            )
        try {
            val product = FastbootValue.product(connection.getVar("product"))
                ?: throw FastbootException(
                    container.stringProvider.getString(R.string.device_error_product_missing)
                )

            val unlockedValue = connection.getVar("unlocked")?.trim()?.lowercase()
            val unlocked = when (unlockedValue) {
                "yes", "true", "1" -> true
                "no", "false", "0" -> false
                else -> null
            }

            val token = FastbootValue.clean(
                connection.getVar("token")
                    ?: connection.oemGetToken()
            ) ?: throw FastbootException(
                container.stringProvider.getString(R.string.device_error_token_missing)
            )
            val tokenKey = FastbootValue.token(token)
                ?: throw FastbootException(
                    container.stringProvider.getString(R.string.device_error_token_missing)
                )

            return DeviceInfo(
                product = product,
                token = token,
                unlocked = unlocked,
                tokenMasked = maskToken(token),
                handleId = device.id,
                tokenKey = tokenKey
            )
        } finally {
            connection.close()
        }
    }

    private fun maskToken(token: String): String {
        return if (token.length <= 12) "••••••" else "${token.take(6)}…${token.takeLast(6)}"
    }

    private fun friendlyError(error: Exception): String = when (error) {
        is SessionExpiredException -> sessionExpiredMessage(error)
        is XiaomiAuthException -> buildString {
            append(container.stringProvider.getString(R.string.error_network))
            error.message?.takeIf { it.isNotBlank() }?.let { detail ->
                append(" (")
                append(detail)
                append(')')
            }
        }
        is XiaomiApiException -> XiaomiErrorMapper.format(error, container.stringProvider)
        is FastbootException -> when (error.kind) {
            FastbootErrorKind.Disconnected -> container.stringProvider.getString(
                R.string.device_error_disconnected
            )
            FastbootErrorKind.Timeout -> container.stringProvider.getString(
                R.string.device_error_timeout
            )
            FastbootErrorKind.Other -> error.message
                ?: container.stringProvider.getString(R.string.device_error_default)
        }
        is IOException -> buildString {
            append(container.stringProvider.getString(R.string.error_network))
            error.message?.takeIf { it.isNotBlank() }?.let { detail ->
                append(" (")
                append(detail)
                append(')')
            }
        }
        else -> error.message
            ?: container.stringProvider.getString(R.string.error_unknown)
    }

    private class SessionExpiredException(
        message: String,
        cause: Throwable? = null
    ) : Exception(message, cause)

    private data class CachedPreflight(
        val product: String,
        val token: String,
        val notice: String?,
        val cleanOrNot: Int?,
        val createdAt: Long
    )

    private enum class UnlockVerification {
        Verified,
        StillLocked,
        Unknown
    }

    companion object {
        // Signature verification failed: the cached account token is no longer
        // usable. Never retry rate limits (10002) or policy rejections (200xx).
        private const val AUTH_ERROR_CODE = 10001

        // Xiaomi rejected or expired the unlock nonce. A single retry with a
        // fresh nonce is allowed; clearDevice is not repeated because its
        // nonce is consumed separately.
        private const val NONCE_EXPIRED_ERROR_CODE = 10004
        private const val INVALID_NONCE_ERROR_CODE = 10005

        // A successful eligibility check may be reused briefly for the same
        // device. Errors and device changes invalidate it immediately.
        private const val PREFLIGHT_CACHE_TTL_MS = 3 * 60 * 1000L

        // After oem unlock, re-read the lock state. A reboot/disconnect makes
        // the result unknown but still accepted; "locked" is a real failure.
        private const val UNLOCK_VERIFY_TIMEOUT_MS = 5_000
        private const val UNLOCK_VERIFY_ATTEMPTS = 3
        private const val UNLOCK_VERIFY_RETRY_DELAY_MS = 500L
    }

    class Factory(
        private val dependencies: UblDependencies
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(UblViewModel::class.java))
            return UblViewModel(dependencies) as T
        }
    }
}
