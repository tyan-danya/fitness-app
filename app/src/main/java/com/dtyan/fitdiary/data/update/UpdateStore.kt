package com.dtyan.fitdiary.data.update

import android.content.Context
import java.io.File

internal class UpdateStore(context: Context) {
    private val prefs = context.getSharedPreferences("fitdiary_updates", Context.MODE_PRIVATE)
    val directory = File(context.filesDir, "app_updates").apply { mkdirs() }
    val lastCheckAt: Long get() = prefs.getLong("lastCheckAt", 0)
    val cancelledVersion: Long get() = prefs.getLong("cancelledVersion", 0)
    val permissionRequested: Boolean get() = prefs.getBoolean("permissionRequested", false)
    val notifiedVersion: Long get() = prefs.getLong("notifiedVersion", 0)

    fun release(): UpdateManifest? = prefs.getString("release", null)?.let { runCatching { UpdateManifest.parse(it) }.getOrNull() }
    fun fileFor(release: UpdateManifest): File = File(directory, "release-${release.versionCode}-${release.sha256.take(16)}.apk")
    fun wasVerified(release: UpdateManifest): Boolean = prefs.getLong("readyCode", 0) == release.versionCode && prefs.getString("readyHash", null) == release.sha256
    fun checked(release: UpdateManifest) { check(prefs.edit().putString("release", release.toJson()).putLong("lastCheckAt", System.currentTimeMillis()).commit()) }
    fun ready(release: UpdateManifest) { check(prefs.edit().putLong("readyCode", release.versionCode).putString("readyHash", release.sha256).commit()) }
    fun forgetReady() { check(prefs.edit().remove("readyCode").remove("readyHash").commit()) }
    fun cancel(version: Long) { check(prefs.edit().putLong("cancelledVersion", version).commit()) }
    fun requestedPermission(value: Boolean) { check(prefs.edit().putBoolean("permissionRequested", value).commit()) }
    fun notified(version: Long) { check(prefs.edit().putLong("notifiedVersion", version).commit()) }
    fun clear() { check(prefs.edit().clear().putLong("lastCheckAt", System.currentTimeMillis()).commit()) }

    /** Only our direct cache children may be removed; never follow links outside this directory. */
    fun purgeDownloads(keeping: File? = null) {
        val parent = directory.canonicalFile
        directory.listFiles()?.filter { file ->
            file.isFile && file.canonicalFile.parentFile == parent && file != keeping &&
                (file.name.matches(Regex("release-[0-9]+-[a-f0-9]{16}\\.apk")) || file.extension == "part")
        }?.forEach { it.delete() }
    }
}
