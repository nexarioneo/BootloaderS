package dev.bootloaderstudio.app.data

import dev.bootloaderstudio.app.xiaomi.XiaomiSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFreshnessTest {

    private fun session(
        resolvedAt: Long?,
        ssecurity: String? = "ssecurity",
        serviceToken: String? = "serviceToken"
    ) = XiaomiSession(
        passToken = "passToken",
        deviceId = "deviceId",
        userId = "userId",
        ssecurity = ssecurity,
        serviceToken = serviceToken,
        resolvedAt = resolvedAt
    )

    @Test
    fun nullSessionIsNotFresh() {
        assertFalse(SessionFreshness.isFresh(null, now = 1_000L))
    }

    @Test
    fun missingTokensAreNotFresh() {
        assertFalse(SessionFreshness.isFresh(session(1_000L, ssecurity = null), now = 1_000L))
        assertFalse(SessionFreshness.isFresh(session(1_000L, serviceToken = null), now = 1_000L))
    }

    @Test
    fun sessionWithoutResolutionTimeIsNotFresh() {
        assertFalse(SessionFreshness.isFresh(session(null), now = 1_000L))
    }

    @Test
    fun recentlyResolvedSessionIsFresh() {
        assertTrue(SessionFreshness.isFresh(session(1_000L), now = 2_000L))
    }

    @Test
    fun expiredSessionIsNotFresh() {
        val now = SessionFreshness.TTL_MS + 2_000L
        assertFalse(SessionFreshness.isFresh(session(1_000L), now = now))
    }

    @Test
    fun futureResolutionTimeIsNotFresh() {
        assertFalse(SessionFreshness.isFresh(session(5_000L), now = 1_000L))
    }
}
