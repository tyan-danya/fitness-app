package com.dtyan.fitdiary.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Загрузка Bitmap из файла с даунскейлом под целевой размер (px). */
private suspend fun loadBitmap(file: File, targetPx: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (targetPx > 0 && maxSide / (sample * 2) >= targetPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
    }

/**
 * Квадратная миниатюра фото тренажёра. Если фото нет/не читается — [fallback]
 * (обычно эмодзи группы мышц на цветной подложке).
 */
@Composable
fun ExercisePhotoThumb(
    photoPath: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    fallback: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val targetPx = with(LocalDensity.current) { (size * 2).roundToPx() }
    var bitmap by remember(photoPath, targetPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photoPath, targetPx) {
        // Changing the path/size cancels the old IO result before it can update this state.
        bitmap = photoPath?.let { loadBitmap(File(context.filesDir, it), targetPx) }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = "Фото тренажёра",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            fallback()
        }
    }
}

/** Полноэкранный просмотр фото тренажёра (тап мимо — закрыть). */
@Composable
fun PhotoViewerDialog(photoPath: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(photoPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photoPath) {
        bitmap = loadBitmap(File(context.filesDir, photoPath), 0)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            val b = bitmap
            if (b != null) {
                Image(
                    bitmap = b,
                    contentDescription = "Фото тренажёра",
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { clip = true },
                    contentScale = ContentScale.FillWidth,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .size(240.dp),
                )
            }
        }
    }
}
