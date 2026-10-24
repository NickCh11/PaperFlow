package dev.davidv.translator

import dev.davidv.translator.ui.components.LiveGlSurfaceView
import org.junit.Assert.*
import org.junit.Test

class LiveFrameGeometryTest {
    private fun map(matrix: FloatArray, x: Float, y: Float) = floatArrayOf(matrix[0] * x + matrix[1] * y + matrix[2], matrix[3] * x + matrix[4] * y + matrix[5])

    @Test fun canonicalFrameCapsTheReadbackAndPreservesAspect() {
        for ((width, height) in listOf(1920 to 1080, 1080 to 1920)) {
            val (w, h) = LiveGlSurfaceView.canonicalDims(width, height)
            assertTrue(w in 1..1000 && h in 1..1000)
            // Integer raster sizes preserve aspect to within one output pixel.
            assertEquals(width.toDouble() / height, w.toDouble() / h, 1.0 / minOf(w, h))
            assertTrue(maxOf(w, h) >= 999)
        }
        assertEquals(640 to 480, LiveGlSurfaceView.canonicalDims(640, 480))
        assertEquals(0 to 0, LiveGlSurfaceView.canonicalDims(0, 480))
        assertEquals(0 to 0, LiveGlSurfaceView.canonicalDims(640, -1))
    }

    @Test fun displayTransformMapsCanonicalCornersToClipSpace() {
        val transform = LiveGlSurfaceView.displayXform(640, 480)
        assertArrayEquals(floatArrayOf(-1f, -1f), map(transform, 0f, 0f), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 1f), map(transform, 640f, 480f), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f), map(transform, 320f, 240f), 1e-6f)
        assertTrue(LiveGlSurfaceView.displayXform(0, -1).all { it.isFinite() })
    }

    @Test fun sensorQuadrantsKeepTheExpectedMirroredCornerAndCenter() {
        val corners = listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 1f), floatArrayOf(0f, 0f))
        for (quadrant in -4..7) {
            val normalized = ((quadrant % 4) + 4) % 4
            val transform = LiveGlSurfaceView.computeUvMat(100, 100, 100, 100, quadrant)
            assertArrayEquals(corners[normalized], map(transform, 0f, 0f), 1e-6f)
            assertArrayEquals(floatArrayOf(.5f, .5f), map(transform, .5f, .5f), 1e-6f)
        }
    }

    @Test fun aspectFillCropsTheWiderAxisAroundTheImageCenter() {
        val horizontalCrop = LiveGlSurfaceView.computeUvMat(200, 100, 100, 100, 0)
        assertArrayEquals(floatArrayOf(.75f, 0f), map(horizontalCrop, 0f, 0f), 1e-6f)
        assertArrayEquals(floatArrayOf(.25f, 1f), map(horizontalCrop, 1f, 1f), 1e-6f)
        val verticalCrop = LiveGlSurfaceView.computeUvMat(100, 200, 100, 100, 0)
        assertArrayEquals(floatArrayOf(1f, .25f), map(verticalCrop, 0f, 0f), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, .75f), map(verticalCrop, 1f, 1f), 1e-6f)
        for (q in 0..3) {
            val transform = LiveGlSurfaceView.computeUvMat(1920, 1080, 600, 1000, q)
            assertArrayEquals(floatArrayOf(.5f, .5f), map(transform, .5f, .5f), 1e-6f)
            for (corner in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) assertTrue(map(transform, corner.first, corner.second).all { it in 0f..1f })
        }
    }
}
