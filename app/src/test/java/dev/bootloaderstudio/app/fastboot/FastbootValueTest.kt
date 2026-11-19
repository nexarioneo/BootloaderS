package dev.bootloaderstudio.app.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootValueTest {

    @Test
    fun cleanRemovesWhitespaceButKeepsCase() {
        assertEquals("AbC123", FastbootValue.clean(" Ab C\t123\n"))
    }

    @Test
    fun normalizeRemovesWhitespaceAndLowercases() {
        assertEquals("abc123", FastbootValue.normalize(" Ab C\t123\n"))
    }

    @Test
    fun blankValuesBecomeNull() {
        assertNull(FastbootValue.clean("   "))
        assertNull(FastbootValue.normalize("\n\t"))
        assertNull(FastbootValue.product(null))
        assertNull(FastbootValue.token(" "))
    }

    @Test
    fun productComparisonIgnoresCaseAndWhitespace() {
        assertTrue(FastbootValue.isSameProduct("Device", " de vice "))
        assertFalse(FastbootValue.isSameProduct("device", "other"))
        assertFalse(FastbootValue.isSameProduct(null, "device"))
    }

    @Test
    fun tokenComparisonUsesNormalizedKeys() {
        assertTrue(FastbootValue.isSameToken("AABBCC", "aabb cc"))
        assertFalse(FastbootValue.isSameToken("aabbcc", "aabbcd"))
        assertFalse(FastbootValue.isSameToken("aabbcc", null))
    }
}
