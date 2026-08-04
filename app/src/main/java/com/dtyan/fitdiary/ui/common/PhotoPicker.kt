package com.dtyan.fitdiary.ui.common

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.dtyan.fitdiary.appContainer

/** Ручки запуска выбора фото. Результат приходит в onPicked как Uri. */
class PhotoPickerHandle(
    val launchGallery: () -> Unit,
    val launchCamera: () -> Unit,
)

private fun cameraUriFor(context: Context): Uri {
    val file = context.appContainer.photoStore.createCameraTempFile()
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * Выбор фото тренажёра: системный Photo Picker (без разрешений)
 * или снимок камерой во временный файл через FileProvider.
 */
@Composable
fun rememberPhotoPicker(onPicked: (Uri) -> Unit): PhotoPickerHandle {
    val context = LocalContext.current
    val currentOnPicked by rememberUpdatedState(onPicked)
    val pendingCameraUri = remember { mutableStateOf<Uri?>(null) }

    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { currentOnPicked(it) } }

    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri.value
        if (success && uri != null) currentOnPicked(uri)
        pendingCameraUri.value = null
    }

    return PhotoPickerHandle(
        launchGallery = {
            gallery.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        launchCamera = {
            val uri = cameraUriFor(context)
            pendingCameraUri.value = uri
            camera.launch(uri)
        },
    )
}
