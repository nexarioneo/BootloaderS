package dev.bootloaderstudio.app.ubl

import dev.bootloaderstudio.app.R
import dev.bootloaderstudio.app.data.SessionRepository
import dev.bootloaderstudio.app.fastboot.FastbootDeviceHandle
import dev.bootloaderstudio.app.fastboot.FastbootErrorKind
import dev.bootloaderstudio.app.fastboot.FastbootException
import dev.bootloaderstudio.app.fastboot.FastbootResponse
import dev.bootloaderstudio.app.fastboot.FastbootTransport
import dev.bootloaderstudio.app.fastboot.FastbootUsbService
import dev.bootloaderstudio.app.platform.CookieCleaner
import dev.bootloaderstudio.app.platform.StringProvider
import dev.bootloaderstudio.app.xiaomi.ClearResult
import dev.bootloaderstudio.app.xiaomi.UnlockResult
import dev.bootloaderstudio.app.xiaomi.XiaomiAccount
import dev.bootloaderstudio.app.xiaomi.XiaomiApiException
import dev.bootloaderstudio.app.xiaomi.XiaomiAuthException
import dev.bootloaderstudio.app.xiaomi.XiaomiAuthService
import dev.bootloaderstudio.app.xiaomi.XiaomiIdentityRequiredException
import dev.bootloaderstudio.app.xiaomi.XiaomiSession
import dev.bootloaderstudio.app.xiaomi.XiaomiUnlockService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UblViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun permissionDenialIsNotPromptedAgainForTheSameDevice() = runTest(dispatcher) {
        val usb = FakeFastbootUsbService(permission = false, permissionResult = false)
        val viewModel = viewModel(usb = usb)

        viewModel.startDeviceDetection()
        runCurrent()

        assertEquals(DeviceState.PermissionDenied, viewModel.uiState.value.deviceState)
        assertEquals(1, usb.permissionRequests)

        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(DeviceState.PermissionDenied, viewModel.uiState.value.deviceState)
        assertEquals(1, usb.permissionRequests)

        viewModel.stopDeviceDetection()
        runCurrent()
    }

    @Test
    fun staleSessionIsResolvedAndPersisted() = runTest(dispatcher) {
        val stale = XiaomiSession(
            passToken = "pass-token",
            deviceId = "device-id",
            userId = "user-id"
        )
        val store = FakeSessionRepository(stale)
        val auth = FakeAuthService()
        val viewModel = viewModel(store = store, auth = auth)

        viewModel.loadAccount()
        advanceUntilIdle()

        assertEquals(1, auth.calls)
        assertEquals(ACCOUNT, viewModel.uiState.value.account)
        assertEquals(ACCOUNT.ssecurity, store.session?.ssecurity)
        assertEquals(ACCOUNT.serviceToken, store.session?.serviceToken)
        assertNotNull(store.session?.resolvedAt)
    }

    @Test
    fun resolvedAccountCookiesArePersistedWithTheSession() = runTest(dispatcher) {
        val store = FakeSessionRepository(freshSession())
        val auth = FakeAuthService(
            account = ACCOUNT.copy(
                cookies = mapOf(
                    "cUserId" to "c-user",
                    "passToken" to "rotated-pass-token"
                )
            )
        )
        val viewModel = viewModel(store = store, auth = auth)

        viewModel.loadAccount(force = true)
        advanceUntilIdle()

        assertEquals("rotated-pass-token", store.session?.passToken)
        assertEquals("c-user", store.session?.cookies?.get("cUserId"))
    }

    @Test
    fun authFailureClearsSessionAndEmitsExpiryEvent() = runTest(dispatcher) {
        val store = FakeSessionRepository(freshSession())
        val auth = FakeAuthService(
            failure = XiaomiAuthException("expired", rejected = true)
        )
        val cookies = FakeCookieCleaner()
        val viewModel = viewModel(store = store, auth = auth, cookieCleaner = cookies)

        val event = async { viewModel.events.first() }
        viewModel.loadAccount(force = true)
        advanceUntilIdle()

        assertEquals(2, auth.calls)
        assertNull(store.session)
        assertNull(viewModel.uiState.value.session)
        assertEquals("expired", viewModel.uiState.value.accountError)
        assertTrue(cookies.cleared)
        assertEquals(UblEvent.SessionExpired, event.await())
    }

    @Test
    fun recoverableAccountFailureKeepsTheSessionForRetry() = runTest(dispatcher) {
        val store = FakeSessionRepository(freshSession())
        val auth = FakeAuthService(
            failure = XiaomiAuthException(
                "Xiaomi STS did not return an unlock service token."
            )
        )
        val cookies = FakeCookieCleaner()
        val viewModel = viewModel(store = store, auth = auth, cookieCleaner = cookies)

        viewModel.loadAccount(force = true)
        advanceUntilIdle()

        assertEquals(2, auth.calls)
        assertNotNull(store.session)
        assertNotNull(viewModel.uiState.value.session)
        assertFalse(cookies.cleared)
        assertTrue(
            viewModel.uiState.value.accountError.orEmpty()
                .contains("unlock service token")
        )
    }

    @Test
    fun identityRequirementKeepsTheSessionAndEmitsAnIdentityEvent() = runTest(dispatcher) {
        val store = FakeSessionRepository(freshSession())
        val url = "https://account.xiaomi.com/fe/service/identity/authStart?sid=unlockApi"
        val auth = FakeAuthService(failure = XiaomiIdentityRequiredException(url, 1))
        val cookies = FakeCookieCleaner()
        val viewModel = viewModel(store = store, auth = auth, cookieCleaner = cookies)

        val event = async { viewModel.events.first() }
        viewModel.loadAccount(force = true)
        advanceUntilIdle()

        assertEquals(1, auth.calls)
        assertNotNull(store.session)
        assertFalse(cookies.cleared)
        assertEquals(url, viewModel.uiState.value.identityUrl)
        assertNull(viewModel.uiState.value.accountError)
        assertEquals(UblEvent.IdentityRequired(url), event.await())
    }

    @Test
    fun identityVerificationRefreshesTheSessionAndResolvesTheAccount() = runTest(dispatcher) {
        val store = FakeSessionRepository(freshSession())
        val url = "https://account.xiaomi.com/fe/service/identity/authStart?sid=unlockApi"
        val auth = FakeAuthService(failure = XiaomiIdentityRequiredException(url, 1))
        val viewModel = viewModel(store = store, auth = auth)

        viewModel.loadAccount(force = true)
        advanceUntilIdle()
        assertEquals(url, viewModel.uiState.value.identityUrl)

        auth.failure = null
        viewModel.onIdentityVerified(
            XiaomiSession(
                passToken = "new-pass",
                deviceId = "new-device",
                userId = "new-user"
            )
        )
        advanceUntilIdle()

        assertEquals(2, auth.calls)
        assertEquals("new-pass", store.session?.passToken)
        assertEquals("new-device", store.session?.deviceId)
        assertEquals("new-user", store.session?.userId)
        assertEquals(ACCOUNT, viewModel.uiState.value.account)
        assertNull(viewModel.uiState.value.identityUrl)
    }

    @Test
    fun cancellingIdentityVerificationKeepsTheSession() = runTest(dispatcher) {
        val store = FakeSessionRepository(freshSession())
        val url = "https://account.xiaomi.com/fe/service/identity/authStart?sid=unlockApi"
        val auth = FakeAuthService(failure = XiaomiIdentityRequiredException(url, 1))
        val viewModel = viewModel(store = store, auth = auth)

        viewModel.loadAccount(force = true)
        advanceUntilIdle()
        viewModel.cancelIdentityVerification()

        assertNull(viewModel.uiState.value.identityUrl)
        assertNotNull(store.session)
        assertEquals("identity-cancelled", viewModel.uiState.value.accountError)
        assertTrue(viewModel.uiState.value.identityCancelled)

        // Re-entering the account screen must not immediately reopen the
        // identity WebView; only an explicit Retry may start it again.
        val callsAfterCancel = auth.calls
        viewModel.loadAccount()
        advanceUntilIdle()
        assertEquals(callsAfterCancel, auth.calls)
    }

    @Test
    fun prepareUnlockRetriesOnceWithAFreshNonce() = runTest(dispatcher) {
        val unlock = FakeUnlockService().apply {
            clearFailures.add(XiaomiApiException(10004, "Request has expired"))
        }
        val viewModel = viewModel(unlock = unlock)

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.prepareUnlock()
        advanceUntilIdle()

        assertEquals(2, unlock.nonceCalls)
        assertEquals(2, unlock.clearCalls)
        assertEquals(listOf("nonce-1", "nonce-2"), unlock.clearNonces)
        assertEquals("notice-2", viewModel.uiState.value.preflightNotice)
        assertNull(viewModel.uiState.value.preflightError)
    }

    @Test
    fun prepareUnlockRefreshesTheAccountAfterAnUnlockAuthFailure() = runTest(dispatcher) {
        val auth = FakeAuthService()
        val unlock = FakeUnlockService().apply {
            clearFailures.add(XiaomiAuthException("expired"))
        }
        val viewModel = viewModel(auth = auth, unlock = unlock)

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.prepareUnlock()
        advanceUntilIdle()

        assertEquals(1, auth.calls)
        assertEquals(2, unlock.clearCalls)
        assertEquals("notice-2", viewModel.uiState.value.preflightNotice)
        assertNull(viewModel.uiState.value.preflightError)
    }

    @Test
    fun confirmUnlockRequestsAFreshNonceAndDoesNotRepeatClear() = runTest(dispatcher) {
        val transport = FakeFastbootTransport()
        val unlock = FakeUnlockService().apply {
            unlockFailures.add(XiaomiApiException(10005, "Invalid Nonce request"))
        }
        val viewModel = viewModel(unlock = unlock, usb = FakeFastbootUsbService(transport = transport))

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.prepareUnlock()
        advanceUntilIdle()
        viewModel.confirmUnlock()
        advanceUntilIdle()

        assertEquals(UnlockPhase.Success, viewModel.uiState.value.unlockPhase)
        // clearDevice consumed nonce-1. ahaUnlock then gets its own nonce for
        // every attempt instead of reusing the preflight nonce.
        assertEquals(listOf("nonce-2", "nonce-3"), unlock.unlockNonces)
        assertEquals(listOf("nonce-1"), unlock.clearNonces)
        assertEquals(3, unlock.nonceCalls)
        assertEquals(1, unlock.clearCalls)
        assertEquals("notice-1", viewModel.uiState.value.preflightNotice)
        assertEquals(1, transport.downloadCalls)
        assertEquals(1, transport.unlockCalls)
    }

    @Test
    fun rateLimitErrorIsNotRetried() = runTest(dispatcher) {
        val unlock = FakeUnlockService().apply {
            clearFailures.add(XiaomiApiException(10002, "The same IP request too often"))
        }
        val viewModel = viewModel(unlock = unlock)

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.prepareUnlock()
        advanceUntilIdle()

        assertEquals(1, unlock.nonceCalls)
        assertEquals(1, unlock.clearCalls)
        assertTrue(viewModel.uiState.value.preflightError.orEmpty().contains("10002"))
    }

    @Test
    fun logoutClearsSessionAndCookies() = runTest(dispatcher) {
        val store = FakeSessionRepository(freshSession())
        val cookies = FakeCookieCleaner()
        val viewModel = viewModel(store = store, cookieCleaner = cookies)

        viewModel.logout()

        assertNull(store.session)
        assertNull(viewModel.uiState.value.session)
        assertTrue(cookies.cleared)
    }

    @Test
    fun authenticationErrorIsRetriedOnlyOnce() = runTest(dispatcher) {
        val auth = FakeAuthService()
        val unlock = FakeUnlockService().apply {
            unlockFailures.add(XiaomiApiException(10001, "Signature verification failed"))
            unlockFailures.add(XiaomiApiException(10001, "Signature verification failed"))
        }
        val viewModel = viewModel(auth = auth, unlock = unlock)

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.prepareUnlock()
        advanceUntilIdle()
        viewModel.confirmUnlock()
        advanceUntilIdle()

        assertEquals(2, unlock.unlockCalls)
        assertEquals(1, auth.calls)
        assertEquals(UnlockPhase.Error, viewModel.uiState.value.unlockPhase)
        assertTrue(viewModel.uiState.value.unlockError.orEmpty().contains("10001"))
    }

    @Test
    fun confirmUnlockReportsVerifiedUnlockState() = runTest(dispatcher) {
        val transport = FakeFastbootTransport(unlockedAfterUnlock = "yes")
        val viewModel = viewModel(usb = FakeFastbootUsbService(transport = transport))

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.prepareUnlock()
        advanceUntilIdle()
        viewModel.confirmUnlock()
        advanceUntilIdle()

        assertEquals(UnlockPhase.Success, viewModel.uiState.value.unlockPhase)
        assertEquals("unlock-verified", viewModel.uiState.value.unlockMessage)
        assertTrue(transport.unlockedReads >= 2)
    }

    @Test
    fun confirmUnlockAcceptsUnknownStateWhenDeviceDisconnects() = runTest(dispatcher) {
        val transport = FakeFastbootTransport(unlockedAfterUnlock = null)
        val viewModel = viewModel(usb = FakeFastbootUsbService(transport = transport))

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.prepareUnlock()
        advanceUntilIdle()
        viewModel.confirmUnlock()
        advanceUntilIdle()

        assertEquals(UnlockPhase.Success, viewModel.uiState.value.unlockPhase)
        assertEquals("unlock-unverified", viewModel.uiState.value.unlockMessage)
    }

    @Test
    fun confirmUnlockFailsWhenDeviceStillReportsLocked() = runTest(dispatcher) {
        val transport = FakeFastbootTransport(unlockedAfterUnlock = "no")
        val viewModel = viewModel(usb = FakeFastbootUsbService(transport = transport))

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.prepareUnlock()
        advanceUntilIdle()
        viewModel.confirmUnlock()
        advanceUntilIdle()

        assertEquals(UnlockPhase.Error, viewModel.uiState.value.unlockPhase)
        assertEquals("unlock-still-locked", viewModel.uiState.value.unlockError)
        assertEquals(1, transport.unlockCalls)
    }

    @Test
    fun prepareUnlockReusesRecentPreflightForTheSameDevice() = runTest(dispatcher) {
        val unlock = FakeUnlockService()
        val viewModel = viewModel(unlock = unlock)

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.prepareUnlock()
        advanceUntilIdle()
        viewModel.prepareUnlock()
        advanceUntilIdle()

        assertEquals(1, unlock.nonceCalls)
        assertEquals(1, unlock.clearCalls)
        assertEquals("notice-1", viewModel.uiState.value.preflightNotice)
    }

    @Test
    fun prepareUnlockRefreshesAfterCooldownExpires() = runTest(dispatcher) {
        var now = 1_700_000_000_000L
        val store = FakeSessionRepository(freshSession(resolvedAt = now))
        val unlock = FakeUnlockService()
        val viewModel = viewModel(store = store, unlock = unlock, now = { now })

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.prepareUnlock()
        advanceUntilIdle()
        assertEquals(1, unlock.nonceCalls)

        now += 3 * 60 * 1000L + 1L
        viewModel.prepareUnlock()
        advanceUntilIdle()

        assertEquals(2, unlock.nonceCalls)
        assertEquals(2, unlock.clearCalls)
    }

    @Test
    fun prepareUnlockErrorDoesNotBlockImmediateRetry() = runTest(dispatcher) {
        val unlock = FakeUnlockService().apply {
            clearFailures.add(XiaomiApiException(10002, "The same IP request too often"))
        }
        val viewModel = viewModel(unlock = unlock)

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.prepareUnlock()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.preflightError)

        viewModel.prepareUnlock()
        advanceUntilIdle()

        assertEquals(2, unlock.nonceCalls)
        assertEquals(2, unlock.clearCalls)
        assertEquals("notice-2", viewModel.uiState.value.preflightNotice)
        assertNull(viewModel.uiState.value.preflightError)
    }

    @Test
    fun confirmUnlockInvalidatesCachedPreflight() = runTest(dispatcher) {
        val unlock = FakeUnlockService()
        val viewModel = viewModel(unlock = unlock)

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.prepareUnlock()
        advanceUntilIdle()
        viewModel.confirmUnlock()
        advanceUntilIdle()
        assertEquals(UnlockPhase.Success, viewModel.uiState.value.unlockPhase)

        viewModel.prepareUnlock()
        advanceUntilIdle()

        assertEquals(3, unlock.nonceCalls)
        assertEquals(2, unlock.clearCalls)
    }

    @Test
    fun confirmUnlockRejectsWhenPreflightHasNotRun() = runTest(dispatcher) {
        val unlock = FakeUnlockService()
        val viewModel = viewModel(unlock = unlock)

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.confirmUnlock()
        advanceUntilIdle()

        assertEquals("unlock-not-prepared", viewModel.uiState.value.unlockError)
        assertEquals(0, unlock.nonceCalls)
        assertEquals(0, unlock.unlockCalls)
    }

    @Test
    fun forceDetectionRefreshesTheConnectedDevice() = runTest(dispatcher) {
        val transport = FakeFastbootTransport()
        val usb = FakeFastbootUsbService(transport = transport)
        val viewModel = viewModel(usb = usb)

        viewModel.startDeviceDetection()
        runCurrent()
        assertEquals("device", viewModel.uiState.value.device?.product)
        val opens = usb.openCalls

        transport.product = "new-device"
        viewModel.startDeviceDetection(force = true)
        advanceUntilIdle()

        assertEquals("new-device", viewModel.uiState.value.device?.product)
        assertEquals(opens + 1, usb.openCalls)
    }

    @Test
    fun detachAndAttachRefreshTheDeviceState() = runTest(dispatcher) {
        val usb = FakeFastbootUsbService(device = FakeFastbootDevice(1))
        val viewModel = viewModel(usb = usb)

        viewModel.startDeviceDetection()
        runCurrent()
        assertEquals(1, viewModel.uiState.value.device?.handleId)

        usb.device = null
        viewModel.onDeviceDetached()
        runCurrent()
        assertNull(viewModel.uiState.value.device)
        assertEquals(DeviceState.Waiting, viewModel.uiState.value.deviceState)

        usb.device = FakeFastbootDevice(2)
        viewModel.onDeviceAttached()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.device?.handleId)
    }

    @Test
    fun prepareUnlockRejectsAStaleDeviceHandle() = runTest(dispatcher) {
        val unlock = FakeUnlockService()
        val usb = FakeFastbootUsbService(device = FakeFastbootDevice(1))
        val viewModel = viewModel(usb = usb, unlock = unlock)

        viewModel.startDeviceDetection()
        runCurrent()
        usb.device = FakeFastbootDevice(2)

        viewModel.prepareUnlock()
        advanceUntilIdle()

        assertEquals("device-changed", viewModel.uiState.value.preflightError)
        assertEquals(0, unlock.nonceCalls)
        assertNull(viewModel.uiState.value.device)
    }

    @Test
    fun confirmUnlockRejectsAMissingDeviceId() = runTest(dispatcher) {
        val store = FakeSessionRepository(freshSession())
        val unlock = FakeUnlockService()
        val viewModel = viewModel(store = store, unlock = unlock)

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.prepareUnlock()
        advanceUntilIdle()
        store.session = store.session?.copy(deviceId = " ")

        viewModel.confirmUnlock()
        advanceUntilIdle()

        assertEquals("device-id-missing", viewModel.uiState.value.unlockError)
        assertEquals(0, unlock.unlockCalls)
    }

    @Test
    fun confirmUnlockRejectsATokenChangeBeforeStaging() = runTest(dispatcher) {
        val transport = FakeFastbootTransport()
        val unlock = FakeUnlockService()
        val viewModel = viewModel(
            unlock = unlock,
            usb = FakeFastbootUsbService(transport = transport)
        )

        viewModel.startDeviceDetection()
        runCurrent()
        viewModel.prepareUnlock()
        advanceUntilIdle()

        transport.token = "other-token"
        viewModel.confirmUnlock()
        advanceUntilIdle()

        assertEquals("device-token-changed", viewModel.uiState.value.unlockError)
        assertEquals(0, transport.downloadCalls)
        assertEquals(0, transport.unlockCalls)
    }

    @Test
    fun detectionMapsATimeoutToALocalizedMessage() = runTest(dispatcher) {
        val transport = FakeFastbootTransport().apply {
            getVarFailure = FastbootException("timeout", FastbootErrorKind.Timeout)
        }
        val viewModel = viewModel(usb = FakeFastbootUsbService(transport = transport))

        viewModel.startDeviceDetection()
        runCurrent()

        assertEquals(DeviceState.Error, viewModel.uiState.value.deviceState)
        assertEquals("device-timeout", viewModel.uiState.value.deviceMessage)

        viewModel.stopDeviceDetection()
    }

    private fun viewModel(
        store: FakeSessionRepository = FakeSessionRepository(freshSession()),
        auth: FakeAuthService = FakeAuthService(),
        unlock: FakeUnlockService = FakeUnlockService(),
        usb: FakeFastbootUsbService = FakeFastbootUsbService(),
        cookieCleaner: FakeCookieCleaner = FakeCookieCleaner(),
        now: () -> Long = System::currentTimeMillis
    ): UblViewModel {
        return UblViewModel(
            FakeDependencies(
                sessionStore = store,
                authRepository = auth,
                unlockRepository = unlock,
                usbManager = usb,
                cookieCleaner = cookieCleaner,
                stringProvider = FakeStringProvider(),
                ioDispatcher = dispatcher
            ),
            now = now
        )
    }

    private class FakeDependencies(
        override val sessionStore: SessionRepository,
        override val authRepository: XiaomiAuthService,
        override val unlockRepository: XiaomiUnlockService,
        override val usbManager: FastbootUsbService,
        override val cookieCleaner: CookieCleaner,
        override val stringProvider: StringProvider,
        override val ioDispatcher: CoroutineDispatcher
    ) : UblDependencies

    private class FakeSessionRepository(
        initial: XiaomiSession? = null
    ) : SessionRepository {
        override var session: XiaomiSession? = initial

        override fun save(session: XiaomiSession) {
            this.session = session
        }

        override fun update(transform: (XiaomiSession) -> XiaomiSession) {
            session = session?.let(transform)
        }

        override fun clear() {
            session = null
        }
    }

    private class FakeAuthService(
        private val account: XiaomiAccount = ACCOUNT,
        var failure: Exception? = null
    ) : XiaomiAuthService {
        var calls = 0

        override suspend fun resolveAccount(session: XiaomiSession): XiaomiAccount {
            calls++
            failure?.let { throw it }
            return account
        }
    }

    private class FakeUnlockService : XiaomiUnlockService {
        var nonceCalls = 0
        var clearCalls = 0
        var unlockCalls = 0
        val unlockNonces = mutableListOf<String>()
        val clearNonces = mutableListOf<String>()
        val clearFailures = ArrayDeque<Exception>()
        val unlockFailures = ArrayDeque<Exception>()

        override suspend fun requestNonce(account: XiaomiAccount): String {
            nonceCalls++
            return "nonce-$nonceCalls"
        }

        override suspend fun clearDevice(
            account: XiaomiAccount,
            product: String,
            nonce: String
        ): ClearResult {
            clearCalls++
            clearNonces += nonce
            if (clearFailures.isNotEmpty()) throw clearFailures.removeFirst()
            return ClearResult(
                notice = "notice-$clearCalls",
                cleanOrNot = 1
            )
        }

        override suspend fun requestUnlock(
            account: XiaomiAccount,
            product: String,
            deviceToken: String,
            deviceId: String,
            nonce: String
        ): UnlockResult {
            unlockCalls++
            unlockNonces += nonce
            if (unlockFailures.isNotEmpty()) throw unlockFailures.removeFirst()
            return UnlockResult(encryptData = "00")
        }
    }

    private class FakeFastbootUsbService(
        var device: FastbootDeviceHandle? = FakeFastbootDevice(1),
        var permission: Boolean = true,
        private val permissionResult: Boolean = true,
        private val transport: FakeFastbootTransport = FakeFastbootTransport()
    ) : FastbootUsbService {
        var permissionRequests = 0
        var openCalls = 0

        override fun hasUsbHost(): Boolean = true

        override fun findFastbootDevice(): FastbootDeviceHandle? = device

        override fun hasPermission(device: FastbootDeviceHandle): Boolean = permission

        override suspend fun requestPermission(device: FastbootDeviceHandle): Boolean {
            permissionRequests++
            permission = permissionResult
            return permissionResult
        }

        override fun openDevice(device: FastbootDeviceHandle): FastbootTransport {
            openCalls++
            return transport
        }
    }

    private class FakeFastbootDevice(
        override val id: Int
    ) : FastbootDeviceHandle

    private class FakeFastbootTransport(
        var product: String = "device",
        var token: String = "token",
        private val unlockedBeforeUnlock: String? = "no",
        private val unlockedAfterUnlock: String? = "yes"
    ) : FastbootTransport {
        var downloadCalls = 0
        var unlockCalls = 0
        var unlockedReads = 0
        var getVarFailure: FastbootException? = null

        override fun getVar(name: String, timeoutMs: Int): String? {
            getVarFailure?.let { throw it }
            return when (name) {
                "product" -> product
                "token" -> token
                "unlocked" -> {
                    unlockedReads++
                    if (unlockCalls > 0) unlockedAfterUnlock else unlockedBeforeUnlock
                }
                else -> null
            }
        }

        override fun oemGetToken(timeoutMs: Int): String = token

        override fun download(data: ByteArray, timeoutMs: Int): FastbootResponse {
            downloadCalls++
            return FastbootResponse(ok = true, text = "", raw = "")
        }

        override fun oemUnlock(timeoutMs: Int): FastbootResponse {
            unlockCalls++
            return FastbootResponse(ok = true, text = "", raw = "")
        }

        override fun close() = Unit
    }

    private class FakeCookieCleaner : CookieCleaner {
        var cleared = false

        override fun clearCookies() {
            cleared = true
        }
    }

    private class FakeStringProvider : StringProvider {
        override fun getString(id: Int): String = when (id) {
            R.string.account_session_expired -> "session-expired"
            R.string.account_identity_cancelled -> "identity-cancelled"
            R.string.unlock_refreshing_nonce -> "refreshing-nonce"
            R.string.unlock_success_verified -> "unlock-verified"
            R.string.unlock_success_unverified -> "unlock-unverified"
            R.string.unlock_result_still_locked -> "unlock-still-locked"
            R.string.device_error_changed -> "device-changed"
            R.string.device_error_token_changed -> "device-token-changed"
            R.string.device_error_timeout -> "device-timeout"
            R.string.device_error_disconnected -> "device-disconnected"
            R.string.device_error_open -> "device-open"
            R.string.device_error_product_missing -> "device-product-missing"
            R.string.device_error_token_missing -> "device-token-missing"
            R.string.device_error_token_reread -> "device-token-reread"
            R.string.device_error_permission_denied -> "device-permission-denied"
            R.string.error_account_or_device_missing -> "account-device-missing"
            R.string.error_unlock_not_prepared -> "unlock-not-prepared"
            R.string.error_device_id_missing -> "device-id-missing"
            R.string.unlock_error_no_data -> "unlock-no-data"
            else -> "string-$id"
        }
    }

    private companion object {
        val ACCOUNT = XiaomiAccount(
            userId = "user-id",
            region = "ID",
            host = "unlock.update.miui.com",
            ssecurity = "ssecurity",
            serviceToken = "service-token"
        )

        fun freshSession(resolvedAt: Long = System.currentTimeMillis()): XiaomiSession {
            return XiaomiSession(
                passToken = "pass-token",
                deviceId = "device-id",
                userId = "user-id",
                region = "ID",
                host = "unlock.update.miui.com",
                ssecurity = "ssecurity",
                serviceToken = "service-token",
                resolvedAt = resolvedAt
            )
        }
    }
}
