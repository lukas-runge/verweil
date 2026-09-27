package de.lukasrunge.verweil.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.core.dawarich.DawarichClient
import de.lukasrunge.verweil.core.platformHttpClient
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Sends the outbox to Dawarich whenever there is network; retries with backoff on failure. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as VerweilApp
        val settings = app.settings.current()
        if (!settings.isConfigured) return Result.success()

        val http = platformHttpClient()
        return try {
            app.outbox.flush(DawarichClient(settings.serverUrl, settings.apiKey, settings.deviceId, http, settings.customHeaders))
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Upload failed, will retry", e)
            Result.retry()
        } finally {
            http.close()
        }
    }

    companion object {
        private const val TAG = "UploadWorker"

        /** Batches uploads: new data within the delay rides along with the already scheduled run. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInitialDelay(2, TimeUnit.MINUTES)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("upload", ExistingWorkPolicy.KEEP, request)
        }
    }
}
