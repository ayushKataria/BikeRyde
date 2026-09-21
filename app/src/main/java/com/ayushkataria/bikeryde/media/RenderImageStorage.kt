package com.ayushkataria.bikeryde.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies a picked background photo into app-scoped storage and returns its absolute path. A
 * picker's `content://` Uri permission grant can expire before a video actually renders (it runs
 * later, in the background, via WorkManager), so the customize screen copies the bytes in
 * immediately rather than holding onto the Uri.
 */
object RenderImageStorage {

    suspend fun copyToAppStorage(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "render_backgrounds").apply { mkdirs() }
        val file = File(dir, "bg_${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to read picked image")
        } catch (t: Throwable) {
            // A failed or cancelled copy would otherwise leave a partial file behind in app storage.
            file.delete()
            throw t
        }
        file.absolutePath
    }

    /** Decodes [path] downsampled to roughly fit [reqWidth]x[reqHeight], to avoid full-resolution
     * camera photos blowing up memory when several are held for a video's crossfade. */
    suspend fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, boundsOptions)
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOptions.outWidth, boundsOptions.outHeight, reqWidth, reqHeight)
            }
            BitmapFactory.decodeFile(path, options)
        }

    private fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            var halfHeight = rawHeight / 2
            var halfWidth = rawWidth / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
