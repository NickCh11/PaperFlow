package com.paperflow.scanengine

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Apache-2.0 MediaPipe SelfieSegmenter model v1, using the shared open LiteRT runtime. */
object PortraitSegmenter {
    private var interpreter: Interpreter?=null
    class Mask(@JvmField val width: Int,@JvmField val height: Int,@JvmField val values: FloatArray)
    @JvmStatic @Synchronized fun mask(context: Context,source: Bitmap): Mask {
        if(interpreter==null){
            val bytes=context.assets.open("portrait/selfie_segmenter.tflite").use { it.readBytes() }
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
            check(hash=="191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b") { "Invalid portrait model" }
            val data=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes);data.rewind()
            interpreter=Interpreter(data,Interpreter.Options().setNumThreads(2))
        }
        val net=interpreter!!;val shape=net.getInputTensor(0).shape();val h=shape[1];val w=shape[2]
        val image=TensorImage(org.tensorflow.lite.DataType.FLOAT32).also { it.load(source) }
        val prepared=ImageProcessor.Builder().add(ResizeOp(h,w,ResizeOp.ResizeMethod.BILINEAR)).add(NormalizeOp(0f,255f)).build().process(image)
        val outShape=net.getOutputTensor(0).shape();val oh=outShape[1];val ow=outShape[2]
        val output=ByteBuffer.allocateDirect(oh*ow*4).order(ByteOrder.nativeOrder());net.run(prepared.buffer,output);output.rewind()
        val values=FloatArray(oh*ow);output.asFloatBuffer().get(values);return Mask(ow,oh,values)
    }
}
