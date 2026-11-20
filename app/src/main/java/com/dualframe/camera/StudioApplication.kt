package com.dualframe.camera

import android.app.Application
import android.content.Context
import android.util.Log
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid

/**
 * Crash diagnostics for the COMBINED Studio + Duoport process.
 *
 * The SDK starts only when both a Sentry DSN is configured in the build and the
 * tester explicitly enables diagnostics in Studio Settings. No user identifiers,
 * camera frames, scripts, model prompts or tokens are attached to events.
 *
 * Sentry captures uncaught Kotlin/Java crashes, native crashes (via NDK module)
 * and ANRs; events are queued until connectivity and a subsequent app launch.
 */
class StudioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StudioDiagnostics.initialize(this)
    }
}

object StudioDiagnostics {
    private const val PREFS = "studio_crash_diagnostics"
    private const val ENABLED = "consented"
    @Volatile private var started = false

    fun isConfigured(): Boolean = BuildConfig.SENTRY_DSN.startsWith("https://")
    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)

    @Synchronized
    fun initialize(context: Context): Boolean {
        if (started) return true
        if (!isConfigured() || !isEnabled(context)) return false
        return runCatching {
            SentryAndroid.init(context.applicationContext) { options ->
                options.dsn = BuildConfig.SENTRY_DSN
                options.isSendDefaultPii = false
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false
                options.environment = if (BuildConfig.DEBUG) "development" else "sideload"
            }
            Sentry.setTag("app", "filmmaking-studio-duoport")
            Sentry.setTag("package", BuildConfig.APPLICATION_ID)
            started = true
            true
        }.getOrElse {
            Log.w("StudioDiagnostics", "Could not initialize diagnostics")
            false
        }
    }

    @Synchronized
    fun setConsent(context: Context, consent: Boolean): Boolean {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, consent).apply()
        return if (consent) initialize(context) else {
            if (started) {
                runCatching { Sentry.close() }
                started = false
            }
            false
        }
    }

    /**
     * Only call with fixed, developer-authored names. Never include record contents,
     * user emails, AI prompts, filenames or URIs in diagnostic breadcrumbs.
     */
    fun breadcrumb(module: String, action: String) {
        if (started && module in setOf("studio", "camera", "export", "ai") &&
            action.matches(Regex("[a-zA-Z0-9_-]{1,48}"))) {
            Sentry.addBreadcrumb(module + "." + action)
        }
    }

    /** Safe verification event; it never intentionally crashes the application. */
    fun sendTestEvent(): Boolean {
        if (!started) return false
        Sentry.captureException(IllegalStateException("Studio-Duoport diagnostics integration test"))
        return true
    }
}
