package com.dtyan.fitdiary.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

fun interface ApkInspector { fun inspect(file: File): ApkIdentity? }

class AndroidApkInspector(private val context: Context) : ApkInspector {
    @Suppress("DEPRECATION")
    override fun inspect(file: File): ApkIdentity? = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags())?.identity()

    @Suppress("DEPRECATION")
    fun installed(): ApkIdentity = context.packageManager.getPackageInfo(context.packageName, signatureFlags()).identity()

    @Suppress("DEPRECATION") // Android 8.x requires the legacy flag; Android 9+ uses signingInfo.
    private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun PackageInfo.identity(): ApkIdentity {
        val certificates = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
        return ApkIdentity(packageName, if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong(),
            certificates.orEmpty().map { sha256(it.toByteArray()) }.toSet())
    }
}

class ApkVerifier(private val inspector: ApkInspector) {
    /** Repeated before installation and on restart; a persisted ready flag is never trusted. */
    suspend fun verify(file: File, release: UpdateManifest, installed: ApkIdentity) {
        require(file.isFile && file.length() == release.sizeBytes) { "Размер APK не совпадает" }
        require(fileSha256(file) == release.sha256) { "Контрольная сумма APK не совпадает" }
        val apk = inspector.inspect(file) ?: throw IllegalArgumentException("APK не распознан Android")
        require(apk.packageName == installed.packageName && apk.packageName == release.packageName) { "APK другого приложения" }
        require(apk.versionCode == release.versionCode && apk.versionCode > installed.versionCode) { "Версия APK не соответствует обновлению" }
        require(installed.signers.isNotEmpty() && apk.signers == installed.signers) { "Подпись APK не соответствует установленному приложению" }
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal suspend fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
