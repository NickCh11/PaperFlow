package com.example.myapplication

import com.paperflow.imageprocessing.ColorMode
import com.paperflow.imageprocessing.Mask
import com.paperflow.imageprocessing.Point
import com.paperflow.imageprocessing.Quad
import com.paperflow.imageprocessing.binarizeDocument
import com.paperflow.imageprocessing.autoColorMode
import com.paperflow.imageprocessing.extractDocument
import com.paperflow.imageprocessing.toImageSize
import com.paperflow.scanengine.ScanEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/** Calls the public Kotlin defaults used when no camera calibration is available. */
class ScanDefaultsContractTest {
    @Before fun loadNativeLibrary() { assertTrue(ScanEngine.ready()) }

    @Test fun defaultColorDecisionFindsInkOnALargerPageWithoutMutatingTheMask() {
        val source = Mat(200,200,CvType.CV_8UC3,Scalar(100.0,100.0,100.0))
        val selected = Mat(200,200,CvType.CV_8UC1,Scalar(255.0))
        val mask = object : Mask {
            override val width = 200
            override val height = 200
            override fun toMat() = selected.clone()
        }
        val quad = Quad(Point(0,0),Point(200,0),Point(200,200),Point(0,200))
        try {
            assertEquals(ColorMode.GRAYSCALE,autoColorMode(source,mask,quad))
            Imgproc.rectangle(source,org.opencv.core.Point(80.0,80.0),org.opencv.core.Point(160.0,160.0),Scalar(30.0,60.0,180.0),-1)
            assertEquals(ColorMode.COLOR,autoColorMode(source,mask,quad))
            assertEquals(200*200,Core.countNonZero(selected))
        } finally { source.release(); selected.release() }
    }

    @Test fun uncalibratedCropKeepsTheRequestedGeometryAndItsSource() {
        val source = Mat(120,180,CvType.CV_8UC3,Scalar(230.0,230.0,230.0))
        Imgproc.rectangle(source,org.opencv.core.Point(50.0,35.0),org.opencv.core.Point(90.0,80.0),Scalar(20.0,20.0,20.0),-1)
        val original = source.clone()
        val quad = Quad(Point(20,20),Point(160,20),Point(160,100),Point(20,100))
        try {
            val cropped = extractDocument(source,quad,0,ColorMode.GRAYSCALE,100_000L)
            try {
                assertEquals(140.0,cropped.cols().toDouble(),1.0)
                assertEquals(80.0,cropped.rows().toDouble(),1.0)
                assertEquals(3,cropped.channels())
                assertEquals(0.0,Core.norm(source,original,Core.NORM_INF),0.0)
                val size = cropped.size().toImageSize()
                assertEquals(cropped.cols().toDouble(),size.width,0.0)
                assertEquals(cropped.rows().toDouble(),size.height,0.0)
                assertTrue(cropped.get(35,50)[0] < cropped.get(5,5)[0])
            } finally { cropped.release() }
        } finally { source.release(); original.release() }
    }

    @Test fun defaultBitonalExportKeepsResolutionAndProducesOneBitColors() {
        val source = Mat(120,180,CvType.CV_8UC3,Scalar(230.0,230.0,230.0))
        Imgproc.rectangle(source,org.opencv.core.Point(50.0,35.0),org.opencv.core.Point(90.0,80.0),Scalar(20.0,20.0,20.0),-1)
        try {
            val binary = binarizeDocument(source)
            try {
                assertEquals(source.size(),binary.size())
                assertEquals(CvType.CV_8UC1,binary.type())
                val pixels = ByteArray(binary.total().toInt())
                binary.get(0,0,pixels)
                assertTrue(pixels.all { it == 0.toByte() || it == 255.toByte() })
                assertTrue(pixels.any { it == 0.toByte() })
                assertTrue(pixels.any { it == 255.toByte() })
                assertEquals(20.0,source.get(50,70)[0],0.0)
            } finally { binary.release() }
        } finally { source.release() }
    }
}
