package com.paperflow.imageprocessing

import com.paperflow.imageprocessing.quad.convexHull
import com.paperflow.imageprocessing.quad.minAreaRect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class DocumentGeometryTest {
    @Test fun hullRejectsInteriorDuplicateAndCollinearPoints() {
        val corners = listOf(Point(0, 0), Point(10, 0), Point(10, 20), Point(0, 20))
        assertEquals(corners.toSet(), convexHull(corners.reversed() + corners + listOf(Point(5, 5), Point(5, 0))).toSet())
        assertEquals(listOf(Point(0, 0), Point(4, 4)), convexHull((0..4).map { Point(it, it) }))
        assertTrue(convexHull(emptyList()).isEmpty())
        assertNull(minAreaRect(listOf(Point(0, 0), Point(1, 1))))
        assertEquals(1, minAreaRect(List(3) { Point(1, 1) })!!.size)
    }

    @Test fun minimumRectanglePreservesKnownRotatedAreaAndCanClipToImage() {
        for (angle in listOf(0, 17, 45, 89)) {
            val theta = Math.toRadians(angle.toDouble())
            val polygon = listOf(Point(0, 0), Point(20, 0), Point(20, 10), Point(0, 10)).map {
                Point(40 + it.x * cos(theta) - it.y * sin(theta), 40 + it.x * sin(theta) + it.y * cos(theta))
            }
            val center = Point(polygon.sumOf { it.x } / 4, polygon.sumOf { it.y } / 4)
            val rect = minAreaRect(polygon + center)!!
            val twiceArea = rect.indices.sumOf { i -> val a = rect[i]; val b = rect[(i + 1) % 4]; a.x * b.y - b.x * a.y }
            assertEquals(200.0, abs(twiceArea) / 2, 1e-6)
        }
        val clipped = minAreaRect(listOf(Point(-5, -8), Point(30, -8), Point(30, 40), Point(-5, 40)), 20, 25)!!
        assertTrue(clipped.all { it.x in 0.0..19.0 && it.y in 0.0..24.0 })
        assertEquals(19.0, clipped.maxOf { it.x }, 0.0)
        assertEquals(24.0, clipped.maxOf { it.y }, 0.0)
    }

    @Test fun paperSnappingRespectsOrientationSizeAndRatioTolerances() {
        for (format in PaperFormats.all) {
            assertEquals(format, EstimatedDimensions.Physical(format.widthMm * 1.01, format.heightMm * 1.01).snapToStandardFormat())
            assertEquals(EstimatedDimensions.Physical(format.heightMm, format.widthMm), EstimatedDimensions.Physical(format.heightMm * 1.01, format.widthMm * 1.01).snapToStandardFormat())
        }
        val postcard = EstimatedDimensions.Physical(100.0, 150.0)
        assertSame(postcard, postcard.snapToStandardFormat())
        val ratio = EstimatedDimensions.Ratio(2.0, 3.0)
        assertSame(ratio, ratio.snapToStandardFormat())
        assertEquals(1.5, ratio.aspectRatio, 0.0)
        assertEquals(1.5, postcard.aspectRatio, 0.0)
    }

    @Test fun parallelDocumentUsesStableMeasuredSideLengths() {
        val quad = Quad(Point(10, 20), Point(210, 20), Point(210, 320), Point(10, 320))
        val dimensions = estimateRealDimensions(quad, 1000, 1000, null)
        assertEquals(EstimatedDimensions.Ratio(200.0, 300.0), dimensions)
        assertNull(cameraIntrinsics(null, 10f)); assertNull(cameraIntrinsics(5f, null))
        assertEquals(500f, cameraIntrinsics(5f, 10f)!!.focalLengthInPixels(1000), 0f)
    }

    private fun projectedDocument(): Quad {
        // Independently project a 200 x 300 mm plane through a pinhole camera.
        val ax = Math.toRadians(24.0); val ay = Math.toRadians(31.0)
        fun project(x: Double, y: Double): Point {
            val yy = y * cos(ax); val zz = y * sin(ax)
            val xx = x * cos(ay) + zz * sin(ay); val z = -x * sin(ay) + zz * cos(ay) + 1000
            return Point(500 + 500 * xx / z, 500 + 500 * yy / z)
        }
        return Quad(project(-100.0, -150.0), project(100.0, -150.0), project(100.0, 150.0), project(-100.0, 150.0))
    }

    @Test fun perspectiveReconstructionRecoversKnownAspectAndScalesWithDistance() {
        val quad = projectedDocument()
        val inferred = estimateRealDimensions(quad, 1000, 1000, null)
        val known = estimateRealDimensions(quad, 1000, 1000, OpticalMeasures(CameraIntrinsics(5f, 10f), null))
        assertEquals(1.5, inferred.aspectRatio, 1e-6); assertEquals(1.5, known.aspectRatio, 1e-6)
        val near = estimateRealDimensions(quad, 1000, 1000, OpticalMeasures(CameraIntrinsics(5f, 10f), 1000f)) as EstimatedDimensions.Physical
        val far = estimateRealDimensions(quad, 1000, 1000, OpticalMeasures(CameraIntrinsics(5f, 10f), 2000f)) as EstimatedDimensions.Physical
        assertEquals(200.0, near.widthMm, 3.0); assertEquals(300.0, near.heightMm, 4.0)
        assertEquals(near.widthMm * 2, far.widthMm, 1e-6)
        assertEquals(near.heightMm * 2, far.heightMm, 1e-6)
        val unstable = estimateRealDimensions(quad, 1000, 1000, OpticalMeasures(CameraIntrinsics(50f, 10f), 1000f))
        assertTrue(unstable is EstimatedDimensions.Ratio)
        val averageWidth = (norm(quad.topLeft, quad.topRight) + norm(quad.bottomLeft, quad.bottomRight)) / 2
        assertEquals(averageWidth, (unstable as EstimatedDimensions.Ratio).width, 0.0)
    }
}
