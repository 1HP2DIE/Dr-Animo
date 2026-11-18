package com.animo.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.animo.app.di.ServiceLocator

/** One quiet pass of [EpisodeBackfill.refreshAired], queued shortly after each app start. */
class AiredSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        ServiceLocator.install(applicationContext)
        ServiceLocator.episodeBackfill.refreshAired()
        return Result.success()
    }
}
