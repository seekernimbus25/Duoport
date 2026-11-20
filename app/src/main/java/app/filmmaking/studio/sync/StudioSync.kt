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

    private val ownerPrefs = app.getSharedPreferences("studio_local_owner", Context.MODE_PRIVATE)

    /** The account this phone's local work is linked to. Kept on sign-out so another account can't absorb it. */
    val owner: LocalOwner?
        get() = ownerPrefs.getString("user", null)?.let { LocalOwner(it, ownerPrefs.getString("email", "").orEmpty()) }

    /** Account whose identity and the cloud's RLS were confirmed on this phone for this sign-in. */
    private val verifiedUser: String? get() = ownerPrefs.getString("verified", null)

    /**
     * Run after every sign-in or session restore, before any sync: confirms with the server that the token belongs
     * to [session]'s account and that RLS isolates it, then decides what may happen to local work.
     * Returns the decision, or throws with a plain-language reason (sync stays off; nothing local changes).
     */
    suspend fun prepare(session: StudioAuthClient.Session, auth: StudioAuthClient): LinkDecision {
        ownerPrefs.edit().remove("verified").apply()
        val serverId = auth.user(session.accessToken).optString("id")
        check(serverId.isNotBlank() && serverId == session.userId) { "The server did not confirm this account. Sign in again." }
        CloudAccessCheck.verify(SupabaseRest({ session.accessToken }), session.userId)?.let { error(it) }
        ownerPrefs.edit().putString("verified", session.userId).apply()
        val decision = OwnershipGate.decide(owner, db.dao().syncedUserIds().toSet(), session.userId, db.dao().liveCount())
        if (decision == LinkDecision.LinkEmpty || decision == LinkDecision.Proceed) link(session)
        return decision
    }

    /** Links this phone's local work to [session]'s account (only after [prepare] verified it). */
    fun link(session: StudioAuthClient.Session) {
        check(verifiedUser == session.userId) { "Verify the account before linking local work" }
        val current = owner
        check(current == null || current.userId == session.userId) { "Local work belongs to another account" }
        ownerPrefs.edit().putString("user", session.userId).putString("email", session.email).apply()
    }

    /** Forgets the verification on sign-out (the owner link and all local data stay). */
    fun signedOut() { ownerPrefs.edit().remove("verified").apply() }

    suspend fun run(choices: Map<String, Resolution> = emptyMap()): SyncReport = lock.withLock {
        val session = sessions.fresh() ?: error("Sign in to sync with the cloud")
        // Never sync one account's local work into another, and never before RLS was confirmed.
        check(owner?.userId == session.userId && verifiedUser == session.userId) {
            "Sync is paused: this phone's work is not linked to " + session.email.ifBlank { "this account" } + "."
        }
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
        val session = sync.sessions.load() ?: return Result.success()
        if (sync.owner?.userId != session.userId) return Result.success() // not linked: never sync
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
