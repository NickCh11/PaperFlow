package com.example.myapplication

import com.paperflow.imageprocessing.ColorMode
import com.paperflow.imageprocessing.Point
import com.paperflow.imageprocessing.Quad
import com.paperflow.imageprocessing.binarizeDocument
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
