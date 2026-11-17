@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.animo.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceFactory
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import okhttp3.OkHttpClient

/**
 * Background playback + media notification.
 *
 * Declared by Agent A as `com.animo.app.player.PlaybackService` with
 * `foregroundServiceType="mediaPlayback"` and the `androidx.media3.session.MediaSessionService`
 * action (research/STACK.md). The service owns its own player, so playback survives the activity.
 *
 * Referer is per media item (see [mediaItem]): a new `OkHttpDataSource.Factory` is built for every
 * item inside [PerItemHlsSourceFactory], never shared (research/RESOLVER_SPEC.md §3).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID)
                .build(),
        )

        val exoPlayer = Media3Factory.newPlayer(
            context = this,
            mediaSourceFactory = PerItemHlsSourceFactory(Media3Factory.playbackClient),
        )
        player = exoPlayer

        val builder = MediaSession.Builder(this, exoPlayer)
        sessionActivityIntent()?.let { builder.setSessionActivity(it) }
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        player?.pause()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val label = applicationInfo.loadLabel(packageManager).toString().ifBlank { "Playback" }
        val channel = NotificationChannel(CHANNEL_ID, label, NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun sessionActivityIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID: String = "animo_playback"

        /** Wraps a resolved master playlist with the headers its CDN requires. */
        fun mediaItem(
            masterUrl: String,
            referer: String,
            userAgent: String = Media3Factory.DEFAULT_USER_AGENT,
            title: String? = null,
        ): MediaItem {
            val extras = Bundle().apply {
                putString(PerItemHlsSourceFactory.EXTRA_REFERER, referer)
                putString(PerItemHlsSourceFactory.EXTRA_USER_AGENT, userAgent)
            }
            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setExtras(extras)
                .build()
            return MediaItem.Builder()
                .setUri(masterUrl)
                .setMediaMetadata(metadata)
                .build()
        }
    }
}

/**
 * Builds HLS sources with a per-item Referer. The data-source factory must be created here — one per
 * playback — because `setDefaultRequestProperties` is inherited by already-created sources.
 */
@OptIn(UnstableApi::class)
private class PerItemHlsSourceFactory(
    private val client: OkHttpClient,
) : MediaSourceFactory {

    override fun getSupportedTypes(): IntArray =
        intArrayOf(C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_OTHER)

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSourceFactory = this

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSourceFactory = this

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val extras = mediaItem.mediaMetadata.extras
        val referer = extras?.getString(EXTRA_REFERER)?.takeIf { it.isNotBlank() }
            ?: Referers.primary()
        val userAgent = extras?.getString(EXTRA_USER_AGENT)?.takeIf { it.isNotBlank() }
            ?: Media3Factory.DEFAULT_USER_AGENT
        val dataSourceFactory = Media3Factory.dataSourceFactory(referer, userAgent, client)
        return Media3Factory.hlsSource(dataSourceFactory, mediaItem)
    }

    companion object {
        const val EXTRA_REFERER: String = "com.animo.app.player.REFERER"
        const val EXTRA_USER_AGENT: String = "com.animo.app.player.USER_AGENT"
    }
}
