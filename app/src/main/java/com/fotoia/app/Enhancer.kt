package com.fotoia.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.ensureActive
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * Melhora fotos com o modelo Real-ESRGAN (realesr-general-x4v3), rodando no proprio
 * aparelho com o ONNX Runtime. O modelo remove ruido, recupera detalhes e amplia 4x.
 * A foto e processada em blocos para caber na memoria do celular.
 */
class Enhancer(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    @Synchronized
    private fun session(): OrtSession {
        session?.let { return it }
        val bytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        val opts = OrtSession.SessionOptions()
        val threads = min(4, max(1, Runtime.getRuntime().availableProcessors()))
        opts.setIntraOpNumThreads(threads)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        val created = env.createSession(bytes, opts)
        session = created
        return created
    }

    /**
     * Devolve um bitmap com 4x a largura e a altura de [src].
     * [onProgress] recebe (blocos prontos, total de blocos) e e chamado fora da thread principal.
     */
    suspend fun enhance(src: Bitmap, onProgress: (Int, Int) -> Unit): Bitmap {
        val s = session()
        val inputName = s.inputNames.first()
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w * SCALE, h * SCALE, Bitmap.Config.ARGB_8888)

        val cols = (w + TILE - 1) / TILE
        val rows = (h + TILE - 1) / TILE
        val total = cols * rows
        val maxIn = TILE + 2 * PAD

        val inPixels = IntArray(maxIn * maxIn)
        val inFloats = FloatArray(3 * maxIn * maxIn)
        val outFloats = FloatArray(3 * maxIn * SCALE * maxIn * SCALE)
        val outPixels = IntArray(TILE * SCALE * TILE * SCALE)

        var done = 0
        onProgress(0, total)
        for (ty in 0 until rows) {
            for (tx in 0 until cols) {
                coroutineContext.ensureActive()

                // Bloco "util" e a mesma area com uma borda extra de contexto.
                val x0 = tx * TILE
                val y0 = ty * TILE
                val x1 = min(x0 + TILE, w)
                val y1 = min(y0 + TILE, h)
                val px0 = max(x0 - PAD, 0)
                val py0 = max(y0 - PAD, 0)
                val px1 = min(x1 + PAD, w)
                val py1 = min(y1 + PAD, h)
                val pw = px1 - px0
                val ph = py1 - py0
                val area = pw * ph

                src.getPixels(inPixels, 0, pw, px0, py0, pw, ph)
                for (i in 0 until area) {
                    val c = inPixels[i]
                    inFloats[i] = ((c shr 16) and 0xFF) / 255f
                    inFloats[area + i] = ((c shr 8) and 0xFF) / 255f
                    inFloats[2 * area + i] = (c and 0xFF) / 255f
                }

                val ow = pw * SCALE
                val oh = ph * SCALE
                val oArea = ow * oh
                val shape = longArrayOf(1, 3, ph.toLong(), pw.toLong())
                OnnxTensor.createTensor(env, FloatBuffer.wrap(inFloats, 0, 3 * area), shape).use { tensor ->
                    s.run(mapOf(inputName to tensor)).use { result ->
                        val fb = (result.get(0) as OnnxTensor).floatBuffer
                        fb.get(outFloats, 0, 3 * oArea)
                    }
                }

                // Recorta a borda de contexto e grava o bloco na imagem final.
                val cx = (x0 - px0) * SCALE
                val cy = (y0 - py0) * SCALE
                val cw = (x1 - x0) * SCALE
                val ch = (y1 - y0) * SCALE
                var k = 0
                for (yy in 0 until ch) {
                    val rowStart = (cy + yy) * ow + cx
                    for (xx in 0 until cw) {
                        val idx = rowStart + xx
                        val r = toByte(outFloats[idx])
                        val g = toByte(outFloats[oArea + idx])
                        val b = toByte(outFloats[2 * oArea + idx])
                        outPixels[k++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                out.setPixels(outPixels, 0, cw, x0 * SCALE, y0 * SCALE, cw, ch)

                done++
                onProgress(done, total)
            }
        }
        return out
    }

    private fun toByte(v: Float): Int {
        val x = (v * 255f + 0.5f).toInt()
        return if (x < 0) 0 else if (x > 255) 255 else x
    }

    companion object {
        const val MODEL_FILE = "realesr-general-x4v3.onnx"
        const val SCALE = 4
        const val TILE = 128
        const val PAD = 12
    }
}
