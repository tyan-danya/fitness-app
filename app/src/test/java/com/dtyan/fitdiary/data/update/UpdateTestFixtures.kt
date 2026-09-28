package com.dtyan.fitdiary.data.update

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

internal val testPayload = ByteArray(1024) { (it % 127).toByte() }
internal val testInstalled = ApkIdentity(UpdateManifest.PACKAGE_NAME, 7, setOf("certificate-A"))
internal val testArchiveIdentity = ApkIdentity(UpdateManifest.PACKAGE_NAME, 8, setOf("certificate-A"))
internal fun testRelease() = UpdateManifest(
    projectId = UpdateManifest.PROJECT_ID,
    version = "1.7", versionCode = 8,
    packageName = UpdateManifest.PACKAGE_NAME,
    downloadUrl = "${UpdateManifest.BASE_URL}/releases/12345678-1234-1234-1234-123456789abc.apk",
    latestDownloadUrl = "${UpdateManifest.BASE_URL}/latest.apk",
    sha256 = sha256(testPayload), sizeBytes = testPayload.size.toLong(), notes = "Test release",
)

internal class FakeUpdateConnection(private val bytes: ByteArray, private val code: Int = 200,
    private val advertisedLength: Long = bytes.size.toLong()) : HttpURLConnection(URL(UpdateManifest.MANIFEST_URL)) {
    var bodyOpened = false
    var disconnected = false
    override fun connect() = Unit
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun getResponseCode() = code
    override fun getContentLengthLong() = advertisedLength
    override fun getInputStream() = ByteArrayInputStream(bytes).also { bodyOpened = true }
}
