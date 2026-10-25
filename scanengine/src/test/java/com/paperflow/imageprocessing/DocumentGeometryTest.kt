package com.paperflow.imageprocessing

import com.paperflow.imageprocessing.quad.convexHull
import com.paperflow.imageprocessing.quad.findQuadFromContourOrientation
import com.paperflow.imageprocessing.quad.minAreaRect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class DocumentGeometryTest {
    @Test fun unresolvableContourIntersectionsReturnNoQuadInsteadOfUnstableCorners() {
        fun rectangle(offset: Double): List<org.opencv.core.Point> = buildList {
            val edge = 32768.0
            for (i in 0 until 32) add(org.opencv.core.Point(offset + i * 1024.0, offset))
            for (i in 0 until 32) add(org.opencv.core.Point(offset + edge, offset + i * 1024.0))
            for (i in 0 until 32) add(org.opencv.core.Point(offset + edge - i * 1024.0, offset + edge))
            for (i in 0 until 32) add(org.opencv.core.Point(offset, offset + edge - i * 1024.0))
        }
        assertEquals(4, findQuadFromContourOrientation(rectangle(0.0))!!.size)
        // These points remain distinct, but adding a unit direction to their
        // fitted centers cannot be represented accurately by a Double.
        assertNull(findQuadFromContourOrientation(rectangle(Math.scalb(1.0, 60))))
    }

    @Test fun nonfiniteAndOverflowingCropCornersAreNeverAcceptedAsConvex() {
        val valid = listOf(Point(0, 0), Point(100, 0), Point(100, 200), Point(0, 200))
        for (corner in valid.indices) for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (axis in 0..1) {
                val points = valid.toMutableList()
                points[corner] = if (axis == 0) Point(value, points[corner].y) else Point(points[corner].x, value)
                assertFalse("Invalid corner $corner/$axis/$value", Quad(points[0], points[1], points[2], points[3]).isConvex())
            }
        }
        assertFalse(Quad(Point(0, 0), Point(Double.MAX_VALUE, 0.0), Point(Double.MAX_VALUE, Double.MAX_VALUE), Point(0.0, Double.MAX_VALUE)).isConvex())
        assertTrue(Quad(valid[0], valid[1], valid[2], valid[3]).isConvex())
    }

    @Test fun quadRotationsPreserveThePageCornersAndSideLengths() {
        val quad = Quad(Point(10, 15), Point(70, 15), Point(70, 50), Point(10, 50))
        val size = ImageSize(100, 80)
        val expected = listOf(
            quad,
            Quad(Point(30, 10), Point(65, 10), Point(65, 70), Point(30, 70)),
            Quad(Point(30, 30), Point(90, 30), Point(90, 65), Point(30, 65)),
            Quad(Point(15, 30), Point(50, 30), Point(50, 90), Point(15, 90))
        )
        for (turns in 0..7) {
            val rotated = quad.rotate90(turns, size)
            assertEquals(expected[turns % 4], rotated)
            assertTrue(rotated.isConvex())
            assertEquals(190.0, rotated.edges().sumOf { it.norm() }, 1e-9)
        }
        assertEquals(5.0, Line(Point(0, 0), Point(3, 4)).norm(), 0.0)
    }

    @Test fun shuffledCornersAndNonuniformScalingRetainTheirGeometry() {
        val points = listOf(Point(10, 20), Point(90, 20), Point(90, 60), Point(10, 60))
        val expected = Quad(points[0], points[1], points[2], points[3])
        for (seed in 0..20) assertEquals(expected, createQuad(points.shuffled(kotlin.random.Random(seed))))
        assertThrows(IllegalArgumentException::class.java) { createQuad(points.take(3)) }
        val scaled = expected.scaledTo(100, 80, 200, 240)
        assertEquals(Quad(Point(20, 60), Point(180, 60), Point(180, 180), Point(20, 180)), scaled)
        assertEquals(expected, scaled.scaledTo(200.0, 240.0, 100.0, 80.0))
        assertTrue(isInsideImage(Point(0, 0), ImageSize(100, 80)))
        assertTrue(isInsideImage(Point(100, 80), ImageSize(100, 80)))
        for (outside in listOf(Point(-1, 40), Point(101, 40), Point(40, -1), Point(40, 81)))
            assertFalse(isInsideImage(outside, ImageSize(100, 80)))
        assertFalse(Quad(Point(0, 0), Point(10, 0), Point(0, 10), Point(10, 10)).isConvex())
    }

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
