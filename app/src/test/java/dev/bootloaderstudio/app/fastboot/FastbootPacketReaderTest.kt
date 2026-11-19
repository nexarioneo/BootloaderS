package dev.bootloaderstudio.app.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootPacketReaderTest {

    @Test
    fun readsCoalescedInfoAndOkayPackets() {
        val reader = FastbootPacketReader()
        val packets = reader.feed(
            "INFO(bootloader) product: fuxi\nOKAY".toByteArray()
        )

        assertEquals(2, packets.size)
        assertEquals(FastbootPacketType.INFO, packets[0].type)
        assertEquals("(bootloader) product: fuxi", packets[0].text)
        assertEquals(FastbootPacketType.OKAY, packets[1].type)
    }

    @Test
    fun readsSeveralInfoPacketsInOneTransfer() {
        val reader = FastbootPacketReader()
        val packets = reader.feed(
            "INFOline one\nINFOline two\nINFOline three\nOKAY".toByteArray()
        )

        assertEquals(4, packets.size)
        assertEquals(
            listOf(
                FastbootPacketType.INFO,
                FastbootPacketType.INFO,
                FastbootPacketType.INFO,
                FastbootPacketType.OKAY
            ),
            packets.map { it.type }
        )
        assertEquals("line one", packets[0].text)
        assertEquals("line two", packets[1].text)
        assertEquals("line three", packets[2].text)
    }

    @Test
    fun buffersPacketSplitAcrossTransfers() {
        val reader = FastbootPacketReader()

        assertTrue(reader.feed("INF".toByteArray()).isEmpty())
        val middle = reader.feed("O(bootloader) product: fuxi\n".toByteArray())
        assertEquals(1, middle.size)
        assertEquals(FastbootPacketType.INFO, middle[0].type)
        assertEquals("(bootloader) product: fuxi", middle[0].text)

        val last = reader.feed("OKAY".toByteArray())
        assertEquals(1, last.size)
        assertEquals(FastbootPacketType.OKAY, last[0].type)
    }

    @Test
    fun buffersInfoUntilTerminalPacketArrives() {
        val reader = FastbootPacketReader()

        assertTrue(reader.feed("INFOtoken: 0x1234".toByteArray()).isEmpty())
        val packets = reader.feed("OKAY".toByteArray())

        assertEquals(2, packets.size)
        assertEquals(FastbootPacketType.INFO, packets[0].type)
        assertEquals("token: 0x1234", packets[0].text)
        assertEquals(FastbootPacketType.OKAY, packets[1].type)
    }

    @Test
    fun readsDataHeaderSize() {
        val reader = FastbootPacketReader()
        val packets = reader.feed("DATA00010000".toByteArray())

        assertEquals(1, packets.size)
        assertEquals(FastbootPacketType.DATA, packets[0].type)
        assertEquals(0x10000, packets[0].maxSize)
    }

    @Test
    fun readsDataFollowedByOkay() {
        val reader = FastbootPacketReader()
        val packets = reader.feed("DATA00000800OKAY".toByteArray())

        assertEquals(2, packets.size)
        assertEquals(FastbootPacketType.DATA, packets[0].type)
        assertEquals(0x800, packets[0].maxSize)
        assertEquals(FastbootPacketType.OKAY, packets[1].type)
    }

    @Test
    fun flushesIncompleteTailAtDeadline() {
        val reader = FastbootPacketReader()

        assertTrue(reader.feed("INFOpartial message".toByteArray()).isEmpty())
        val packets = reader.flush()

        assertEquals(1, packets.size)
        assertEquals(FastbootPacketType.INFO, packets[0].type)
        assertEquals("partial message", packets[0].text)
    }

    @Test
    fun resynchronisesAfterNoise() {
        val reader = FastbootPacketReader()
        val packets = reader.feed("xxINFOhello\nOKAY".toByteArray())

        assertEquals(2, packets.size)
        assertEquals(FastbootPacketType.INFO, packets[0].type)
        assertEquals("hello", packets[0].text)
        assertEquals(FastbootPacketType.OKAY, packets[1].type)
    }

    @Test
    fun keepsTrailingOkayAfterInfoWithoutNewline() {
        val reader = FastbootPacketReader()
        val packets = reader.feed("INFOsome textOKAY".toByteArray())

        assertEquals(2, packets.size)
        assertEquals(FastbootPacketType.INFO, packets[0].type)
        assertEquals("some text", packets[0].text)
        assertEquals(FastbootPacketType.OKAY, packets[1].type)
    }

    @Test
    fun parseCanReadPacketAtOffset() {
        val raw = "junkOKAYdone".toByteArray()
        val packet = FastbootProtocol.parse(raw, 8, 4)

        assertEquals(FastbootPacketType.OKAY, packet?.type)
        assertEquals("done", packet?.text)
    }
}
