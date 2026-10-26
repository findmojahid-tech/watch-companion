package com.mojahid.watchcompanion

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exports an ORIGINAL static Momo face; live watch display requires a verified vendor protocol. */
object MomoFaceExport {
    fun saveToGallery(context: Context): Boolean = runCatching {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.color = Color.rgb(7, 10, 18)
        canvas.drawRoundRect(RectF(0f, 0f, 400f, 400f), 76f, 76f, p)

        p.color = Color.rgb(16, 24, 38)
        canvas.drawRoundRect(RectF(24f, 24f, 376f, 376f), 70f, 70f, p)

        p.color = Color.rgb(130, 226, 255)
        canvas.drawRoundRect(RectF(78f, 141f, 169f, 227f), 44f, 44f, p)
        canvas.drawRoundRect(RectF(231f, 141f, 322f, 227f), 44f, 44f, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = 11f
        p.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(RectF(174f, 234f, 226f, 276f), 18f, 144f, false, p)
        p.style = Paint.Style.FILL

        p.color = Color.rgb(48, 96, 119)
        canvas.drawCircle(100f, 267f, 15f, p)
        canvas.drawCircle(300f, 267f, 15f, p)

        val now = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "momo-face-" + now + ".png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Momo")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false

        val ok = try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
        } finally {
            bitmap.recycle()
        }
        if (!ok) {
            resolver.delete(uri, null, null)
            return@runCatching false
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    }.getOrDefault(false)
}
