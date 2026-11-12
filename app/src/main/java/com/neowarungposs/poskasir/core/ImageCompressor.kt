package com.neowarungposs.poskasir.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Section 6: every user photo is WebP, max 800px, under 200 KB before upload. (QRIS images are NOT passed here.) */
object ImageCompressor {
    suspend fun toWebp(context: Context, uri: Uri, maxPx: Int = 800, maxBytes: Int = 200_000): ByteArray =
        withContext(Dispatchers.Default) {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: throw IllegalStateException("Gambar tidak bisa dibaca")

            val scale = minOf(1f, maxPx.toFloat() / maxOf(decoded.width, decoded.height))
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
            } else decoded

            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            var quality = 85
            var bytes: ByteArray
            do {
                val out = ByteArrayOutputStream()
                bitmap.compress(format, quality, out)
                bytes = out.toByteArray()
                quality -= 10
            } while (bytes.size > maxBytes && quality >= 25)
            if (bytes.size > maxBytes) throw IllegalStateException("Gambar terlalu besar setelah dikompres")
            bytes
        }
}
