package com.dtyan.fitdiary.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.abs

/**
 * Тесты PhotoStore на реальной графике Robolectric (native BitmapFactory):
 * даунскейл до 1280 с сохранением пропорций, отсутствие апскейла,
 * безопасное удаление (null, попытки выйти из каталога) и временный файл камеры.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoStoreTest {

    private lateinit var context: Context
    private lateinit var store: PhotoStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = PhotoStore(context)
    }

    /** Пишет одноцветный JPEG width×height во временный файл в cacheDir и отдаёт его Uri. */
    private fun jpegUri(width: Int, height: Int): Uri {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.rgb(180, 60, 60))
        val file = File(context.cacheDir, "src_${width}x$height.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return Uri.fromFile(file)
    }

    /** Размеры сохранённого файла без декодирования пикселей. */
    private fun decodedSize(relativePath: String): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(store.fileFor(relativePath).absolutePath, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    // ---------- saveFromUri ----------

    @Test
    fun saveFromUri_bigImage_downscalesTo1280_keepsAspectRatio() = runTest {
        val rel = store.saveFromUri(jpegUri(3000, 1500))

        assertThat(rel).startsWith("exercise_photos/")
        assertThat(rel).endsWith(".jpg")
        assertThat(store.fileFor(rel).exists()).isTrue()

        val (w, h) = decodedSize(rel)
        assertThat(maxOf(w, h)).isAtMost(1280)
        // 3000×1500 → длинная сторона 1280, пропорции ~2:1 (±2 px).
        assertThat(w).isEqualTo(1280)
        assertThat(abs(h - 640)).isAtMost(2)
    }

    @Test
    fun saveFromUri_smallImage_isNotUpscaled() = runTest {
        val rel = store.saveFromUri(jpegUri(500, 400))

        assertThat(rel).startsWith("exercise_photos/")
        val (w, h) = decodedSize(rel)
        assertThat(w).isEqualTo(500)
        assertThat(h).isEqualTo(400)
    }

    // ---------- delete ----------

    @Test
    fun delete_removesSavedFile() = runTest {
        val rel = store.saveFromUri(jpegUri(500, 400))
        val file = store.fileFor(rel)
        assertThat(file.exists()).isTrue()

        store.delete(rel)

        assertThat(file.exists()).isFalse()
    }

    @Test
    fun delete_null_isNoOp() = runTest {
        store.delete(null) // не должен падать
    }

    @Test
    fun delete_pathTraversal_doesNotDeleteOutsidePhotosDir() = runTest {
        // Файлы вне exercise_photos: в filesDir и на уровень выше (куда целит «../evil.txt»).
        val inFilesDir = File(context.filesDir, "evil.txt").apply { writeText("живой") }
        val aboveFilesDir = File(context.filesDir.parentFile!!, "evil.txt").apply { writeText("живой") }

        store.delete("../evil.txt")
        store.delete("evil.txt")
        store.delete("exercise_photos/../evil.txt")

        assertThat(inFilesDir.exists()).isTrue()
        assertThat(aboveFilesDir.exists()).isTrue()
    }

    // ---------- createCameraTempFile ----------

    @Test
    fun createCameraTempFile_inCacheCameraDir_withJpgName() {
        val file = store.createCameraTempFile()

        assertThat(file.parentFile).isEqualTo(File(context.cacheDir, "camera"))
        assertThat(file.parentFile!!.isDirectory).isTrue()
        assertThat(file.name).startsWith("capture_")
        assertThat(file.name).endsWith(".jpg")
    }
}
