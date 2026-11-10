package com.fotoia.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Foto pronta para a IA: ja girada e reduzida ao tamanho de entrada escolhido. */
class LoadedPhoto(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)

object ImageLoader {

    /**
     * Carrega o arquivo, aplica a rotacao do EXIF e limita o lado maior a [maxSide].
     * Com [crop] maior que 1 usa so o centro da foto (zoom digital): 2 = metade da largura e da altura.
     */
    fun load(file: File, maxSide: Int, crop: Float = 1f): LoadedPhoto {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        require(rawW > 0 && rawH > 0) { "imagem inválida" }

        val factor = max(1f, crop)
        val regW = min(rawW, max(16, (rawW / factor).toInt()))
        val regH = min(rawH, max(16, (rawH / factor).toInt()))

        var sample = 1
        while (max(regW, regH) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded: Bitmap? = if (factor > 1.01f) {
            val left = (rawW - regW) / 2
            val top = (rawH - regH) / 2
            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)
            try {
                decoder.decodeRegion(Rect(left, top, left + regW, top + regH), opts)
            } finally {
                decoder.recycle()
            }
        } else {
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }
        var bmp = decoded ?: throw IllegalArgumentException("imagem inválida")

        val orientation = ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = Matrix()
        var swapped = false
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> { matrix.postRotate(90f); swapped = true }
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> { matrix.postRotate(270f); swapped = true }
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f); swapped = true }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f); swapped = true }
        }
        if (!matrix.isIdentity) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }

        val longSide = max(bmp.width, bmp.height)
        if (longSide > maxSide) {
            val scale = maxSide.toFloat() / longSide
            val w = max(1, (bmp.width * scale).roundToInt())
            val h = max(1, (bmp.height * scale).roundToInt())
            bmp = Bitmap.createScaledBitmap(bmp, w, h, true)
        }
        if (bmp.config != Bitmap.Config.ARGB_8888) {
            bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        }

        val origW = if (swapped) regH else regW
        val origH = if (swapped) regW else regH
        return LoadedPhoto(bmp, origW, origH)
    }
}
