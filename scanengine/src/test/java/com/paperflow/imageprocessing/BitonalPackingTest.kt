package com.paperflow.imageprocessing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BitonalPackingTest {
    @Test fun blackBitsUseMsbFirstAndThresholdTreatsBytesAsUnsigned() {
        val gray = byteArrayOf(127, 128.toByte(), 255.toByte(), 0)
        assertArrayEquals(byteArrayOf(0x90.toByte()), packBitsMsbFirst(gray, 4, 1))
        assertArrayEquals(byteArrayOf(0, 255.toByte(), 255.toByte(), 0), unpackBitsMsbFirst(byteArrayOf(0x90.toByte()), 4, 1))
    }

    @Test fun rowPaddingDoesNotSpillIntoTheFollowingRow() {
        val gray = ByteArray(18) { index -> if ((index / 9 + index % 9) % 2 == 0) 0 else 255.toByte() }
        val packed = packBitsMsbFirst(gray, 9, 2)
        assertArrayEquals(byteArrayOf(0xaa.toByte(), 0x80.toByte(), 0x55, 0), packed)
        assertArrayEquals(gray, unpackBitsMsbFirst(packed, 9, 2))
    }

    @Test fun roundTripPreservesBinaryPixelsAcrossByteBoundaries() {
        val random = java.util.Random(7319)
        for (width in listOf(1, 7, 8, 9, 15, 16, 17, 63)) for (height in listOf(1, 2, 7)) {
            val gray = ByteArray(width * height) { if (random.nextBoolean()) 0 else 255.toByte() }
            val packed = packBitsMsbFirst(gray, width, height)
            assertEquals(((width + 7) / 8) * height, packed.size)
            assertArrayEquals(gray, unpackBitsMsbFirst(packed, width, height))
        }
    }

    @Test fun emptyDimensionsProduceEmptyBuffers() {
        for ((width, height) in listOf(0 to 0, 0 to 3, 9 to 0)) {
            assertArrayEquals(byteArrayOf(), packBitsMsbFirst(byteArrayOf(), width, height))
            assertArrayEquals(byteArrayOf(), unpackBitsMsbFirst(byteArrayOf(), width, height))
        }
    }
}
