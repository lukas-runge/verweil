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
import de.lukasrunge.verweil.UploadStatus
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.core.dawarich.DawarichClient
import de.lukasrunge.verweil.core.dawarich.DawarichException
import de.lukasrunge.verweil.core.platformHttpClient
import kotlinx.coroutines.flow.update
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
            val sent = app.outbox.flush(
                DawarichClient(settings.serverUrl, settings.apiKey, settings.deviceId, http, settings.customHeaders),
            )
            app.uploadStatus.update {
                UploadStatus(lastSuccessMs = if (sent > 0) System.currentTimeMillis() else it.lastSuccessMs)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is DawarichException && e.isAuthError) {
                // Retrying cannot help until the user signs in again; everything stays queued until then.
                app.uploadStatus.update { it.copy(error = "Dawarich refused the API key. Sign out and sign in again.") }
                return Result.failure()
            }
            Log.w(TAG, "Upload failed, will retry", e)
            app.uploadStatus.update { it.copy(error = e.message ?: e.toString()) }
            Result.retry()
        } finally {
            http.close()
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val WORK_NAME = "upload"

        /** Batches uploads: new data within the delay rides along with the already scheduled run. */
        fun enqueue(context: Context) = enqueue(context, delayMinutes = 2, ExistingWorkPolicy.KEEP)

        /** Uploads right away, e.g. after signing in again or retrying refused data. */
        fun uploadNow(context: Context) = enqueue(context, delayMinutes = 0, ExistingWorkPolicy.REPLACE)

        private fun enqueue(context: Context, delayMinutes: Long, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }
}
