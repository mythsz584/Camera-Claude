package com.fotoia.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.ensureActive
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Melhora fotos no proprio aparelho com o ONNX Runtime. O modelo (assets/fotoia-x4.onnx) faz:
 *  1. super-resolucao 4x com Real-ESRGAN (remove ruido e recupera detalhes);
 *  2. realce: niveis, gama (luz), contraste, saturacao, clareza e nitidez.
 * Os numeros do realce sao calculados para cada foto em [analyze].
 * A foto e processada em blocos para caber na memoria do celular.
 */
class Enhancer(private val context: Context) {

    /** Intensidade do realce aplicado depois da super-resolucao. */
    class Level(
        val levels: Float,       // quanto estica o preto e o branco (0 a 1)
        val gammaWeight: Float,  // quanto corrige a luz media da foto (0 a 1)
        val gammaMin: Float,     // limite para clarear (menor = clareia mais)
        val gammaMax: Float,     // limite para escurecer
        val contrast: Float,
        val saturation: Float,
        val sharpen: Float,
        val clarity: Float
    )

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
     * Mede a foto e devolve os 7 numeros do realce, na ordem que o modelo espera:
     * preto, ganho, gama, contraste, saturacao, nitidez, clareza.
     * [ev] e o ajuste manual de luz (barra da esquerda): positivo clareia, negativo escurece.
     */
    fun analyze(src: Bitmap, level: Level, ev: Float): FloatArray {
        val w = src.width
        val h = src.height
        val histLuma = IntArray(256)
        val histMax = IntArray(256)
        val row = IntArray(w)
        var total = 0
        var y = 0
        while (y < h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val c = row[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                histLuma[(r * 77 + g * 150 + b * 29) shr 8]++
                histMax[max(r, max(g, b))]++
                total++
                x += 2
            }
            y += 2
        }
        val low = percentile(histLuma, total, 0.005f)
        val median = percentile(histLuma, total, 0.5f)
        val high = percentile(histMax, total, 0.995f)

        // Niveis: o ponto mais escuro vira preto e o mais claro vira branco, com limites.
        val black = level.levels * min(low, 0.12f)
        var white = 1f - level.levels * (1f - max(high, 0.55f))
        if (white - black < 0.35f) white = black + 0.35f
        val gain = 1f / (white - black)

        // Luz: aproxima o tom medio da foto de um valor agradavel, dentro dos limites do nivel.
        val mid = ((median - black) * gain).coerceIn(0.02f, 0.98f)
        val wanted = ln(TARGET_MEDIAN) / ln(mid)
        val auto = (1f + level.gammaWeight * (wanted - 1f)).coerceIn(level.gammaMin, level.gammaMax)
        val manual = 2f.pow(-0.5f * ev.coerceIn(-2.5f, 2.5f))

        return floatArrayOf(
            black, gain, auto * manual,
            level.contrast, level.saturation, level.sharpen, level.clarity
        )
    }

    private fun percentile(hist: IntArray, total: Int, q: Float): Float {
        val target = q * total
        var acc = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= target) return i / 255f
        }
        return 1f
    }

    /**
     * Devolve um bitmap com 4x a largura e a altura de [src], ja com o realce de [params].
     * [onProgress] recebe (blocos prontos, total de blocos) e e chamado fora da thread principal.
     */
    suspend fun enhance(src: Bitmap, params: FloatArray, onProgress: (Int, Int) -> Unit): Bitmap {
        val s = session()
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w * SCALE, h * SCALE, Bitmap.Config.ARGB_8888)

        val cols = (w + TILE - 1) / TILE
        val rows = (h + TILE - 1) / TILE
        val total = cols * rows
        val maxIn = min(TILE + 2 * PAD, max(w, h))

        val inPixels = IntArray(maxIn * maxIn)
        val inFloats = FloatArray(3 * maxIn * maxIn)
        val outPixels = IntArray(min(TILE, w) * SCALE * min(TILE, h) * SCALE)
        var spare: FloatArray? = null

        var done = 0
        onProgress(0, total)
        val paramsShape = longArrayOf(params.size.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(params), paramsShape).use { paramsTensor ->
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
                    // Recorte da borda de contexto.
                    val cx = (x0 - px0) * SCALE
                    val cy = (y0 - py0) * SCALE
                    val cw = (x1 - x0) * SCALE
                    val ch = (y1 - y0) * SCALE

                    val shape = longArrayOf(1, 3, ph.toLong(), pw.toLong())
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(inFloats, 0, 3 * area), shape).use { tensor ->
                        s.run(mapOf("input" to tensor, "params" to paramsTensor)).use { result ->
                            val fb = (result.get(0) as OnnxTensor).floatBuffer
                            val data: FloatArray
                            val base: Int
                            if (fb.hasArray()) {
                                data = fb.array()
                                base = fb.arrayOffset() + fb.position()
                            } else {
                                val copy = spare?.takeIf { it.size >= 3 * oArea } ?: FloatArray(3 * oArea)
                                spare = copy
                                fb.get(copy, 0, 3 * oArea)
                                data = copy
                                base = 0
                            }
                            var k = 0
                            for (yy in 0 until ch) {
                                val rowStart = base + (cy + yy) * ow + cx
                                for (xx in 0 until cw) {
                                    val idx = rowStart + xx
                                    val r = toByte(data[idx])
                                    val g = toByte(data[oArea + idx])
                                    val b = toByte(data[2 * oArea + idx])
                                    outPixels[k++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                                }
                            }
                        }
                    }
                    out.setPixels(outPixels, 0, cw, x0 * SCALE, y0 * SCALE, cw, ch)

                    done++
                    onProgress(done, total)
                }
            }
        }
        return out
    }

    private fun toByte(v: Float): Int {
        val x = (v * 255f + 0.5f).toInt()
        return if (x < 0) 0 else if (x > 255) 255 else x
    }

    companion object {
        const val MODEL_FILE = "fotoia-x4.onnx"
        const val SCALE = 4
        const val TILE = 192
        const val PAD = 24

        /** Tom medio que o realce procura (0 = preto, 1 = branco). */
        const val TARGET_MEDIAN = 0.46f

        val NATURAL = Level(0.5f, 0.4f, 0.80f, 1.05f, 0.10f, 1.08f, 0.5f, 0.12f)
        val STRONG = Level(0.8f, 0.6f, 0.65f, 1.08f, 0.20f, 1.20f, 1.0f, 0.25f)
        val EXTREME = Level(1.0f, 0.8f, 0.55f, 1.10f, 0.25f, 1.35f, 1.5f, 0.35f)
    }
}
