package com.fotoia.app

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Uma foto da rajada: tamanho e leitura de uma linha de pixels ARGB. */
interface Frame {
    val width: Int
    val height: Int
    fun readRow(y: Int, out: IntArray)
    fun close() {}
}

class StackResult(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val framesUsed: Int,
    val baseIndex: Int
)

/**
 * Junta varias fotos tiradas em sequencia (rajada) numa so, como fazem as cameras de celular
 * no zoom alto: escolhe a foto mais nitida como base, alinha as outras a ela (com precisao
 * de fracao de pixel, para compensar a mao tremendo) e tira a media. A media reduz o ruido;
 * pixels que mudaram de verdade (algo se mexeu) sao ignorados para nao criar fantasmas.
 *
 * Cada foto deve ter uma margem em volta da area desejada; [keep] e a fracao central
 * (de cada lado) que sai no resultado. A margem e o espaco para o alinhamento.
 */
object BurstStacker {

    /** Diferenca media de brilho (0 a 255) acima da qual uma foto e considerada desalinhada. */
    private const val MAX_ERROR = 24f

    /** Fotos com menos que esta fracao da nitidez da melhor sao descartadas. */
    private const val MIN_SHARPNESS = 0.6f

    private class Level(val luma: ByteArray, val w: Int, val h: Int)

    private class Shift(val dx: Float, val dy: Float, val error: Float)

    fun stack(count: Int, load: (Int) -> Frame?, keep: Float): StackResult? {
        if (count <= 0) return null
        val keepF = keep.coerceIn(0.2f, 1f)

        // 1. Nitidez de cada foto, para escolher a base.
        var w = -1
        var h = -1
        val sharpness = FloatArray(count) { -1f }
        for (i in 0 until count) {
            val f = load(i) ?: continue
            try {
                if (w < 0) {
                    w = f.width
                    h = f.height
                }
                if (f.width == w && f.height == h) sharpness[i] = sharpnessOf(f, keepF)
            } finally {
                f.close()
            }
        }
        if (w <= 0) return null
        var refIndex = 0
        for (i in 1 until count) if (sharpness[i] > sharpness[refIndex]) refIndex = i
        if (sharpness[refIndex] < 0f) return null
        // Fotos bem mais borradas que a melhor (tremeu no clique) ficam de fora.
        val minSharp = MIN_SHARPNESS * sharpness[refIndex]
        for (i in 0 until count) if (i != refIndex && sharpness[i] < minSharp) sharpness[i] = -1f

        // 2. Area de saida (centro) e a foto base.
        val ow = max(1, (w * keepF).roundToInt())
        val oh = max(1, (h * keepF).roundToInt())
        val ox0 = (w - ow) / 2
        val oy0 = (h - oh) / 2
        val acc = FloatArray(3 * ow * oh)
        val weight = FloatArray(ow * oh)
        val refPix = IntArray(ow * oh)

        val refFrame = load(refIndex) ?: return null
        val refPyramid: List<Level>
        try {
            refPyramid = pyramid(lumaOf(refFrame))
            val row = IntArray(w)
            for (y in 0 until oh) {
                refFrame.readRow(oy0 + y, row)
                System.arraycopy(row, ox0, refPix, y * ow, ow)
            }
        } finally {
            refFrame.close()
        }
        for (i in 0 until ow * oh) {
            val c = refPix[i]
            acc[3 * i] = ((c shr 16) and 0xFF).toFloat()
            acc[3 * i + 1] = ((c shr 8) and 0xFF).toFloat()
            acc[3 * i + 2] = (c and 0xFF).toFloat()
            weight[i] = 1f
        }
        var used = 1

        // 3. Alinha e soma as outras fotos.
        for (i in 0 until count) {
            if (i == refIndex || sharpness[i] < 0f) continue
            val f = load(i) ?: continue
            try {
                if (f.width != w || f.height != h) continue
                val shift = align(refPyramid, pyramid(lumaOf(f)), keepF)
                if (shift.error > MAX_ERROR) continue
                merge(f, shift, ox0, oy0, ow, oh, refPix, acc, weight)
                used++
            } finally {
                f.close()
            }
        }

        val out = IntArray(ow * oh)
        for (i in 0 until ow * oh) {
            val s = 1f / weight[i]
            val r = (acc[3 * i] * s + 0.5f).toInt().coerceIn(0, 255)
            val g = (acc[3 * i + 1] * s + 0.5f).toInt().coerceIn(0, 255)
            val b = (acc[3 * i + 2] * s + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return StackResult(out, ow, oh, used, refIndex)
    }

    private fun luma(c: Int): Int =
        (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    /** Energia das diferencas entre vizinhos na area central: quanto maior, mais nitida. */
    private fun sharpnessOf(f: Frame, keep: Float): Float {
        val w = f.width
        val h = f.height
        val x0 = ((w * (1f - keep)) / 2f).toInt()
        val x1 = w - x0 - 1
        val y0 = ((h * (1f - keep)) / 2f).toInt()
        val y1 = h - y0 - 1
        var prev = IntArray(w)
        var cur = IntArray(w)
        f.readRow(y0, prev)
        var sum = 0.0
        var n = 0
        for (y in y0 + 1..y1) {
            f.readRow(y, cur)
            for (x in x0 until x1) {
                val l = luma(cur[x])
                val dx = luma(cur[x + 1]) - l
                val dy = l - luma(prev[x])
                sum += (dx * dx + dy * dy).toDouble()
                n++
            }
            val t = prev
            prev = cur
            cur = t
        }
        return if (n == 0) 0f else (sum / n).toFloat()
    }

    private fun lumaOf(f: Frame): Level {
        val w = f.width
        val h = f.height
        val out = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            f.readRow(y, row)
            val base = y * w
            for (x in 0 until w) out[base + x] = luma(row[x]).toByte()
        }
        return Level(out, w, h)
    }

    private fun pyramid(base: Level): List<Level> {
        val levels = arrayListOf(base)
        while (levels.size < 5) {
            val p = levels.last()
            val nw = p.w / 2
            val nh = p.h / 2
            if (min(nw, nh) < 40) break
            val out = ByteArray(nw * nh)
            for (y in 0 until nh) {
                val r0 = 2 * y * p.w
                val r1 = r0 + p.w
                for (x in 0 until nw) {
                    val s = (p.luma[r0 + 2 * x].toInt() and 0xFF) + (p.luma[r0 + 2 * x + 1].toInt() and 0xFF) +
                        (p.luma[r1 + 2 * x].toInt() and 0xFF) + (p.luma[r1 + 2 * x + 1].toInt() and 0xFF)
                    out[y * nw + x] = ((s + 2) shr 2).toByte()
                }
            }
            levels.add(Level(out, nw, nh))
        }
        return levels
    }

    /** Diferenca media de brilho entre a base e a foto deslocada de (sx, sy), na area central. */
    private fun sad(ref: Level, frm: Level, sx: Int, sy: Int, keep: Float, step: Int): Float {
        val w = ref.w
        val h = ref.h
        val x0 = ((w * (1f - keep)) / 2f).toInt()
        val y0 = ((h * (1f - keep)) / 2f).toInt()
        val x1 = w - x0
        val y1 = h - y0
        var sum = 0L
        var n = 0
        var y = y0
        while (y < y1) {
            val fy = (y + sy).coerceIn(0, h - 1)
            val rb = y * w
            val fb = fy * w
            var x = x0
            while (x < x1) {
                val fx = (x + sx).coerceIn(0, w - 1)
                sum += abs((ref.luma[rb + x].toInt() and 0xFF) - (frm.luma[fb + fx].toInt() and 0xFF))
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    private fun align(refP: List<Level>, frmP: List<Level>, keep: Float): Shift {
        val levels = min(refP.size, frmP.size)
        var dx = 0
        var dy = 0
        for (lev in levels - 1 downTo 0) {
            val ref = refP[lev]
            val frm = frmP[lev]
            val step = if (lev == 0) 2 else 1
            val range: Int
            if (lev == levels - 1) {
                // Busca ampla no nivel mais reduzido, ate o tamanho da margem.
                val margin = min(ref.w, ref.h) * (1f - keep) / 2f
                range = max(2, min(margin.toInt(), min(ref.w, ref.h) / 4))
            } else {
                dx *= 2
                dy *= 2
                range = 2
            }
            var best = Float.MAX_VALUE
            var bx = dx
            var by = dy
            for (v in -range..range) {
                for (u in -range..range) {
                    val s = sad(ref, frm, dx + u, dy + v, keep, step)
                    if (s < best) {
                        best = s
                        bx = dx + u
                        by = dy + v
                    }
                }
            }
            dx = bx
            dy = by
        }
        // Fracao de pixel: parabola pelos vizinhos do melhor ponto.
        val ref = refP[0]
        val frm = frmP[0]
        val c = sad(ref, frm, dx, dy, keep, 1)
        val subX = parabola(sad(ref, frm, dx - 1, dy, keep, 1), c, sad(ref, frm, dx + 1, dy, keep, 1))
        val subY = parabola(sad(ref, frm, dx, dy - 1, keep, 1), c, sad(ref, frm, dx, dy + 1, keep, 1))
        return Shift(dx + subX, dy + subY, c)
    }

    private fun parabola(a: Float, b: Float, c: Float): Float {
        val den = a - 2f * b + c
        if (den <= 1e-6f) return 0f
        return (0.5f * (a - c) / den).coerceIn(-0.5f, 0.5f)
    }

    private fun merge(
        f: Frame, s: Shift, ox0: Int, oy0: Int, ow: Int, oh: Int,
        refPix: IntArray, acc: FloatArray, weight: FloatArray
    ) {
        val w = f.width
        val h = f.height
        val rowA = IntArray(w)
        val rowB = IntArray(w)

        // Limite de diferenca para aceitar um pixel: 3x a diferenca tipica (ruido) desta foto.
        val hist = IntArray(256)
        var samples = 0
        var y = 0
        while (y < oh) {
            sampleRow(f, s, oy0 + y, rowA, rowB, h)
            var x = 0
            while (x < ow) {
                hist[diff(bilinear(rowA, rowB, ox0 + x + s.dx, w, fracY(oy0 + y, s)), refPix[y * ow + x])]++
                samples++
                x += 4
            }
            y += 4
        }
        var median = 0
        var acc0 = 0
        for (i in 0..255) {
            acc0 += hist[i]
            if (acc0 * 2 >= samples) {
                median = i
                break
            }
        }
        val limit = max(10f, 3f * median)

        for (yy in 0 until oh) {
            sampleRow(f, s, oy0 + yy, rowA, rowB, h)
            val ty = fracY(oy0 + yy, s)
            val base = yy * ow
            for (xx in 0 until ow) {
                val c = bilinear(rowA, rowB, ox0 + xx + s.dx, w, ty)
                val d = diff(c, refPix[base + xx]).toFloat()
                val wt = when {
                    d <= limit -> 1f
                    d < 2f * limit -> (2f * limit - d) / limit
                    else -> 0f
                }
                if (wt <= 0f) continue
                val i = base + xx
                acc[3 * i] += wt * ((c shr 16) and 0xFF)
                acc[3 * i + 1] += wt * ((c shr 8) and 0xFF)
                acc[3 * i + 2] += wt * (c and 0xFF)
                weight[i] += wt
            }
        }
    }

    private fun fracY(y: Int, s: Shift): Float {
        val fy = y + s.dy
        return fy - floor(fy)
    }

    private fun sampleRow(f: Frame, s: Shift, y: Int, rowA: IntArray, rowB: IntArray, h: Int) {
        val y0 = floor(y + s.dy).toInt()
        f.readRow(y0.coerceIn(0, h - 1), rowA)
        f.readRow((y0 + 1).coerceIn(0, h - 1), rowB)
    }

    private fun bilinear(rowA: IntArray, rowB: IntArray, fx: Float, w: Int, ty: Float): Int {
        val x0f = floor(fx)
        val tx = fx - x0f
        val x0 = x0f.toInt().coerceIn(0, w - 1)
        val x1 = (x0f.toInt() + 1).coerceIn(0, w - 1)
        val a = rowA[x0]
        val b = rowA[x1]
        val c = rowB[x0]
        val d = rowB[x1]
        val r = lerp2((a shr 16) and 0xFF, (b shr 16) and 0xFF, (c shr 16) and 0xFF, (d shr 16) and 0xFF, tx, ty)
        val g = lerp2((a shr 8) and 0xFF, (b shr 8) and 0xFF, (c shr 8) and 0xFF, (d shr 8) and 0xFF, tx, ty)
        val bl = lerp2(a and 0xFF, b and 0xFF, c and 0xFF, d and 0xFF, tx, ty)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun lerp2(a: Int, b: Int, c: Int, d: Int, tx: Float, ty: Float): Int {
        val top = a + (b - a) * tx
        val bot = c + (d - c) * tx
        return (top + (bot - top) * ty + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun diff(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db))
    }
}
