package com.mojahid.watchcompanion

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 320 x 390 original Momo face optimized for the widely listed W17 Pro dial resolution.
 * This exports a normal PNG, not a proprietary watch firmware file.
 * MActivePro (com.njj.mactivepro) can import photo/album dials on supported models.
 */
object MomoFaceExport {
    fun saveToGallery(context: Context): Boolean = runCatching {
        val w = 320
        val h = 390
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                Color.rgb(6, 10, 19), Color.rgb(12, 19, 33), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null

            // Dark rounded robot head; no intense HDR bloom or overly sharp effects.
            p.color = Color.rgb(15, 24, 38)
            canvas.drawRoundRect(RectF(15f, 28f, 305f, 350f), 60f, 60f, p)
            p.color = Color.rgb(27, 40, 55)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f
            canvas.drawRoundRect(RectF(18f, 31f, 302f, 347f), 58f, 58f, p)
            p.style = Paint.Style.FILL

            // Soft cyan eyes with mild shadow, friendly and minimal.
            p.color = Color.rgb(118, 216, 247)
            p.setShadowLayer(14f, 0f, 1f, Color.argb(85, 60, 185, 235))
            canvas.drawRoundRect(RectF(63f, 151f, 137f, 204f), 27f, 27f, p)
            canvas.drawRoundRect(RectF(183f, 151f, 257f, 204f), 27f, 27f, p)
            p.clearShadowLayer()

            // Friendly subtle curved smile.
            p.color = Color.rgb(112, 210, 239)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 7f
            p.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(RectF(143f, 225f, 177f, 247f), 13f, 154f, false, p)
            p.style = Paint.Style.FILL

            // Small character mark near the bottom, away from expected time overlay.
            p.textAlign = Paint.Align.CENTER
            p.textSize = 14f
            p.typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            p.letterSpacingCompat(0f)
            p.color = Color.rgb(125, 155, 171)
            canvas.drawText("M O M O", 160f, 330f, p)

            val now = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Momo-W17-" + now + ".png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Momo")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            val ok = resolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: false
            if (!ok) {
                resolver.delete(uri, null, null)
                return@runCatching false
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            true
        } finally {
            bitmap.recycle()
        }
    }.getOrDefault(false)

    // No automatic dial transfer: vendor protocol is unverified for this exact watch.
    private fun Paint.letterSpacingCompat(@Suppress("UNUSED_PARAMETER") value: Float) {}
}
