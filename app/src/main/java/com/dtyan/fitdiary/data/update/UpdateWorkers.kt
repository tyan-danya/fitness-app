package com.dtyan.fitdiary.data.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.io.IOException

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        AppUpdater(applicationContext).checkTask()
        Result.success()
    } catch (e: CancellationException) { throw e
    } catch (_: IOException) { if (runAttemptCount < 3) Result.retry() else Result.failure()
    } catch (_: Exception) { Result.failure() }
}

class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        AppUpdater(applicationContext).downloadTask(inputData.getBoolean(AppUpdater.ALLOW_METERED, false))
        Result.success()
    } catch (e: CancellationException) { throw e
    } catch (_: IOException) { if (runAttemptCount < 5) Result.retry() else Result.failure()
    } catch (_: Exception) { Result.failure() }
}
