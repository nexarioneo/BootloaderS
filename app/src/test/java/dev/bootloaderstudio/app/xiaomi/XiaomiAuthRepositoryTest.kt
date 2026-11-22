package dev.bootloaderstudio.app.xiaomi

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

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
    fun resolveAccountExchangesTheLoginJsonForUnlockCookies() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        enqueueServiceLogin(ssecurity = "c2VjcmV0")
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "serviceToken=service-token; Path=/; HttpOnly")
                .addHeader("Set-Cookie", "unlockApi_slh=slh; Path=/")
                .addHeader("Set-Cookie", "userId=42; Path=/")
                .setBody("""{"R":"","S":"OK"}""")
        )

        val account = repository.resolveAccount(session())

        assertEquals("ID", account.region)
        assertEquals("c2VjcmV0", account.ssecurity)
        assertTrue(account.serviceToken.contains("serviceToken=service-token"))
        assertTrue(account.serviceToken.contains("unlockApi_slh=slh"))
        assertTrue(account.serviceToken.contains("userId=42"))
        assertTrue(account.serviceToken.contains("cUserId=c-user"))
    }

    @Test
    fun resolveAccountSignsTheStsLocationWithTheLoginNonce() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        enqueueServiceLogin(nonce = "12345", ssecurity = "c2VjcmV0")
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "serviceToken=service-token; Path=/")
                .setBody("""{"R":"","S":"OK"}""")
        )

        repository.resolveAccount(session())

        server.takeRequest() // region
        server.takeRequest() // region config
        val loginRequest = server.takeRequest()
        assertEquals(
            "/pass/serviceLogin?sid=unlockApi&_json=true&passive=true" +
                "&hidden=false&_locale=en",
            loginRequest.path
        )
        assertTrue(loginRequest.getHeader("Cookie").orEmpty().contains("passToken=pass-token"))

        val stsRequest = server.takeRequest()
        val expectedSign = URLEncoder.encode(
            Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest("nonce=12345&c2VjcmV0".toByteArray(Charsets.UTF_8))
            ),
            "UTF-8"
        )
        assertTrue(stsRequest.path.orEmpty().contains("clientSign=$expectedSign"))
        assertEquals("https://account.xiaomi.com/", stsRequest.getHeader("Referer"))
        assertTrue(stsRequest.getHeader("User-Agent").orEmpty().contains("Mozilla"))
        // The native STS exchange still carries the passport cookie triplet,
        // but never the WebView markers or a stale unlock token.
        val stsCookie = stsRequest.getHeader("Cookie").orEmpty()
        assertTrue(stsCookie.contains("passToken=pass-token"))
        assertFalse(stsCookie.contains("pass_ua="))
        assertFalse(stsCookie.contains("passInfo="))
        assertFalse(stsCookie.contains("serviceToken="))
    }

    @Test
    fun resolveAccountReadsSsecurityFromLegacyExtensionPragma() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        server.enqueue(
            MockResponse()
                .addHeader("extension-pragma", """{"ssecurity":"legacy-secret"}""")
                .setBody(
                    """{"code":0,"result":"ok","nonce":"12345",""" +
                        """"cUserId":"c-user","location":"${stsLocation()}"}"""
                )
        )
        server.enqueue(
            MockResponse()
                .addHeader("serviceToken", "header-token")
                .setBody("""{"R":"","S":"OK"}""")
        )

        val account = repository.resolveAccount(session())

        assertEquals("legacy-secret", account.ssecurity)
    }

    @Test
    fun resolveAccountPrefersThePrefixedStsTokenHeader() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        enqueueServiceLogin()
        server.enqueue(
            MockResponse()
                .addHeader("unlockApi_serviceToken", "prefixed-token")
                .addHeader("serviceToken", "generic-token")
                .setBody("""{"R":"","S":"OK"}""")
        )

        val account = repository.resolveAccount(session())

        assertTrue(account.serviceToken.contains("serviceToken=prefixed-token"))
        assertEquals("prefixed-token", account.cookies["serviceToken"])
    }

    @Test
    fun resolveAccountPersistsStsHeaderTokensInTheAccountCookies() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        enqueueServiceLogin()
        server.enqueue(
            MockResponse()
                .addHeader("unlockApi_serviceToken", "header-token")
                .addHeader("unlockApi_slh", "header-slh")
                .addHeader("unlockApi_ph", "header-ph")
                .setBody("""{"R":"","S":"OK"}""")
        )

        val account = repository.resolveAccount(session())

        assertEquals("header-token", account.cookies["serviceToken"])
        assertEquals("header-slh", account.cookies["unlockApi_slh"])
        assertEquals("header-ph", account.cookies["unlockApi_ph"])
    }

    @Test
    fun resolveAccountKeepsTheBrowserCUserIdWhenStsDoesNotReturnOne() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"result":"ok","ssecurity":"s-security",""" +
                    """"nonce":"12345","location":"${stsLocation()}"}"""
            )
        )
        server.enqueue(
            MockResponse()
                .addHeader("serviceToken", "header-token")
                .setBody("""{"R":"","S":"OK"}""")
        )

        val account = repository.resolveAccount(
            session().copy(cookies = mapOf("cUserId" to "browser-cuser"))
        )

        assertTrue(account.serviceToken.contains("cUserId=browser-cuser"))
        assertEquals("browser-cuser", account.cookies["cUserId"])
    }

    @Test
    fun resolveAccountSendsOnlyAccountCookiesToServiceLogin() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        enqueueServiceLogin()
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "serviceToken=service-token; Path=/")
                .setBody("""{"R":"","S":"OK"}""")
        )

        repository.resolveAccount(
            session().copy(
                cookies = mapOf(
                    "cUserId" to "web-cuser",
                    "passport_ph" to "web-ph",
                    "pass_ua" to "web",
                    "passInfo" to "login-end",
                    "uLocale" to "en_US",
                    "_ga" to "GA1.1.test",
                    "serviceToken" to "web-service-token",
                    "unlockApi_slh" to "web-slh"
                )
            )
        )

        server.takeRequest() // region
        server.takeRequest() // region config
        val loginCookie = server.takeRequest().getHeader("Cookie").orEmpty()
        assertTrue(loginCookie.contains("cUserId=web-cuser"))
        assertTrue(loginCookie.contains("passport_ph=web-ph"))
        assertTrue(loginCookie.contains("passToken=pass-token"))
        // Browser-only markers make serviceLogin answer with psecurity and
        // no ssecurity, which breaks the native unlock signing key.
        assertFalse(loginCookie.contains("pass_ua="))
        assertFalse(loginCookie.contains("passInfo="))
        assertFalse(loginCookie.contains("uLocale="))
        assertFalse(loginCookie.contains("_ga="))
        assertFalse(loginCookie.contains("serviceToken="))
        assertFalse(loginCookie.contains("unlockApi_slh="))
    }

    @Test
    fun resolveAccountDropsExpiredSentinelsFromTheStsResponse() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        enqueueServiceLogin()
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "serviceToken=service-token; Path=/")
                .addHeader("Set-Cookie", "passToken=EXPIRED; Max-Age=0; Path=/")
                .addHeader("Set-Cookie", "userId=EXPIRED; Max-Age=0; Path=/")
                .setBody("""{"R":"","S":"OK"}""")
        )

        val account = repository.resolveAccount(session())

        assertTrue(account.serviceToken.contains("serviceToken=service-token"))
        assertFalse(account.serviceToken.contains("EXPIRED"))
        assertFalse(account.serviceToken.contains("passToken"))
    }

    @Test
    fun expiredSetCookieDoesNotReplaceTheSessionCookie() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"data":{"region":"ID"}}""")
                .addHeader("Set-Cookie", "passToken=EXPIRED; Max-Age=0; Path=/")
        )
        enqueueRegionConfig()
        enqueueServiceLogin()
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "serviceToken=service-token; Path=/")
                .setBody("""{"R":"","S":"OK"}""")
        )

        repository.resolveAccount(session())

        server.takeRequest() // /pass/user/login/region
        val configRequest = server.takeRequest() // /pass2/config

        val cookie = configRequest.getHeader("Cookie").orEmpty()
        assertTrue(cookie.contains("passToken=pass-token"))
        assertFalse(cookie.contains("EXPIRED"))
    }

    @Test
    fun resolveAccountSendsBrowserCookiesAndKeepsRotatedOnes() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        enqueueServiceLogin()
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "serviceToken=service-token; Path=/")
                .addHeader("Set-Cookie", "cUserId=rotated-cuser; Path=/")
                .setBody("""{"R":"","S":"OK"}""")
        )

        val account = repository.resolveAccount(
            session().copy(
                cookies = mapOf(
                    "cUserId" to "web-cuser",
                    "passport_ph" to "web-ph"
                )
            )
        )

        server.takeRequest() // region
        server.takeRequest() // region config
        val loginCookie = server.takeRequest().getHeader("Cookie").orEmpty()
        assertTrue(loginCookie.contains("cUserId=web-cuser"))
        assertTrue(loginCookie.contains("passport_ph=web-ph"))
        assertEquals("rotated-cuser", account.cookies["cUserId"])
        assertTrue(account.cookies.containsKey("passport_ph"))
    }

    @Test
    fun resolveAccountTreatsALoginPageAsAnExpiredSession() = runTest {
        server.enqueue(
            MockResponse().setBody("<!DOCTYPE html><html><body>login</body></html>")
        )

        val error = runCatching { repository.resolveAccount(session()) }.exceptionOrNull()

        assertTrue(error is XiaomiAuthException)
        assertTrue((error as XiaomiAuthException).rejected)
    }

    @Test
    fun resolveAccountIgnoresEarlierHopsAndNullSentinels() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        enqueueServiceLogin()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/sts-final?d=2")
                .addHeader("Set-Cookie", "stale=old; Path=/")
        )
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "stale=null; Path=/")
                .addHeader("Set-Cookie", "serviceToken=service-token; Path=/")
                .setBody("""{"R":"","S":"OK"}""")
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
            MockResponse().setBody(
                """{"code":0,"result":"ok","nonce":"123","location":"${stsLocation()}"}"""
            )
        )
        // The native redirect fallback also comes up empty.
        server.enqueue(MockResponse().setBody("<html><body>no pragma</body></html>"))

        val error = runCatching { repository.resolveAccount(session()) }.exceptionOrNull()

        assertTrue(error is XiaomiAuthException)
        assertFalse((error as XiaomiAuthException).rejected)
        assertTrue(error.message.orEmpty().contains("missing ssecurity"))
        assertTrue(error?.message.orEmpty().contains("notificationUrl=absent"))
    }

    @Test
    fun resolveAccountDoesNotDoubleSignAPreSignedStsLocation() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"result":"ok","ssecurity":"s-security","nonce":"12345",""" +
                    """"location":"${server.url("/sts?d=1&nonce=12345&_ssign=sig")}"}"""
            )
        )
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "serviceToken=service-token; Path=/")
                .setBody("""{"R":"","S":"OK"}""")
        )

        repository.resolveAccount(session())

        server.takeRequest() // region
        server.takeRequest() // region config
        server.takeRequest() // serviceLogin
        val sts = server.takeRequest()
        assertFalse(sts.path.orEmpty().contains("clientSign="))
    }

    @Test
    fun resolveAccountUsesTheLegacyRedirectWhenJsonHasNoSsecurity() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        // Web-flavoured answer: psecurity plus an already signed location.
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"result":"ok","securityStatus":0,""" +
                    """"psecurity":"p-secret","userId":"42","cUserId":"c-user",""" +
                    """"location":"${server.url("/sts?d=1&nonce=n1&_ssign=sig1")}"}"""
            )
        )
        // Native redirect flow: ssecurity arrives in extension-pragma and the
        // pre-signed location is followed without adding clientSign.
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader(
                    "extension-pragma",
                    """{"ssecurity":"legacy-secret","nonce":"n2"}"""
                )
                .addHeader(
                    "Location",
                    server.url("/sts?d=legacy&nonce=n2&_ssign=sig2").toString()
                )
        )
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "serviceToken=legacy-token; Path=/")
                .addHeader("Set-Cookie", "unlockApi_slh=legacy-slh; Path=/")
                .setBody("""{"R":"","S":"OK"}""")
        )

        val account = repository.resolveAccount(session())

        assertEquals("legacy-secret", account.ssecurity)
        assertTrue(account.serviceToken.contains("serviceToken=legacy-token"))
        assertTrue(account.serviceToken.contains("unlockApi_slh=legacy-slh"))

        server.takeRequest() // region
        server.takeRequest() // region config
        server.takeRequest() // JSON serviceLogin (no ssecurity)
        val legacy = server.takeRequest()
        assertEquals("/pass/serviceLogin?sid=unlockApi", legacy.path)
        val sts = server.takeRequest()
        assertFalse(sts.path.orEmpty().contains("clientSign="))
    }

    @Test
    fun resolveAccountPrefersIdentityOverALoginErrorCode() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        server.enqueue(
            MockResponse().setBody(
                """{"code":70016,"result":"error","securityStatus":1,"ssecurity":"",""" +
                    """"notificationUrl":"/fe/service/identity/authStart?sid=unlockApi&context=abc"}"""
            )
        )

        val error = runCatching { repository.resolveAccount(session()) }.exceptionOrNull()

        assertTrue(error is XiaomiIdentityRequiredException)
        assertEquals(1, (error as XiaomiIdentityRequiredException).securityStatus)
    }

    @Test
    fun resolveAccountRequestsIdentityVerificationWhenNotificationUrlIsPresent() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        server.enqueue(
            MockResponse().setBody(
                """&&&START&&&{"code":0,"result":"ok","securityStatus":1,""" +
                    """"ssecurity":"","nonce":"123",""" +
                    """"notificationUrl":"/fe/service/identity/authStart?sid=unlockApi&context=abc"}"""
            )
        )

        val error = runCatching { repository.resolveAccount(session()) }.exceptionOrNull()

        assertTrue(error is XiaomiIdentityRequiredException)
        val identity = error as XiaomiIdentityRequiredException
        assertEquals(1, identity.securityStatus)
        assertEquals(
            server.url("/fe/service/identity/authStart?sid=unlockApi&context=abc").toString(),
            identity.notificationUrl
        )
    }

    @Test
    fun resolveAccountKeepsAbsoluteNotificationUrls() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"result":"ok","securityStatus":1,"ssecurity":"",""" +
                    """"notificationUrl":"https://account.xiaomi.com/fe/service/identity/authStart?sid=unlockApi"}"""
            )
        )

        val error = runCatching { repository.resolveAccount(session()) }.exceptionOrNull()

        assertTrue(error is XiaomiIdentityRequiredException)
        assertEquals(
            "https://account.xiaomi.com/fe/service/identity/authStart?sid=unlockApi",
            (error as XiaomiIdentityRequiredException).notificationUrl
        )
    }

    @Test
    fun resolveAccountRejectsALoginErrorCode() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        server.enqueue(
            MockResponse()
                .setBody(
                    """&&&START&&&{"code":70016,"result":"error","description":"Login verification failed"}"""
                )
        )

        val error = runCatching { repository.resolveAccount(session()) }.exceptionOrNull()

        assertTrue(error is XiaomiAuthException)
        assertFalse((error as XiaomiAuthException).rejected)
        assertTrue(error.message.orEmpty().contains("70016"))
    }

    @Test
    fun resolveAccountTreatsAnUnrelatedHtmlPageAsRecoverable() = runTest {
        server.enqueue(
            MockResponse().setBody("<html><body>Gateway timeout</body></html>")
        )

        val error = runCatching { repository.resolveAccount(session()) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertFalse(error is XiaomiAuthException)
    }

    @Test
    fun resolveAccountRejectsAnStsResponseWithoutAServiceToken() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        enqueueServiceLogin()
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "userId=42; Path=/")
                .setBody("""{"R":"","S":"OK"}""")
        )

        val error = runCatching { repository.resolveAccount(session()) }.exceptionOrNull()

        assertTrue(error is XiaomiAuthException)
        assertFalse((error as XiaomiAuthException).rejected)
        assertNotNull(error.message)
    }

    @Test
    fun resolveAccountAcceptsServiceTokenFromAStsHeader() = runTest {
        enqueueRegion()
        enqueueRegionConfig()
        enqueueServiceLogin()
        server.enqueue(
            MockResponse()
                .addHeader("serviceToken", "header-token")
                .addHeader("unlockApi_slh", "header-slh")
                .setBody("""{"R":"","S":"OK"}""")
        )

        val account = repository.resolveAccount(session())

        assertTrue(account.serviceToken.contains("serviceToken=header-token"))
        assertTrue(account.serviceToken.contains("unlockApi_slh=header-slh"))
    }

    @Test
    fun resolveAccountRejectsExpiredSession() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val error = runCatching { repository.resolveAccount(session()) }.exceptionOrNull()

        assertTrue(error is XiaomiAuthException)
        assertTrue((error as XiaomiAuthException).rejected)
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

    private fun enqueueServiceLogin(
        nonce: String = "12345",
        ssecurity: String = "ssecurity"
    ) {
        server.enqueue(
            MockResponse().setBody(
                """&&&START&&&{"code":0,"result":"ok","ssecurity":"$ssecurity",""" +
                    """"nonce":"$nonce","cUserId":"c-user","location":"${stsLocation()}"}"""
            )
        )
    }

    private fun stsLocation(): String = server.url("/sts?d=1").toString()

    private fun session(): XiaomiSession {
        return XiaomiSession(
            passToken = "pass-token",
            deviceId = "device-id",
            userId = "42"
        )
    }
}
