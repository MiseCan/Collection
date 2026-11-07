package com.collection.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.TimeUnit

class TrashCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        if (LibraryStore(applicationContext).purgeExpiredTrash() > 0) changes.tryEmit(Unit)
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        internal val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        internal fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("collection-trash-cleanup", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
