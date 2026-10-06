package com.enzo.ilhadinamica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Converte capas, fotos de contato e de grupo em imagens leves para a ilha. */
object Img {
    private const val SIZE = 144

    fun toDataUrl(src: Bitmap?): String? {
        if (src == null || src.isRecycled || src.width <= 0 || src.height <= 0) return null
        return runCatching {
            // Recorte central quadrado para a capa nunca ficar esticada.
            val side = minOf(src.width, src.height)
            val x = (src.width - side) / 2
            val y = (src.height - side) / 2
            val square = if (src.width == src.height) src else Bitmap.createBitmap(src, x, y, side, side)
            val safe = if (square.config == Bitmap.Config.HARDWARE) square.copy(Bitmap.Config.ARGB_8888, false) else square
            val scaled = Bitmap.createScaledBitmap(safe, SIZE, SIZE, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    fun fromIcon(ctx: Context, icon: Icon?): String? {
        if (icon == null) return null
        return runCatching { toDataUrl(drawableToBitmap(icon.loadDrawable(ctx))) }.getOrNull()
    }

    private fun drawableToBitmap(d: Drawable?): Bitmap? {
        if (d == null) return null
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else SIZE
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else SIZE
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFF2C4A3A.toInt())
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return b
    }
}
