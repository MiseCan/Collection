package com.collection.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncWorkerTest {
    private lateinit var context: Context
    private lateinit var manager: WorkManager
    private val configured = SyncConfig(username = "test@example.com", password = "not-a-real-account", automatic = true)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("webdav_sync", 0).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        manager = WorkManager.getInstance(context)
    }
    @Test fun automaticSyncIsUniqueAndRequiresUnmeteredNetworkByDefault() {
        SyncScheduler.configure(context, configured)
        SyncScheduler.configure(context, configured)
        val jobs = manager.getWorkInfosForUniqueWork("collection-sync-periodic").get()
        assertEquals(1, jobs.size)
        assertEquals(NetworkType.UNMETERED, jobs.single().constraints.requiredNetworkType)
        assertEquals(TimeUnit.MINUTES.toMillis(30), jobs.single().periodicityInfo!!.repeatIntervalMillis)
    }
    @Test fun manualRequestReplacesWaitingWifiWorkAndUsesTheCurrentNetwork() {
        SyncScheduler.request(context, config = configured)
        val automatic = manager.getWorkInfosForUniqueWork("collection-sync-pending").get().single()
        assertEquals(NetworkType.UNMETERED, automatic.constraints.requiredNetworkType)
        SyncScheduler.request(context, manual = true, config = configured)
        val jobs = manager.getWorkInfosForUniqueWork("collection-sync-pending").get()
        assertEquals(1, jobs.count { !it.state.isFinished })
        assertEquals(NetworkType.CONNECTED, jobs.single { !it.state.isFinished }.constraints.requiredNetworkType)
    }
    @Test fun disablingAutomaticSyncCancelsScheduledAutomaticWork() {
        SyncScheduler.configure(context, configured)
        SyncScheduler.request(context, config = configured)
        SyncScheduler.configure(context, configured.copy(automatic = false))
        assertTrue(manager.getWorkInfosForUniqueWork("collection-sync-periodic").get().all { it.state.isFinished })
        assertTrue(manager.getWorkInfosForUniqueWork("collection-sync-pending").get().all { it.state.isFinished })
    }
    @Test fun unconfiguredWorkerReturnsWithoutReadingOrUploadingTheLibrary() = runBlocking {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertFalse(SyncSignals.status.value.running)
    }
}
