package dev.davidv.translator

import org.junit.Assert.*
import org.junit.Test

/** Golden packets are shared with Rust tests for the actual JNI wire format. */
class FrameResultProtocolTest {
    private fun packet(hex: String) = java.lang.Long.parseUnsignedLong(hex, 16)

    @Test fun zeroPacketMeansNoFrame() {
        assertNull(LivePipelineJni.FrameResult.unpack(0))
    }

    @Test fun signedPacketPreservesAllPayloadFieldsAndFlags() {
        val frame = LivePipelineJni.FrameResult.unpack(packet("a004a24698000500"))!!
        assertEquals(LivePipelineJni.PlanarTrackerState.LOCKED, frame.state)
        assertEquals(0x1234L, frame.anchorIdLow16)
        assertEquals(37, frame.inliers)
        assertEquals(1.25f, frame.scale, 0f)
        assertTrue(frame.compositeOk)
        assertTrue(frame.startedAcquire)
        assertTrue(frame.startedRefresh)
    }

    @Test fun saturatedPayloadDoesNotBleedIntoReservedBitsOrFlags() {
        val frame = LivePipelineJni.FrameResult.unpack(packet("dfffffffe01fffff"))!!
        assertEquals(LivePipelineJni.PlanarTrackerState.LOST, frame.state)
        assertEquals(65535, frame.inliers)
        assertEquals(65535L, frame.anchorIdLow16)
        assertEquals(2097151f / 1024, frame.scale, 0f)
        assertFalse(frame.compositeOk)
        assertFalse(frame.startedAcquire)
        assertFalse(frame.startedRefresh)
    }

    @Test fun everyTrackerStateDecodesWithoutSignExtension() {
        val packets = listOf("0000000000000400", "4000000000000400", "8000000000000400", "c000000000000400")
        for ((index, state) in LivePipelineJni.PlanarTrackerState.entries.withIndex()) {
            val frame = LivePipelineJni.FrameResult.unpack(packet(packets[index]))!!
            assertEquals(state, frame.state)
            assertEquals(1f, frame.scale, 0f)
            assertEquals(0, frame.inliers)
            assertEquals(0L, frame.anchorIdLow16)
        }
    }
}
