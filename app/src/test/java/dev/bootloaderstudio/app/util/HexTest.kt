package dev.bootloaderstudio.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {

    @Test
    fun encodesLowercaseHex() {
        assertEquals("00ff10ab", Hex.encode(byteArrayOf(0x00, 0xFF.toByte(), 0x10, 0xAB.toByte())))
    }

    @Test
    fun decodesHexWithWhitespace() {
        assertArrayEquals(
            byteArrayOf(0x00, 0xFF.toByte(), 0x10, 0xAB.toByte()),
            Hex.decode("00 FF 10 ab")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOddLength() {
        Hex.decode("abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidCharacters() {
        Hex.decode("zz")
    }

    @Test
    fun md5MatchesKnownVector() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", Hex.md5("hello"))
    }
}
