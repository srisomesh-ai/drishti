package com.bharatgps.drishti

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/** MobileFaceNet: 112x112 face crop -> 192-number "face fingerprint". Runs fully on the phone. */
class FaceEmbedder(context: Context) {
    private val interpreter: Interpreter
    private val batch: Int
    private val size = 112
    private val embDim: Int

    init {
        interpreter = Interpreter(loadModel(context, "MobileFaceNet.tflite"), Interpreter.Options().setNumThreads(2))
        val inShape = interpreter.getInputTensor(0).shape() // [2,112,112,3]
        batch = inShape[0]
        embDim = interpreter.getOutputTensor(0).shape()[1]
    }

    private fun loadModel(ctx: Context, name: String): MappedByteBuffer {
        val fd = ctx.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { fis ->
            return fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    /** frame must be upright. box in frame pixel coords. */
    fun embed(frame: Bitmap, box: Rect): FloatArray? {
        // square crop around the face with a small margin
        val cx = box.centerX(); val cy = box.centerY()
        val half = (maxOf(box.width(), box.height()) * 0.55f).toInt()
        val l = (cx - half).coerceAtLeast(0)
        val t = (cy - half).coerceAtLeast(0)
        val r = (cx + half).coerceAtMost(frame.width)
        val b = (cy + half).coerceAtMost(frame.height)
        if (r - l < 40 || b - t < 40) return null
        val crop = Bitmap.createBitmap(frame, l, t, r - l, b - t)
        val scaled = Bitmap.createScaledBitmap(crop, size, size, true)

        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        val buf = ByteBuffer.allocateDirect(batch * size * size * 3 * 4).order(ByteOrder.nativeOrder())
        repeat(batch) {
            for (p in px) {
                buf.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)
                buf.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)
                buf.putFloat(((p and 0xFF) - 127.5f) / 128f)
            }
        }
        buf.rewind()
        val out = Array(batch) { FloatArray(embDim) }
        interpreter.run(buf, out)
        val e = out[0]
        var n = 0f
        for (v in e) n += v * v
        n = sqrt(n)
        if (n == 0f) return null
        for (i in e.indices) e[i] /= n
        return e
    }

    fun close() = interpreter.close()
}
