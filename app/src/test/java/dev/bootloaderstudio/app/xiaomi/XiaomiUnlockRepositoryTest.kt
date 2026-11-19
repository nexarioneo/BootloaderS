package dev.bootloaderstudio.app.xiaomi

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XiaomiUnlockRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: XiaomiUnlockRepository
    private lateinit var account: XiaomiAccount

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = XiaomiUnlockRepository(OkHttpClient())
        account = XiaomiAccount(
            userId = "42",
            region = "ID",
            host = server.url("/").toString().trimEnd('/'),
            ssecurity = "AAAAAAAAAAAAAAAAAAAAAA==",
            serviceToken = "serviceToken=service-token"
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun requestNonceSendsTheSignedRequestAndReturnsTheNonce() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"nonce":"nonce-1"}"""))

        val nonce = repository.requestNonce(account)

        assertEquals("nonce-1", nonce)
        val request = server.takeRequest()
        assertEquals("/api/v2/nonce", request.path)
        assertEquals("serviceToken=service-token", request.getHeader("Cookie"))
    }

    @Test
    fun clearDeviceReturnsTheServerNotice() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"notice":"eligible","cleanOrNot":1}"""
            )
        )

        val result = repository.clearDevice(account, "device", "nonce-1")

        assertEquals("eligible", result.notice)
        assertEquals(1, result.cleanOrNot)
        assertEquals("/api/v2/unlock/device/clear", server.takeRequest().path)
    }

    @Test
    fun requestUnlockReturnsEncryptData() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"encryptData":"00ff"}"""))

        val result = repository.requestUnlock(
            account = account,
            product = "device",
            deviceToken = "token",
            deviceId = "device-id",
            nonce = "nonce-1"
        )

        assertEquals("00ff", result.encryptData)
        assertEquals("/api/v3/ahaUnlock", server.takeRequest().path)
    }

    @Test
    fun requestUnlockRejectsNonZeroServerCode() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":20036,"descEN":"waiting period"}"""
            )
        )

        val error = runCatching {
            repository.requestUnlock(
                account = account,
                product = "device",
                deviceToken = "token",
                deviceId = "device-id",
                nonce = "nonce-1"
            )
        }.exceptionOrNull()

        assertTrue(error is XiaomiApiException)
        assertEquals(20036, (error as XiaomiApiException).code)
        assertEquals("waiting period", error.serverMessage)
    }

    @Test
    fun requestUnlockRejectsInvalidEncryptData() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"encryptData":"zz"}"""))

        val error = runCatching {
            repository.requestUnlock(
                account = account,
                product = "device",
                deviceToken = "token",
                deviceId = "device-id",
                nonce = "nonce-1"
            )
        }.exceptionOrNull()

        assertTrue(error is XiaomiApiException)
        assertEquals(-1, (error as XiaomiApiException).code)
    }

    @Test
    fun requestNonceRejectsAMissingNonce() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0}"""))

        val error = runCatching { repository.requestNonce(account) }.exceptionOrNull()

        assertTrue(error is XiaomiApiException)
    }
}
