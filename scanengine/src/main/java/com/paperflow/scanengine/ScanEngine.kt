package com.paperflow.scanengine

import android.content.Context
import android.graphics.Bitmap
import com.paperflow.imageprocessing.*
import com.paperflow.imageprocessing.Point
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Local scan engine. Normalized TL, TR, BR, BL coordinates at the Java boundary. */
object ScanEngine {
    private lateinit var context: Context
    private var interpreter: Interpreter? = null
    @JvmStatic fun configure(value: Context) { context = value.applicationContext }
    @JvmStatic fun ready(): Boolean = OpenCVLoader.initLocal()
    class Analysis(@JvmField val corners: FloatArray?, @JvmField val mask: FloatArray,
                   @JvmField val width: Int, @JvmField val height: Int)
    private class ProbabilityMask(override val width: Int, override val height: Int, val values: FloatArray, val binary: Boolean = false): Mask {
        override fun toMat(): Mat = Mat(height, width, if (binary) CvType.CV_8UC1 else CvType.CV_32FC1).also { mat ->
            if (binary) mat.put(0,0,ByteArray(values.size) { if(values[it]>=0.5f) 255.toByte() else 0 })
            else mat.put(0,0,values)
        }
    }
    @Synchronized private fun load(): Interpreter {
        interpreter?.let { return it }
        check(ready()) { "Image processing is unavailable" }
        val bytes = context.assets.open("scanner/page-segmentation.tflite").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(hash == "96e14d7e610dd0c27b768b228fbc553b4ec119ebe68f3a3594029a25400691d2") { "Invalid scan model" }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        buffer.rewind()
        return Interpreter(buffer, Interpreter.Options().setNumThreads(2)).also { interpreter = it }
    }
    @JvmStatic @Synchronized fun analyze(bitmap: Bitmap, live: Boolean): Analysis {
        val net = load()
        val shape = net.getOutputTensor(0).shape()
        val h = shape[1]; val w = shape[2]
        val input = TensorImage(org.tensorflow.lite.DataType.FLOAT32).also { it.load(bitmap) }
        val processor = ImageProcessor.Builder().add(ResizeOp(h, w, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(127.5f, 127.5f)).build()
        val output = ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.nativeOrder())
        net.run(processor.process(input).buffer, output)
        output.rewind()
        val values = FloatArray(w*h); output.asFloatBuffer().get(values)
        for (i in values.indices) values[i] = values[i].coerceIn(0f, 1f)
        return Analysis(quad(values,w,h,bitmap.width,bitmap.height,live),values,w,h)
    }
    @JvmStatic fun quad(values: FloatArray, w: Int, h: Int, imageW: Int, imageH: Int, live: Boolean): FloatArray? {
        check(ready())
        val q = detectDocumentQuad(ProbabilityMask(w,h,values), ImageSize(imageW.toDouble(),imageH.toDouble()), if(live) Mode.LIVE_ANALYSIS else Mode.CAPTURE) ?: return null
        return floatArrayOf((q.topLeft.x/w).toFloat(),(q.topLeft.y/h).toFloat(),(q.topRight.x/w).toFloat(),(q.topRight.y/h).toFloat(),(q.bottomRight.x/w).toFloat(),(q.bottomRight.y/h).toFloat(),(q.bottomLeft.x/w).toFloat(),(q.bottomLeft.y/h).toFloat())
    }
    private fun full(w: Int,h: Int) = Quad(Point(0,0),Point(w,0),Point(w,h),Point(0,h))
    @JvmStatic fun colorMode(source: Bitmap): Int {
        check(ready()); val rgba=Mat(); val bgr=Mat()
        try {
            Utils.bitmapToMat(source,rgba); Imgproc.cvtColor(rgba,bgr,Imgproc.COLOR_RGBA2BGR)
            val q=full(source.width,source.height)
            val mask=ProbabilityMask(2,2,FloatArray(4){1f},true)
            return autoColorMode(bgr,mask,q).ordinal
        } finally { rgba.release(); bgr.release() }
    }
    @JvmStatic fun enhance(source: Bitmap, mode: Int): Bitmap {
        check(ready()); val rgba=Mat(); val bgr=Mat(); var enhanced: Mat?=null
        try {
            Utils.bitmapToMat(source,rgba); Imgproc.cvtColor(rgba,bgr,Imgproc.COLOR_RGBA2BGR)
            enhanced=enhanceCapturedImage(bgr,ColorMode.entries[mode.coerceIn(0,2)])
            Imgproc.cvtColor(enhanced,rgba,Imgproc.COLOR_BGR2RGBA)
            return Bitmap.createBitmap(rgba.cols(),rgba.rows(),Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba,it) }
        } finally { rgba.release(); bgr.release(); enhanced?.release() }
    }
    @JvmStatic fun crop(source: Bitmap, points: FloatArray): Bitmap {
        check(ready()); val rgba=Mat(); val src=MatOfPoint2f(); val dst=MatOfPoint2f(); val result=Mat(); var transform: Mat?=null
        try {
            Utils.bitmapToMat(source,rgba)
            val p=List(4){Point((points[2*it]*source.width).toDouble(),(points[2*it+1]*source.height).toDouble())}
            val q=Quad(p[0],p[1],p[2],p[3]); require(q.isConvex()) { "Invalid crop corners" }
            val dimensions=estimateRealDimensions(q,source.width,source.height,null).snapToStandardFormat().toPixelDimensions(q)
            val w=dimensions.first.coerceIn(32.0,6000.0); val h=dimensions.second.coerceIn(32.0,6000.0)
            src.fromArray(*p.map { org.opencv.core.Point(it.x,it.y) }.toTypedArray())
            dst.fromArray(org.opencv.core.Point(0.0,0.0),org.opencv.core.Point(w,0.0),org.opencv.core.Point(w,h),org.opencv.core.Point(0.0,h))
            transform=Imgproc.getPerspectiveTransform(src,dst)
            Imgproc.warpPerspective(rgba,result,transform,Size(w,h),Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,Scalar(255.0,255.0,255.0,255.0))
            return Bitmap.createBitmap(result.cols(),result.rows(),Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(result,it) }
        } finally { rgba.release();src.release();dst.release();result.release();transform?.release() }
    }
}
