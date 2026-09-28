package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * Renders the customised launcher icon of a clone.
 *
 * The previous implementation had this logic in a method that was never called, so the badge the setup
 * screen promised never reached the generated APK. Here it produces the PNG bytes that
 * [com.example.engine.core.ApkTransformer] injects over the original launcher icon.
 */
object BadgeRenderer {

    private const val MIN_SIZE = 192

    /**
     * @param original the source app's launcher icon.
     * @param badgeNumber number drawn into the badge, `null` for no badge.
     * @param badgeColor ARGB colour of the badge.
     * @param rotationDegrees rotation applied to the icon bitmap.
     * @param invertColors colour inversion of the icon bitmap.
     */
    fun render(
        original: Bitmap,
        badgeNumber: Int?,
        badgeColor: Long,
        rotationDegrees: Float,
        invertColors: Boolean
    ): ByteArray {
        val size = maxOf(original.width, original.height, MIN_SIZE)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (invertColors) {
            paint.colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        // centre the (possibly non square) source icon
        val left = (size - original.width) / 2f
        val top = (size - original.height) / 2f
        canvas.save()
        if (rotationDegrees != 0f) {
            canvas.rotate(rotationDegrees, size / 2f, size / 2f)
        }
        canvas.drawBitmap(original, left, top, paint)
        canvas.restore()

        if (badgeNumber != null) {
            val radius = (size * 0.22f).coerceAtLeast(28f)
            val centerX = size - radius - size * 0.02f
            val centerY = radius + size * 0.02f

            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = badgeColor.toInt()
                style = Paint.Style.FILL
            }
            canvas.drawCircle(centerX, centerY, radius, badgePaint)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = radius * 0.10f
            }
            canvas.drawCircle(centerX, centerY, radius, borderPaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = radius * 1.15f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val text = badgeNumber.toString()
            val offset = (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, centerX, centerY - offset, textPaint)
        }

        val output = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.PNG, 100, output)
        return output.toByteArray()
    }
}
