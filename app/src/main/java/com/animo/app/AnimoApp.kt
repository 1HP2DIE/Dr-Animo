package com.animo.app

import android.app.Application
import com.animo.app.data.sync.SyncScheduler
import com.animo.app.di.ServiceLocator

/**
 * Process entry point. Order matters:
 *
 * 1. install the container (everything else calls into it),
 * 2. schedule the nightly catalog sync (idempotent, `KEEP`),
 * 3. nothing else — no eager network, no eager DB open. The first screen decides what it needs, and
 *    "cache or crash" means an empty catalog is a valid, renderable state.
 */
class AnimoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        ServiceLocator.install(this)
        AppLog.i("Animo ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) starting")

        SyncScheduler.ensureScheduled(this)
    }
}
