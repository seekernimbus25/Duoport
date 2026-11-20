package app.filmmaking.studio.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.filmmaking.studio.data.SessionStore
import app.filmmaking.studio.data.StudioAuthClient
import app.filmmaking.studio.data.StudioDatabase
import app.filmmaking.studio.data.StudioExport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit

/** One entry point for foreground ("Sync now") and background (WorkManager) sync. */
class StudioSync(context: Context) {
    private val app = context.applicationContext
    val sessions = SessionStore(app, StudioAuthClient())
    private val db = StudioDatabase.get(app)
    private val status = app.getSharedPreferences("studio_sync_status", Context.MODE_PRIVATE)

    val lastSummary: String get() = status.getString("summary", "Never synced").orEmpty()
    val lastSyncAt: Long get() = status.getLong("at", 0L)

    suspend fun run(choices: Map<String, Resolution> = emptyMap()): SyncReport = lock.withLock {
        val session = sessions.fresh() ?: error("Sign in to sync with the cloud")
        val api = SupabaseRest({ session.accessToken })
        val engine = SyncEngine(db, api, session.userId.ifBlank { error("Sign in again to enable sync") },
            sessions.deviceId) { saveRecoveryPoint() }
        val report = engine.run(choices)
        status.edit().putString("summary", report.summary).putLong("at", report.at).apply()
        report
    }

    /** Local recovery point before cloud data changes this device; last 5 kept. */
    private suspend fun saveRecoveryPoint() {
        val dir = File(app.filesDir, "recovery").apply { mkdirs() }
        File(dir, "before-sync-" + System.currentTimeMillis() + ".json").writeText(StudioExport(db).backup())
        dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
    }

    companion object {
        private val lock = Mutex()
        private const val PERIODIC = "studio-cloud-sync"
        private const val ONCE = "studio-cloud-sync-now"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Background sync every 6 hours while signed in; conflicts are never auto-resolved. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(network).build())
        }

        fun requestSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network)
                    .setInitialDelay(20, TimeUnit.SECONDS).build())
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(ONCE)
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = StudioSync(applicationContext)
        if (sync.sessions.load() == null) return Result.success()
        return try {
            sync.run()
            Result.success()
        } catch (e: app.filmmaking.studio.data.AuthException) {
            Result.success() // signed out or revoked: wait for the user to sign in again
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
