package com.paperflow.translation
import java.nio.ByteBuffer

/** JNI boundary to offline-translator's pinned Rust/MNN/slimt runtime. */
object RuntimeNative {
    init { System.loadLibrary("paperflow_runtime"); check(apiVersion()==2) { "Incompatible offline language runtime" } }
    @JvmStatic private external fun apiVersion(): Int
    @JvmStatic external fun open(catalog: String, base: String): String
    @JvmStatic external fun call(request: String): String
    @JvmStatic external fun image(pixels: ByteBuffer, width: Int, height: Int, request: String, output: ByteBuffer): String
    @JvmStatic external fun createLive(source: String, target: String): Long
    @JvmStatic external fun destroyLive(pointer: Long)
    @JvmStatic external fun controlLive(pointer: Long, enabled: Boolean, afScanning: Boolean, reset: Boolean)
    @JvmStatic external fun changeLiveLanguages(pointer: Long, source: String, target: String)
}
