package com.hatchslide.core.render.assets

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Drops the mip levels of a KTX1 file that are larger than a quality tier allows, so filament-utils'
 * KTX1Loader uploads only what the tier needs (spec section 6: max texture 1024 / 2048 / 4096).
 * The level data is copied untouched; only the header is rewritten. Key-value data is dropped (the
 * texture files carry none).
 */
object KtxTrim {
    private const val IDENTIFIER_SIZE = 12
    private const val HEADER_SIZE = IDENTIFIER_SIZE + 13 * 4
    private val IDENTIFIER = byteArrayOf(
        0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, 0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** Header fields of a KTX1 file. */
    class Header(val width: Int, val height: Int, val faces: Int, val levels: Int, val keyValueBytes: Int, val internalFormat: Int)

    fun header(data: ByteArray): Header {
        require(data.size >= HEADER_SIZE && data.copyOfRange(0, IDENTIFIER_SIZE).contentEquals(IDENTIFIER)) { "not a KTX1 file" }
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(b.getInt(IDENTIFIER_SIZE) == 0x04030201) { "big-endian KTX is not supported" }
        return Header(
            width = b.getInt(IDENTIFIER_SIZE + 6 * 4),
            height = b.getInt(IDENTIFIER_SIZE + 7 * 4),
            faces = b.getInt(IDENTIFIER_SIZE + 10 * 4),
            levels = maxOf(1, b.getInt(IDENTIFIER_SIZE + 11 * 4)),
            keyValueBytes = b.getInt(IDENTIFIER_SIZE + 12 * 4),
            internalFormat = b.getInt(IDENTIFIER_SIZE + 4 * 4),
        )
    }

    /**
     * Returns [data] as a direct buffer, without the leading levels whose width or height exceeds
     * [maxSize]. At least one level is always kept.
     */
    fun trim(data: ByteArray, maxSize: Int): ByteBuffer {
        val h = header(data)
        var skip = 0
        while (skip < h.levels - 1 && maxOf(h.width shr skip, h.height shr skip) > maxSize) skip++
        if (skip == 0) return direct(data, 0, data.size)

        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var offset = HEADER_SIZE + h.keyValueBytes
        repeat(skip) {
            val imageSize = b.getInt(offset)
            offset += 4 + levelBytes(imageSize, h.faces)
        }
        val tail = data.size - offset
        val out = ByteBuffer.allocateDirect(HEADER_SIZE + tail).order(ByteOrder.LITTLE_ENDIAN)
        out.put(data, 0, HEADER_SIZE)
        out.putInt(IDENTIFIER_SIZE + 6 * 4, maxOf(1, h.width shr skip))
        out.putInt(IDENTIFIER_SIZE + 7 * 4, maxOf(1, h.height shr skip))
        out.putInt(IDENTIFIER_SIZE + 11 * 4, h.levels - skip)
        out.putInt(IDENTIFIER_SIZE + 12 * 4, 0)
        out.position(HEADER_SIZE)
        out.put(data, offset, tail)
        out.flip()
        return out
    }

    /** Bytes of one level after its size field: every face padded to 4 bytes (cube padding). */
    private fun levelBytes(imageSize: Int, faces: Int): Int {
        val padded = (imageSize + 3) and 3.inv()
        return if (faces == 6) padded * 6 else padded
    }

    private fun direct(data: ByteArray, from: Int, length: Int): ByteBuffer =
        ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder()).apply {
            put(data, from, length)
            flip()
        }
}
