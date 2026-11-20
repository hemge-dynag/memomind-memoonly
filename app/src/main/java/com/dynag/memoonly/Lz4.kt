package com.dynag.memoonly

/**
 * Raw LZ4 block compressor, a greedy single pass, ported from the phone-side
 * JavaScript of the Home Assistant app (`display.js`, `lz4CompressBlock`).
 *
 * The glasses decode one unframed block per picture: a stream of sequences,
 * each one a token (literal length in the high digit, match length in the low
 * digit), the literal bytes, the 2-byte little-endian match offset, then a
 * final literal-only run. This is exactly what `lz4_decompress_block` in
 * `GlassSDK/examples/music/music.c` expects.
 */
object Lz4 {

    private const val MATCH_TAIL = 5
    private const val MATCH_MIN = 4
    private const val OFFSET_LIMIT = 65535
    private const val TABLE_BITS = 16

    private class BlockWriter(capacity: Int) {
        var buf = ByteArray(capacity)
            private set
        var cursor = 0
            private set

        private fun ensure(extra: Int) {
            if (cursor + extra <= buf.size) return
            var size = buf.size.coerceAtLeast(16)
            while (size < cursor + extra) size *= 2
            buf = buf.copyOf(size)
        }

        fun byte(value: Int) {
            ensure(1)
            buf[cursor] = (value and 0xFF).toByte()
            cursor++
        }

        /** Extension bytes for a length whose token digit is min(15, length). */
        fun length(value: Int) {
            if (value < 15) return
            var remaining = value - 15
            while (remaining >= 255) {
                byte(255)
                remaining -= 255
            }
            byte(remaining)
        }

        fun copy(input: ByteArray, from: Int, to: Int) {
            ensure(to - from)
            System.arraycopy(input, from, buf, cursor, to - from)
            cursor += to - from
        }

        fun finish(): ByteArray = buf.copyOf(cursor)
    }

    private fun hashSlot(sequence: Int): Int {
        val mixed = sequence * -1640531535
        return (mixed ushr (32 - TABLE_BITS)) and ((1 shl TABLE_BITS) - 1)
    }

    private fun readKey(input: ByteArray, offset: Int): Int {
        return (input[offset].toInt() and 0xFF) or
            ((input[offset + 1].toInt() and 0xFF) shl 8) or
            ((input[offset + 2].toInt() and 0xFF) shl 16) or
            ((input[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeSequence(
        writer: BlockWriter,
        input: ByteArray,
        literalFrom: Int,
        literalTo: Int,
        distance: Int,
        length: Int
    ) {
        val literalLength = literalTo - literalFrom
        val matchLength = length - MATCH_MIN
        writer.byte((minOf(15, literalLength) shl 4) + minOf(15, matchLength))
        writer.length(literalLength)
        writer.copy(input, literalFrom, literalTo)
        writer.byte(distance and 0xFF)
        writer.byte((distance ushr 8) and 0xFF)
        writer.length(matchLength)
    }

    private fun closeLiteralRun(writer: BlockWriter, input: ByteArray, from: Int, to: Int) {
        val length = to - from
        if (length <= 0) return
        writer.byte(minOf(15, length) shl 4)
        writer.length(length)
        writer.copy(input, from, to)
    }

    private fun compressRange(input: ByteArray, from: Int, to: Int, writer: BlockWriter) {
        val span = to - from
        if (span <= 0) return
        val table = IntArray(1 shl TABLE_BITS) { -1 }
        val start = span - MATCH_TAIL - MATCH_MIN
        val tail = span - MATCH_TAIL
        var position = 0
        var anchor = 0
        while (position <= start) {
            val slot = hashSlot(readKey(input, from + position))
            val candidate = table[slot]
            table[slot] = position
            if (candidate < 0 || position - candidate > OFFSET_LIMIT) {
                position += 1
                continue
            }
            val distance = position - candidate
            val biased = from + candidate
            var length = 0
            while (length < MATCH_MIN) {
                if (input[from + position + length] != input[biased + length]) break
                length += 1
            }
            if (length < MATCH_MIN) {
                position += 1
                continue
            }
            while (position + length < tail &&
                input[from + position + length] == input[biased + length]
            ) {
                length += 1
            }
            writeSequence(writer, input, from + anchor, from + position, distance, length)
            position += length
            anchor = position
            if (position >= tail) break
        }
        closeLiteralRun(writer, input, from + anchor, to)
    }

    /**
     * Compress [input] as one raw LZ4 block. A 16-bit hash keeps long runs of
     * one shade matched even on a full 240 x 240 GRAY_4 cover (28 800 bytes).
     */
    fun compressBlock(input: ByteArray): ByteArray {
        val size = input.size
        if (size == 0) return ByteArray(0)
        val worst = size + (size + 254) / 255 + 16
        val writer = BlockWriter(worst)
        compressRange(input, 0, size, writer)
        return writer.finish()
    }
}
