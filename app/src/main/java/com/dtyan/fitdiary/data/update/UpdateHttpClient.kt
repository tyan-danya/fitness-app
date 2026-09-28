package com.dtyan.fitdiary.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

internal class UpdateHttpClient(private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }) {
    suspend fun manifest(): UpdateManifest = withContext(Dispatchers.IO) {
        val connection = connection(UpdateManifest.MANIFEST_URL)
        try {
            requireOk(connection)
            val output = ByteArrayOutputStream()
            val started = System.nanoTime()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(45)) { "Сервер обновлений отвечает слишком долго" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= UpdateManifest.MAX_MANIFEST_BYTES) { "Слишком большой манифест" }
                    output.write(buffer, 0, count)
                }
            }
            UpdateManifest.parse(output.toString("UTF-8"))
        } finally { connection.disconnect() }
    }

    suspend fun download(release: UpdateManifest, partial: File, networkAllowed: () -> Boolean, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        require(UpdateManifest.isPinnedArtifact(release.downloadUrl)) { "Недопустимый адрес APK" }
        val connection = connection(release.downloadUrl)
        try {
            requireOk(connection)
            val length = connection.contentLengthLong
            require(length == -1L || length == release.sizeBytes) { "Размер ответа не соответствует APK" }
            var received = 0L
            var percent = -1
            val started = System.nanoTime()
            connection.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!networkAllowed()) throw IOException("Wi-Fi отключён; продолжим позже")
                    require(System.nanoTime() - started < TimeUnit.MINUTES.toNanos(8)) { "Загрузка превысила время ожидания" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    require(received <= release.sizeBytes && received <= UpdateManifest.MAX_APK_BYTES) { "APK больше заявленного размера" }
                    output.write(buffer, 0, count)
                    val next = (received * 100 / release.sizeBytes).toInt()
                    if (next != percent) { percent = next; onProgress(next) }
                }
                output.fd.sync()
            } }
            require(received == release.sizeBytes) { "APK загружен не полностью" }
        } finally { connection.disconnect() }
    }

    private fun connection(url: String): HttpURLConnection = openConnection(url).apply {
        requestMethod = "GET"
        instanceFollowRedirects = false // Never move an update request to an untrusted origin/artifact.
        connectTimeout = 15_000
        readTimeout = 20_000
        useCaches = false
        setRequestProperty("Accept-Encoding", "identity")
    }

    private fun requireOk(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code != 200) throw IOException("Сервер обновлений: HTTP $code")
    }
}
