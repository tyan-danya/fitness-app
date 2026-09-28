package com.dtyan.fitdiary.data.update

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.dtyan.fitdiary.BuildConfig
import com.dtyan.fitdiary.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class UpdatePhase { DISABLED, IDLE, CHECKING, UP_TO_DATE, AVAILABLE, WAITING_FOR_WIFI, DOWNLOADING, VERIFYING, READY, NEEDS_PERMISSION, ERROR }

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: UpdateManifest? = null,
    val progress: Int = 0,
    val message: String? = null,
    val lastCheckAt: Long = 0,
) {
    val busy: Boolean get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
    val canInstall: Boolean get() = phase == UpdatePhase.READY || phase == UpdatePhase.NEEDS_PERMISSION
}

/** Application-scoped coordinator shared by the UI and WorkManager workers. */
class AppUpdater private constructor(private val context: Context, @Suppress("UNUSED_PARAMETER") singleton: Unit, private val hooks: UpdateTestHooks? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = UpdateStore(context)
    private val operation = Mutex()
    private val http = hooks?.http ?: UpdateHttpClient()
    private val inspector = AndroidApkInspector(context)
    private val verifier = ApkVerifier(hooks?.inspector ?: inspector)
    val enabled = hooks != null || (!BuildConfig.DEBUG && context.packageName == UpdateManifest.PACKAGE_NAME)
    private val _state = MutableStateFlow(if (enabled) UpdateState(release = store.release(), lastCheckAt = store.lastCheckAt)
        else UpdateState(UpdatePhase.DISABLED, message = "Это отладочная сборка. Обновления обычной версии здесь не скачиваются и не устанавливаются."))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    companion object {
        private const val CHECK_WORK = "fitdiary-update-check"
        private const val PERIODIC_WORK = "fitdiary-update-periodic"
        private const val DOWNLOAD_WORK = "fitdiary-update-download"
        internal const val ALLOW_METERED = "allow_metered"
        const val EXTRA_OPEN_UPDATES = "open_updates"
        @Suppress("StaticFieldLeak") // invoke() stores only applicationContext, never an Activity.
        @Volatile private var instance: AppUpdater? = null
        operator fun invoke(context: Context): AppUpdater = instance ?: synchronized(this) {
            instance ?: AppUpdater(context.applicationContext, Unit).also { instance = it }
        }
        internal fun forTests(context: Context, hooks: UpdateTestHooks): AppUpdater = AppUpdater(context, Unit, hooks)

        internal fun downloadConstraints(allowMetered: Boolean): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(!allowMetered).build()
    }

    init { if (enabled && hooks == null) scope.launch { recoverReady() } }

    /** Daily background work is approximate; Android may defer it for battery/network. */
    fun schedule() {
        if (!enabled) return
        val manager = WorkManager.getInstance(context)
        val periodic = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic)
        if (System.currentTimeMillis() - store.lastCheckAt >= TimeUnit.HOURS.toMillis(6)) checkNow()
    }

    fun checkNow() {
        if (!enabled) return
        if (hooks != null) { hooks.onCheck(); return }
        _state.value = _state.value.copy(message = "Проверим обновления при подключении к сети")
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniqueWork(CHECK_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** A manual tap explicitly authorizes mobile data; automatic requests always pass false. */
    fun downloadNow(allowMetered: Boolean = true) {
        if (!enabled) return
        val release = store.release() ?: run { checkNow(); return }
        if (release.versionCode <= installed().versionCode) return
        if (allowMetered) store.cancel(0)
        enqueueDownload(allowMetered)
    }

    private fun enqueueDownload(allowMetered: Boolean) {
        _state.value = _state.value.copy(phase = UpdatePhase.WAITING_FOR_WIFI,
            message = if (allowMetered) "Загрузка начнётся при подключении к сети" else "Скачаем автоматически по Wi-Fi или проводной безлимитной сети")
        if (hooks != null) { hooks.onDownload(allowMetered); return }
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(workDataOf(ALLOW_METERED to allowMetered))
            .setConstraints(downloadConstraints(allowMetered))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniqueWork(DOWNLOAD_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelDownload() {
        val release = store.release() ?: return
        store.cancel(release.versionCode)
        if (hooks == null) WorkManager.getInstance(context).cancelUniqueWork(DOWNLOAD_WORK)
        _state.value = UpdateState(UpdatePhase.AVAILABLE, release, message = "Загрузка отменена. Можно скачать позже.", lastCheckAt = store.lastCheckAt)
    }

    fun onResume() { if (enabled) scope.launch {
        recoverReady()
        if (System.currentTimeMillis() - store.lastCheckAt >= TimeUnit.HOURS.toMillis(6)) checkNow()
    } }

    internal suspend fun recoverReady() = withContext(Dispatchers.IO) {
        if (operation.isLocked) return@withContext
        operation.withLock {
            val release = store.release() ?: return@withLock
            if (release.versionCode <= installed().versionCode) {
                store.clear()
                store.purgeDownloads()
                _state.value = UpdateState(UpdatePhase.UP_TO_DATE, message = "Установлена актуальная версия")
            } else if (store.wasVerified(release) || store.fileFor(release).isFile) {
                _state.value = UpdateState(UpdatePhase.VERIFYING, release, message = "Проверяем сохранённый APK")
                try {
                    verifier.verify(store.fileFor(release), release, installed())
                    store.ready(release)
                    store.purgeDownloads(keeping = store.fileFor(release))
                    publishReady(release)
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) {
                    store.forgetReady(); store.fileFor(release).delete()
                    _state.value = UpdateState(UpdatePhase.AVAILABLE, release, message = "Сохранённый APK не прошёл проверку. Скачайте его повторно.")
                }
            } else if (_state.value.phase !in setOf(UpdatePhase.WAITING_FOR_WIFI, UpdatePhase.DOWNLOADING)) {
                _state.value = UpdateState(UpdatePhase.AVAILABLE, release, lastCheckAt = store.lastCheckAt)
            }
        }
    }

    internal suspend fun checkTask() = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        operation.withLock {
            _state.value = _state.value.copy(phase = UpdatePhase.CHECKING, message = null)
            try {
                val fetched = http.manifest()
                val previous = store.release()
                require(previous == null || previous.versionCode != fetched.versionCode || previous.sha256 == fetched.sha256) { "Опубликованный APK этой версии изменился. Проверка остановлена." }
                val release = previous?.takeIf { it.versionCode > fetched.versionCode } ?: fetched
                if (release.versionCode <= installed().versionCode) {
                    store.clear()
                    store.purgeDownloads()
                    _state.value = UpdateState(UpdatePhase.UP_TO_DATE, message = "Установлена актуальная версия", lastCheckAt = store.lastCheckAt)
                    return@withLock
                }
                store.checked(release)
                if (store.wasVerified(release) || store.fileFor(release).isFile) {
                    try { verifier.verify(store.fileFor(release), release, installed()); store.ready(release); store.purgeDownloads(keeping = store.fileFor(release)); publishReady(release); return@withLock }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { store.forgetReady(); store.fileFor(release).delete() }
                }
                _state.value = UpdateState(UpdatePhase.AVAILABLE, release, lastCheckAt = store.lastCheckAt)
                if (store.cancelledVersion != release.versionCode) enqueueDownload(false)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                val cached = store.release()
                val usable = if (cached != null && store.wasVerified(cached)) {
                    try { verifier.verify(store.fileFor(cached), cached, installed()); true }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                } else false
                if (usable && cached != null) {
                    publishReady(cached)
                    _state.value = _state.value.copy(message = "Новые версии проверить не удалось. Уже скачанный APK проверен и доступен для установки.")
                } else failure(e)
                throw e
            }
        }
    }

    internal suspend fun downloadTask(allowMetered: Boolean) = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        operation.withLock {
            val release = store.release() ?: return@withLock
            if (release.versionCode <= installed().versionCode || (!allowMetered && store.cancelledVersion == release.versionCode)) return@withLock
            if (!allowMetered && !wifiAvailable()) throw java.io.IOException("Ожидание Wi-Fi")
            val partial = File(store.directory, "${UUID.randomUUID()}.part")
            try {
                store.directory.listFiles()?.filter { it.extension == "part" }?.forEach { it.delete() }
                require(store.directory.usableSpace >= release.sizeBytes + 10 * 1024 * 1024) { "Недостаточно свободного места для APK" }
                _state.value = UpdateState(UpdatePhase.DOWNLOADING, release)
                http.download(release, partial, networkAllowed = { allowMetered || wifiAvailable() }) { percent ->
                    _state.value = _state.value.copy(progress = percent)
                }
                _state.value = _state.value.copy(phase = UpdatePhase.VERIFYING, message = "Проверяем размер, SHA-256 и подпись APK")
                verifier.verify(partial, release, installed())
                currentCoroutineContext().ensureActive()
                if (store.cancelledVersion == release.versionCode) throw CancellationException("Загрузка отменена пользователем")
                val target = store.fileFor(release)
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                store.ready(release)
                store.purgeDownloads(keeping = target)
                publishReady(release, notify = true)
            } catch (e: CancellationException) {
                if (store.cancelledVersion != release.versionCode) _state.value = UpdateState(UpdatePhase.WAITING_FOR_WIFI, release, message = "Загрузка приостановлена; продолжим при доступной сети")
                throw e
            } catch (e: java.io.IOException) {
                _state.value = UpdateState(UpdatePhase.WAITING_FOR_WIFI, release,
                    message = if (allowMetered) "Загрузка прервалась. Повторим при доступной сети; можно запустить её вручную."
                    else "Загрузка прервалась. Повторим по Wi-Fi; можно скачать вручную через любую сеть.", lastCheckAt = store.lastCheckAt)
                throw e
            } catch (e: Exception) { failure(e); throw e
            } finally { partial.delete() }
        }
    }

    /** Never called by workers or onResume: only a visible user action opens system UI. */
    fun install(activity: Activity) {
        if (!enabled || !_state.value.canInstall || activity.isFinishing || activity.isDestroyed) return
        val release = store.release() ?: return
        _state.value = UpdateState(UpdatePhase.VERIFYING, release)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { operation.withLock {
                    require(store.release()?.sha256 == release.sha256) { "Доступна более новая версия. Проверьте обновление повторно." }
                    verifier.verify(store.fileFor(release), release, installed())
                } }
                if (activity.isFinishing || activity.isDestroyed) return@launch
                if (!context.packageManager.canRequestPackageInstalls()) {
                    store.requestedPermission(true)
                    _state.value = UpdateState(UpdatePhase.NEEDS_PERMISSION, release, message = "Разрешите установку из ФитДневника в Android. После возврата нажмите «Установить» ещё раз.")
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } else {
                    store.requestedPermission(false)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", store.fileFor(release))
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        clipData = ClipData.newRawUri("APK обновления", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    publishReady(release)
                    activity.startActivity(intent)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { failure(e) }
        }
    }

    private fun publishReady(release: UpdateManifest, notify: Boolean = false) {
        val needsPermission = store.permissionRequested && !context.packageManager.canRequestPackageInstalls()
        _state.value = UpdateState(if (needsPermission) UpdatePhase.NEEDS_PERMISSION else UpdatePhase.READY, release, 100,
            if (needsPermission) "Android пока не разрешил установку. Можно разрешить её или сделать это позже."
            else "APK проверен. Установка начнётся только после вашего подтверждения в Android.", store.lastCheckAt)
        if (notify && hooks == null) runCatching { notifyReady(release) }
    }

    private fun notifyReady(release: UpdateManifest) {
        if (store.notifiedVersion == release.versionCode) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel("app_updates", "Обновления приложения", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_UPDATES, true)
        val pending = PendingIntent.getActivity(context, 4107, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "app_updates")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("ФитДневник ${release.version} готов к установке")
            .setContentText("APK проверен. Нажмите, чтобы открыть обновление.")
            .setContentIntent(pending).setAutoCancel(true).build()
        manager.notify(4107, notification)
        store.notified(release.versionCode)
    }

    private fun installed(): ApkIdentity = hooks?.installed?.invoke() ?: inspector.installed()
    private fun failure(error: Exception) {
        _state.value = UpdateState(UpdatePhase.ERROR, store.release(), message = error.message?.take(240) ?: "Не удалось проверить обновление", lastCheckAt = store.lastCheckAt)
    }
    private fun wifiAvailable(): Boolean {
        hooks?.let { return it.hasWifi() }
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
}

/** Internal test seam: production always uses Android identity, connectivity and WorkManager. */
internal data class UpdateTestHooks(
    val http: UpdateHttpClient,
    val inspector: ApkInspector,
    val installed: () -> ApkIdentity,
    val hasWifi: () -> Boolean = { true },
    val onDownload: (Boolean) -> Unit = {},
    val onCheck: () -> Unit = {},
)
