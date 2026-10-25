package com.hatchslide.core.render.assets

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class KtxTrimTest {
    /** A KTX1 file with [levels] mips of fake payload; level i is filled with byte i. */
    private fun ktx(size: Int, levels: Int, faces: Int, keyValue: Int = 0): ByteArray {
        val chunks = ArrayList<ByteArray>()
        for (level in 0 until levels) {
            val s = maxOf(1, size shr level)
            val imageSize = s * s // one byte per texel is enough to exercise the layout
            val padded = (imageSize + 3) and 3.inv()
            val chunk = ByteBuffer.allocate(4 + padded * faces).order(ByteOrder.LITTLE_ENDIAN)
            chunk.putInt(imageSize)
            repeat(faces) {
                repeat(imageSize) { chunk.put(level.toByte()) }
                repeat(padded - imageSize) { chunk.put(0) }
            }
            chunks += chunk.array()
        }
        val header = ByteBuffer.allocate(64 + keyValue).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, 0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        for (v in intArrayOf(0x04030201, 0, 1, 0, 0x93D4, 0x8C42, size, size, 0, 0, faces, levels, keyValue)) header.putInt(v)
        return header.array() + chunks.fold(ByteArray(0)) { a, b -> a + b }
    }

    private fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }

    @Test
    fun keepsFilesWithinTheLimit() {
        val data = ktx(16, 5, 1)
        assertArrayEquals(data, bytes(KtxTrim.trim(data, 16)))
    }

    @Test
    fun dropsLevelsAboveTheLimit() {
        val trimmed = bytes(KtxTrim.trim(ktx(16, 5, 1, keyValue = 8), 4))
        val header = KtxTrim.header(trimmed)
        assertEquals(4, header.width)
        assertEquals(4, header.height)
        assertEquals(3, header.levels)
        assertEquals(0, header.keyValueBytes)
        assertEquals(0x93D4, header.internalFormat)
        // First remaining level is the old level 2 (4x4): size field 16 then bytes of value 2.
        val b = ByteBuffer.wrap(trimmed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16, b.getInt(64))
        assertEquals(2.toByte(), trimmed[68])
        assertEquals(64 + (4 + 16) + (4 + 4) + (4 + 4), trimmed.size)
    }

    @Test
    fun trimsCubemapsFaceByFace() {
        val trimmed = bytes(KtxTrim.trim(ktx(8, 4, 6), 2))
        val header = KtxTrim.header(trimmed)
        assertEquals(2, header.width)
        assertEquals(2, header.levels)
        assertEquals(6, header.faces)
        assertEquals(3.toByte(), trimmed.last { it != 0.toByte() })
        assertEquals(64 + (4 + 4 * 6) + (4 + 4 * 6), trimmed.size)
    }

    @Test
    fun alwaysKeepsTheSmallestLevel() {
        val header = KtxTrim.header(bytes(KtxTrim.trim(ktx(16, 2, 1), 1)))
        assertEquals(1, header.levels)
        assertEquals(8, header.width)
    }
}
