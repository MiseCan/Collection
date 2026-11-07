package com.collection.sync

import android.app.Application
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val config = SyncSettings(applicationContext).read()
        if (!config.configured || (!inputData.getBoolean("manual", false) && !config.automatic)) return Result.success()
        return try { SyncCoordinator.synchronize(applicationContext); Result.success() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if ((e is SyncFailure && e.retryable) || e is java.net.SocketTimeoutException || e is java.net.ConnectException)
                if (runAttemptCount < 5) Result.retry() else Result.failure()
            else Result.failure()
        }
    }
}

internal object SyncScheduler {
    private const val PERIODIC = "collection-sync-periodic"
    private const val ONE_TIME = "collection-sync-pending"
    fun configure(context: Context, config: SyncConfig = SyncSettings(context).read()) {
        if (!config.configured) return
        val manager = WorkManager.getInstance(context)
        if (!config.automatic) {
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(ONE_TIME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setInitialDelay(30, TimeUnit.MINUTES)
            .setConstraints(constraints(config.wifiOnly)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun request(context: Context, manual: Boolean = false, config: SyncConfig = SyncSettings(context).read()) {
        if (!config.configured || (!manual && !config.automatic)) return
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf("manual" to manual)).setConstraints(constraints(!manual && config.wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work)
    }
    internal fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true).build()
}

class CollectionApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        scope.launch {
            // Configuration without an account never starts network work.
            SyncScheduler.configure(this@CollectionApplication)
            SyncScheduler.request(this@CollectionApplication)
            SyncSignals.local.drop(1).debounce(10_000).collect { SyncScheduler.request(this@CollectionApplication) }
        }
    }
}
