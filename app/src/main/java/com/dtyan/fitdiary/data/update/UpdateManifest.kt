package com.dtyan.fitdiary.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

@Serializable
data class UpdateManifest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("project_id") val projectId: String,
    val version: String,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("package_name") val packageName: String,
    @SerialName("download_url") val downloadUrl: String,
    @SerialName("latest_download_url") val latestDownloadUrl: String,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val notes: String = "",
) {
    fun toJson(): String = parser.encodeToString(this)

    companion object {
        const val PROJECT_ID = "205c5b37-6bd5-4ea2-9f02-8f86f39935f9"
        const val PACKAGE_NAME = "com.dtyan.fitdiary"
        const val HOST = "xn--12-6kcif3a1ap9a.xn--p1ai"
        const val BASE_URL = "https://$HOST/apps/$PROJECT_ID"
        const val MANIFEST_URL = "$BASE_URL/latest.json"
        const val MAX_APK_BYTES = 150L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 128 * 1024
        private val parser = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val artifactPath = Regex("/apps/$PROJECT_ID/releases/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.apk")

        fun parse(text: String): UpdateManifest {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "Слишком большой манифест обновления" }
            val manifest = parser.decodeFromString<UpdateManifest>(text)
            require(manifest.schemaVersion == 1) { "Неизвестный формат обновления" }
            require(manifest.projectId == PROJECT_ID && manifest.packageName == PACKAGE_NAME) { "Обновление другого приложения" }
            require(manifest.versionCode in 1..Int.MAX_VALUE.toLong()) { "Некорректный номер версии" }
            require(manifest.version.isNotBlank() && manifest.version.length <= 80 && manifest.version.none(Char::isISOControl)) { "Некорректная версия" }
            require(manifest.notes.length <= 20_000) { "Слишком длинное описание версии" }
            require(manifest.sizeBytes in 1..MAX_APK_BYTES) { "Недопустимый размер APK" }
            require(Regex("[0-9a-fA-F]{64}").matches(manifest.sha256)) { "Некорректная контрольная сумма" }
            require(isPinnedArtifact(manifest.downloadUrl)) { "Недопустимый адрес APK" }
            require(manifest.latestDownloadUrl == "$BASE_URL/latest.apk") { "Недопустимый адрес последней версии" }
            return manifest.copy(sha256 = manifest.sha256.lowercase())
        }

        fun isPinnedArtifact(url: String): Boolean = runCatching {
            val uri = URI(url)
            url.length <= 2048 && uri.scheme == "https" && uri.host == HOST && uri.port == -1 &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath == uri.path && artifactPath.matches(uri.path)
        }.getOrDefault(false)
    }
}
