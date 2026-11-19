package dev.bootloaderstudio.app.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootProtocolTest {

    @Test
    fun parsesOkayPacket() {
        val packet = FastbootProtocol.parse("OKAYdone".toByteArray(), 8)
        assertEquals(FastbootPacketType.OKAY, packet?.type)
        assertEquals("done", packet?.text)
    }

    @Test
    fun parsesFailPacket() {
        val raw = "FAILunknown command".toByteArray()
        val packet = FastbootProtocol.parse(raw, raw.size)
        assertEquals(FastbootPacketType.FAIL, packet?.type)
        assertEquals("unknown command", packet?.text)
    }

    @Test
    fun parsesDataPacketSize() {
        val packet = FastbootProtocol.parse("DATA00001000".toByteArray(), 12)
        assertEquals(FastbootPacketType.DATA, packet?.type)
        assertEquals(4096, packet?.maxSize)
    }

    @Test
    fun parsesTextPacket() {
        val raw = "TEXT(bootloader) product: fuxi".toByteArray()
        val packet = FastbootProtocol.parse(raw, raw.size)
        assertEquals(FastbootPacketType.TEXT, packet?.type)
        assertEquals("(bootloader) product: fuxi", packet?.text)
    }

    @Test
    fun returnsNullForShortBuffer() {
        assertNull(FastbootProtocol.parse("OKA".toByteArray(), 3))
    }

    @Test
    fun extractsNamedValueFromInfoOutput() {
        val output = "INFO(bootloader) product: fuxi\nINFO(bootloader) unlocked: no"
        assertEquals("fuxi", FastbootProtocol.parseValue(output, "product"))
        assertEquals("no", FastbootProtocol.parseValue(output, "unlocked"))
    }

    @Test
    fun extractsTokenLine() {
        val output = "INFO(bootloader) token: 0x1122334455667788"
        assertEquals("0x1122334455667788", FastbootProtocol.parseValue(output, "token"))
    }

    @Test
    fun cleansProtocolPrefixes() {
        val cleaned = FastbootProtocol.cleanLines(
            "INFO(bootloader) product: fuxi\nOKAY\nFAILsomething went wrong"
        )
        assertEquals("product: fuxi\nsomething went wrong", cleaned)
    }

    @Test
    fun parseValueFallsBackToFirstLine() {
        assertEquals("fuxi", FastbootProtocol.parseValue("fuxi\nsecond", "product"))
    }

    @Test
    fun unknownPacketKeepsPayload() {
        val raw = "WHAT is this".toByteArray()
        val packet = FastbootProtocol.parse(raw, raw.size)
        assertEquals(FastbootPacketType.UNKNOWN, packet?.type)
        assertTrue(packet?.text?.contains("WHAT") == true)
    }
}
