package com.dtyan.fitdiary.data.update

import android.app.Application
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import com.dtyan.fitdiary.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppUpdaterTest {
    @get:Rule val mainRule = MainDispatcherRule()
    private lateinit var context: Context
    private lateinit var store: UpdateStore
    private lateinit var updater: AppUpdater
    private var release = testRelease()
    private var offline = false
    private var wifi = true
    private var identity = testArchiveIdentity
    private var afterInspection: () -> Unit = {}
    private val scheduled = mutableListOf<Boolean>()

    private fun hooks() = UpdateTestHooks(
        http = UpdateHttpClient { url ->
            if (offline) throw IOException("Offline")
            FakeUpdateConnection(if (url == UpdateManifest.MANIFEST_URL) release.toJson().toByteArray() else testPayload)
        },
        inspector = ApkInspector { afterInspection(); identity },
        installed = { testInstalled }, hasWifi = { wifi }, onDownload = { scheduled += it },
    )

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("fitdiary_updates", Context.MODE_PRIVATE).edit().clear().commit()
        store = UpdateStore(context)
        store.directory.listFiles()?.forEach { it.delete() }
        updater = AppUpdater.forTests(context, hooks())
    }

    @Test fun checkQueuesWifiDownloadThenVerifiesAndPersistsReady() = runTest {
        updater.checkTask()
        assertThat(updater.state.value.phase).isEqualTo(UpdatePhase.WAITING_FOR_WIFI)
        assertThat(scheduled).containsExactly(false)
        assertThat(AppUpdater.downloadConstraints(false).requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(AppUpdater.downloadConstraints(true).requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        updater.downloadTask(false)
        assertThat(updater.state.value.phase).isEqualTo(UpdatePhase.READY)
        assertThat(UpdateStore(context).wasVerified(release)).isTrue()
        assertThat(store.fileFor(release).readBytes()).isEqualTo(testPayload)
    }

    @Test fun restartReverifiesApkAndNeverPromptsForPermissionOrInstallation() = runTest {
        updater.checkTask(); updater.downloadTask(false)
        store.requestedPermission(true)
        val restarted = AppUpdater.forTests(context, hooks())
        assertThat(restarted.state.value.canInstall).isFalse()
        restarted.recoverReady()
        assertThat(restarted.state.value.canInstall).isTrue()
        assertThat(shadowOf(context as Application).nextStartedActivity).isNull()
        assertThat(store.permissionRequested).isTrue() // no automatic follow-up system prompt
    }

    @Test fun corruptPersistedApkIsRemovedAndCannotBeInstalled() = runTest {
        updater.checkTask(); updater.downloadTask(false)
        store.fileFor(release).writeBytes(ByteArray(testPayload.size))
        val restarted = AppUpdater.forTests(context, hooks())
        restarted.recoverReady()
        assertThat(restarted.state.value.canInstall).isFalse()
        assertThat(store.wasVerified(release)).isFalse()
        assertThat(store.fileFor(release).exists()).isFalse()
    }

    @Test fun processDeathAfterRenameBeforeReadyMarkerRecoversByFullValidation() = runTest {
        updater.checkTask(); updater.downloadTask(false)
        store.forgetReady()
        val restarted = AppUpdater.forTests(context, hooks())
        restarted.recoverReady()
        assertThat(restarted.state.value.phase).isEqualTo(UpdatePhase.READY)
        assertThat(store.wasVerified(release)).isTrue()
    }

    @Test fun cancellationAfterSignatureCheckCannotPublishInstallableFile() = runTest {
        updater.checkTask()
        afterInspection = { updater.cancelDownload() }
        assertThat(runCatching { updater.downloadTask(false) }.isFailure).isTrue()
        assertThat(updater.state.value.canInstall).isFalse()
        assertThat(store.fileFor(release).exists()).isFalse()
        assertThat(store.directory.listFiles().orEmpty().any { it.extension == "part" }).isFalse()
    }

    @Test fun mobileDownloadNeedsManualActionAndWrongSignerNeverBecomesReady() = runTest {
        updater.checkTask(); wifi = false
        assertThat(runCatching { updater.downloadTask(false) }.isFailure).isTrue()
        updater.downloadNow(true)
        assertThat(scheduled.last()).isTrue()
        identity = identity.copy(signers = setOf("wrong-key"))
        assertThat(runCatching { updater.downloadTask(true) }.isFailure).isTrue()
        assertThat(updater.state.value.canInstall).isFalse()
        assertThat(store.fileFor(release).exists()).isFalse()
    }

    @Test fun offlineCheckKeepsVerifiedDownloadAvailable() = runTest {
        updater.checkTask(); updater.downloadTask(false)
        offline = true
        assertThat(runCatching { updater.checkTask() }.isFailure).isTrue()
        assertThat(updater.state.value.canInstall).isTrue()
        assertThat(store.fileFor(release).exists()).isTrue()
    }

    @Test fun newerReleaseKeepsOldApkUntilReplacementIsVerifiedThenCleansCache() = runTest {
        updater.checkTask(); updater.downloadTask(false)
        val oldApk = store.fileFor(release)
        val unrelated = File(store.directory, "keep.txt").apply { writeText("keep") }
        release = release.copy(version = "1.8", versionCode = 9)
        updater.checkTask()
        assertThat(oldApk.exists()).isTrue()
        identity = identity.copy(versionCode = 9)
        updater.downloadTask(false)
        assertThat(oldApk.exists()).isFalse()
        assertThat(store.fileFor(release).exists()).isTrue()
        assertThat(unrelated.exists()).isTrue()
    }

    @Test fun interruptedDownloadHasRetryMessageAndNoPartialOrInstallAction() = runTest {
        updater.checkTask(); offline = true
        assertThat(runCatching { updater.downloadTask(false) }.isFailure).isTrue()
        assertThat(updater.state.value.phase).isEqualTo(UpdatePhase.WAITING_FOR_WIFI)
        assertThat(updater.state.value.canInstall).isFalse()
        assertThat(store.directory.listFiles().orEmpty().any { it.extension == "part" }).isFalse()
    }

    @Test fun explicitInstallWithoutPermissionOpensOnlyAppSpecificAndroidSettings() = runTest {
        updater.checkTask(); updater.downloadTask(false)
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            updater.install(activity)
            awaitInstallValidation()
            val request = shadowOf(activity).nextStartedActivity
            assertThat(request.action).isEqualTo(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            assertThat(request.data.toString()).isEqualTo("package:${context.packageName}")
            assertThat(store.permissionRequested).isTrue()
            assertThat(updater.state.value.phase).isEqualTo(UpdatePhase.NEEDS_PERMISSION)
            assertThat(shadowOf(activity).nextStartedActivity).isNull()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun explicitInstallUsesReadableContentUriAndDoubleTapDoesNotLaunchTwice() = runTest {
        // AndroidX FileProvider compares canonical paths with POSIX '/' separators. Robolectric
        // on a Windows JVM supplies '\\', so real FileProvider rejects a correctly configured root.
        // Keep the real provider integration enabled on Linux CI and Android; do not mock it away.
        assumeTrue("Real AndroidX FileProvider integration requires POSIX host paths; Windows Robolectric canonical paths use backslashes", File.separatorChar != '\\')
        updater.checkTask(); updater.downloadTask(false)
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            updater.install(activity)
            updater.install(activity)
            awaitInstallValidation()
            assertWithMessage(updater.state.value.message ?: "Installer validation")
                .that(updater.state.value.phase).isEqualTo(UpdatePhase.READY)
            val request = shadowOf(activity).nextStartedActivity
            assertThat(request.action).isEqualTo(Intent.ACTION_VIEW)
            assertThat(request.type).isEqualTo("application/vnd.android.package-archive")
            assertThat(request.data!!.scheme).isEqualTo("content")
            assertThat(request.data!!.authority).isEqualTo("${context.packageName}.fileprovider")
            assertThat(request.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
            assertThat(request.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
            assertThat(request.clipData!!.getItemAt(0).uri).isEqualTo(request.data)
            val bytes = context.contentResolver.openInputStream(request.data!!)!!.use { it.readBytes() }
            assertThat(bytes).isEqualTo(testPayload)
            assertThat(shadowOf(activity).nextStartedActivity).isNull()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun fileCorruptedAfterReadyCannotOpenInstallerOrSettings() = runTest {
        updater.checkTask(); updater.downloadTask(false)
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        store.fileFor(release).writeBytes(ByteArray(testPayload.size))
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            updater.install(activity)
            awaitInstallValidation()
            assertThat(updater.state.value.phase).isEqualTo(UpdatePhase.ERROR)
            assertThat(updater.state.value.canInstall).isFalse()
            assertThat(shadowOf(activity).nextStartedActivity).isNull()
        } finally { controller.pause().stop().destroy() }
    }

    // APK validation uses the real IO dispatcher. Await its completion with a real timeout,
    // while runTest continues dispatching the coordinator's Main continuation.
    private suspend fun awaitInstallValidation() = withContext(Dispatchers.Default) {
        withTimeout(5_000) { updater.state.first { !it.busy } }
    }

    @Test fun oldVersionDoesNotScheduleDownloadAndNewManifestCannotReuseVersionWithAnotherHash() = runTest {
        release = release.copy(versionCode = 7)
        updater.checkTask()
        assertThat(updater.state.value.phase).isEqualTo(UpdatePhase.UP_TO_DATE)
        assertThat(scheduled).isEmpty()
        release = testRelease()
        updater.checkTask()
        release = release.copy(sha256 = "0".repeat(64))
        assertThat(runCatching { updater.checkTask() }.isFailure).isTrue()
        assertThat(store.release()!!.sha256).isEqualTo(testRelease().sha256)
    }
}
