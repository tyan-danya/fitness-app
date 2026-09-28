package com.dtyan.fitdiary.ui.stats

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.theme.LightFitAccents
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId

/**
 * Рендер графиков в PNG (Robolectric NATIVE graphics) через чистые DrawScope-функции —
 * визуальная проверка без устройства. Файлы: app/build/chart-renders (PNG).
 * Плюс структурные проверки: столбики растут от нижней базовой линии, шкала «красивая».
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartRenderTest {

    private val outDir: File = File("build/chart-renders").apply { mkdirs() }
    private val density = Density(2.5f)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val textMeasurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)

    private val colors = ChartColors(
        accent = LightFitAccents.stats,
        gradient = LightFitAccents.statsGradient,
        grid = Color(0xFFCADACD),
        axis = Color(0xFF78857B),
        surface = Color(0xFFFAFDFA),
        muted = Color(0xFFEAF2EC),
    )
    private val styles = ChartTextStyles(
        axis = TextStyle(fontSize = 11.sp, color = Color(0xFF48544B)),
        value = TextStyle(fontSize = 12.sp, color = Color(0xFF17201A), fontWeight = FontWeight.SemiBold),
    )

    private fun render(name: String, widthPx: Int, heightPx: Int, block: DrawScope.() -> Unit): ImageBitmap {
        val image = ImageBitmap(widthPx, heightPx)
        val canvas = Canvas(image)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(widthPx.toFloat(), heightPx.toFloat())) {
            drawRect(Color(0xFFFAFDFA))
            block()
        }
        FileOutputStream(File(outDir, "$name.png")).use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return image
    }

    private fun day(offset: Long): Long =
        LocalDate.of(2026, 9, 18).minusDays(offset).atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val weights = listOf(
        day(30) to 84.6, day(27) to 84.4, day(24) to 84.5, day(20) to 84.1, day(17) to 83.9,
        day(13) to 83.7, day(10) to 83.8, day(6) to 83.3, day(3) to 83.1, day(0) to 82.9,
    )
    private val volumes = listOf(
        "27.07" to 0.0, "03.08" to 4200.0, "10.08" to 6100.0, "17.08" to 0.0,
        "24.08" to 7350.0, "31.08" to 5200.0, "07.09" to 8900.0, "14.09" to 3100.0,
    )

    @Test
    fun renderWeightLineChart() {
        render("weight-line", 900, 550) {
            drawLineChart(weights, colors, styles, textMeasurer, { Format.weight(it) }, minSpan = 2.0)
        }
    }

    @Test
    fun renderVolumeBarChart_barsGrowFromBottom() {
        val w = 900
        val h = 550
        val image = render("volume-bars", w, h) {
            drawBarChart(volumes, colors, styles, textMeasurer, { Format.volume(it) })
        }
        // Столбик самой тяжёлой недели («07.09», 7-й из 8) закрашен акцентом ближе к низу
        // и НЕ закрашен у самого верха → рост снизу вверх.
        val pixels = IntArray(w * h)
        image.readPixels(pixels, 0, 0, w, h)
        fun isAccent(x: Int, y: Int): Boolean {
            val p = pixels[y * w + x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            return b > 150 && r < 200 && g < 150 // фиолетовый градиент
        }
        val gutter = (w * 0.12f).toInt()
        val slot = (w - gutter - 4) / 8f
        val x = (gutter + slot * 6 + slot / 2).toInt()
        assertThat(isAccent(x, (h * 0.80f).toInt())).isTrue() // низ столбика закрашен
        assertThat(isAccent(x, (h * 0.02f).toInt())).isFalse() // над графиком — фон
        // Нулевая неделя («27.07», 1-й слот): в середине высоты — фон
        val x0 = (gutter + slot / 2).toInt()
        assertThat(isAccent(x0, h / 2)).isFalse()
    }

    @Test
    fun renderMeasurementChart_smallChanges() {
        val waist = listOf(day(21) to 92.0, day(14) to 91.5, day(7) to 91.5, day(0) to 90.8)
        render("waist-line", 900, 400) {
            drawLineChart(waist, colors.copy(accent = LightFitAccents.measure), styles, textMeasurer, { Format.weight(it) }, minSpan = 2.0)
        }
    }

    @Test
    fun niceAxis_examples() {
        val weightAxis = niceAxis(82.9, 84.6, targetTicks = 4, minSpan = 2.0)
        assertThat(weightAxis.min).isAtMost(82.9)
        assertThat(weightAxis.max).isAtLeast(84.6)
        assertThat(weightAxis.step).isEqualTo(0.5)

        val volumeAxis = niceAxis(0.0, 8900.0, targetTicks = 4, includeZero = true)
        assertThat(volumeAxis.min).isEqualTo(0.0)
        assertThat(volumeAxis.step).isEqualTo(2500.0)
        assertThat(volumeAxis.max).isEqualTo(10000.0)

        // Все значения равны — шкала не вырождается
        val flat = niceAxis(90.0, 90.0, minSpan = 2.0)
        assertThat(flat.max - flat.min).isAtLeast(2.0)

        // Нулевые данные столбиков
        val zero = niceAxis(0.0, 1.0, includeZero = true)
        assertThat(zero.ticks.size).isAtLeast(2)
    }

    @Test
    fun dateAxisLabels_firstAndLastAlwaysPresent() {
        val labels = dateAxisLabels(day(30), day(0), maxLabels = 4)
        assertThat(labels).hasSize(4)
        assertThat(labels.first().second).isEqualTo("19.08")
        assertThat(labels.last().second).isEqualTo("18.09")
        assertThat(dateAxisLabels(day(0), day(0), 4)).hasSize(1)
    }
}
