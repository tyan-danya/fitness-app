package com.dtyan.fitdiary.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Хранилище фотографий тренажёров: копирует выбранное/снятое изображение
 * в filesDir/exercise_photos с даунскейлом и учётом EXIF-поворота.
 * В БД хранится путь ОТНОСИТЕЛЬНО filesDir (например, «exercise_photos/xxx.jpg»).
 */
class PhotoStore(private val context: Context) {

    companion object {
        private const val DIR = "exercise_photos"
        private const val MAX_DIM_PX = 1280
        private const val JPEG_QUALITY = 85
    }

    private fun photosDir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Абсолютный файл по относительному пути из БД. */
    fun fileFor(relativePath: String): File = File(context.filesDir, relativePath)

    /** Сохранить изображение из Uri (галерея или снимок камеры). Возвращает относительный путь. */
    suspend fun saveFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Не удалось прочитать изображение" }

        // Грубый даунскейл степенью двойки при декодировании, точный — после.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DIM_PX || bounds.outHeight / (sample * 2) >= MAX_DIM_PX) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Не удалось декодировать изображение")

        val rotationDegrees = resolver.openInputStream(uri)?.use {
            ExifInterface(it).rotationDegrees
        } ?: 0

        val oriented = if (rotationDegrees != 0) {
            val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
                .also { if (it != decoded) decoded.recycle() }
        } else decoded

        val maxSide = maxOf(oriented.width, oriented.height)
        val final = if (maxSide > MAX_DIM_PX) {
            val scale = MAX_DIM_PX.toFloat() / maxSide
            Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * scale).toInt().coerceAtLeast(1),
                (oriented.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it != oriented) oriented.recycle() }
        } else oriented

        val file = File(photosDir(), "${UUID.randomUUID()}.jpg")
        file.outputStream().use { final.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        final.recycle()
        "$DIR/${file.name}"
    }

    /** Удалить файл фото (при замене или удалении). null безопасен. */
    suspend fun delete(relativePath: String?) {
        if (relativePath == null) return
        withContext(Dispatchers.IO) {
            val f = fileFor(relativePath)
            // Защита от выхода за пределы каталога фотографий.
            if (f.canonicalPath.startsWith(photosDir().canonicalPath)) f.delete()
        }
    }

    /** Временный файл в кэше для снимка камерой (отдаётся FileProvider-ом). */
    fun createCameraTempFile(): File {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        return File(dir, "capture_${System.currentTimeMillis()}.jpg")
    }
}
