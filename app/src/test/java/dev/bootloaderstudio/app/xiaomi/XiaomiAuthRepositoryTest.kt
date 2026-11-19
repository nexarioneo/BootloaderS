package dev.bootloaderstudio.app.xiaomi

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XiaomiAuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: XiaomiAuthRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = XiaomiAuthRepository(
            baseClient = OkHttpClient(),
            baseUrl = server.url("/").toString()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun resolveAccountAggregatesCookiesAcrossRedirects() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/pass/serviceLogin?sid=unlockApi&done=1")
                .addHeader("Set-Cookie", "passToken=redirect-token; Path=/; HttpOnly")
        )
        server.enqueue(
            MockResponse()
                .addHeader("extension-pragma", """{"ssecurity":"c2VjcmV0"}""")
                .addHeader("Set-Cookie", "serviceToken=service-token; Path=/; HttpOnly")
                .addHeader("Set-Cookie", "userId=42; Path=/")
                .setBody("""{"result":"ok"}""")
        )

        val account = repository.resolveAccount(session())

        assertEquals("ID", account.region)
        assertEquals("c2VjcmV0", account.ssecurity)
        assertTrue(account.serviceToken.contains("passToken=redirect-token"))
        assertTrue(account.serviceToken.contains("serviceToken=service-token"))
        assertTrue(account.serviceToken.contains("userId=42"))
    }

    @Test
    fun resolveAccountDropsCookiesMarkedNullByALaterHop() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/pass/serviceLogin?sid=unlockApi&done=1")
                .addHeader("Set-Cookie", "stale=old; Path=/")
        )
        server.enqueue(
            MockResponse()
                .addHeader("extension-pragma", """{"ssecurity":"ssecurity"}""")
                .addHeader("Set-Cookie", "stale=null; Path=/")
                .addHeader("Set-Cookie", "serviceToken=service-token; Path=/")
                .setBody("{}")
        )

        val account = repository.resolveAccount(session())

        assertFalse(account.serviceToken.contains("stale"))
        assertTrue(account.serviceToken.contains("serviceToken=service-token"))
    }

    @Test
    fun resolveAccountRejectsMissingSsecurity() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "serviceToken=service-token; Path=/")
                .setBody("{}")
        )

        val error = runCatching { repository.resolveAccount(session()) }.exceptionOrNull()

        assertTrue(error is XiaomiAuthException)
    }

    @Test
    fun resolveAccountRejectsExpiredSession() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val error = runCatching { repository.resolveAccount(session()) }.exceptionOrNull()

        assertTrue(error is XiaomiAuthException)
    }

    private fun enqueueRegion() {
        server.enqueue(
            MockResponse().setBody("""{"data":{"region":"ID"}}""")
        )
    }

    private fun enqueueRegionConfig() {
        server.enqueue(
            MockResponse().setBody(
                """{"regionConfig":{"Singapore":{"region.codes":["ID"]}}}"""
            )
        )
    }

    private fun session(): XiaomiSession {
        return XiaomiSession(
            passToken = "pass-token",
            deviceId = "device-id",
            userId = "42"
        )
    }
}
