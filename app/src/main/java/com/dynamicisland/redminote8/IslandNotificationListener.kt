package com.dynamicisland.redminote8

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

object IslandLiveStore {
    @Volatile var mediaActive: Boolean = false
    @Volatile var mediaPlaying: Boolean = false
    @Volatile var mediaTitle: String = ""
    @Volatile var mediaArtist: String = ""
    @Volatile var mediaPackage: String = ""
    @Volatile var mediaArt: Bitmap? = null
    @Volatile var activeController: MediaController? = null

    @Volatile var notifActive: Boolean = false
    @Volatile var notifAppName: String = ""
    @Volatile var notifTitle: String = ""
    @Volatile var notifText: String = ""
    @Volatile var notifPackage: String = ""
    @Volatile var notifTimestamp: Long = 0L
}

class IslandNotificationListener : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private var currentController: MediaController? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindBestController(controllers)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            syncMediaState(currentController)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            syncMediaState(currentController)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = ComponentName(this, IslandNotificationListener::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, comp)
            bindBestController(mediaSessionManager?.getActiveSessions(comp))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            currentController?.unregisterCallback(controllerCallback)
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun bindBestController(controllers: List<MediaController>?) {
        try {
            currentController?.unregisterCallback(controllerCallback)
        } catch (_: Exception) {
        }

        if (controllers.isNullOrEmpty()) {
            currentController = null
            IslandLiveStore.activeController = null
            IslandLiveStore.mediaActive = false
            IslandLiveStore.mediaPlaying = false
            notifyIslandService()
            return
        }

        val playing = controllers.firstOrNull {
            val st = it.playbackState?.state
            st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
        } ?: controllers.first()

        currentController = playing
        IslandLiveStore.activeController = playing
        playing.registerCallback(controllerCallback)
        syncMediaState(playing)
    }

    private fun syncMediaState(controller: MediaController?) {
        if (controller == null) {
            IslandLiveStore.mediaActive = false
            IslandLiveStore.mediaPlaying = false
            notifyIslandService()
            return
        }

        val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
        val active = state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_PAUSED
        val meta = controller.metadata

        IslandLiveStore.mediaActive = active
        IslandLiveStore.mediaPlaying = (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING)
        IslandLiveStore.mediaPackage = controller.packageName ?: ""
        IslandLiveStore.mediaTitle = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: getFriendlyAppName(controller.packageName)
        IslandLiveStore.mediaArtist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: getFriendlyAppName(controller.packageName)
        IslandLiveStore.mediaArt = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)

        notifyIslandService()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val token: MediaSession.Token? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
        }

        if (token != null || notif.category == Notification.CATEGORY_TRANSPORT) {
            if (token != null) {
                try {
                    val ctrl = MediaController(this, token)
                    bindBestController(listOf(ctrl))
                    return
                } catch (_: Exception) {
                }
            }
            val mTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val mText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (mTitle.isNotEmpty()) {
                IslandLiveStore.mediaActive = true
                IslandLiveStore.mediaPlaying = true
                IslandLiveStore.mediaPackage = sbn.packageName
                IslandLiveStore.mediaTitle = mTitle
                IslandLiveStore.mediaArtist = mText.ifBlank { getFriendlyAppName(sbn.packageName) }
                notifyIslandService()
                return
            }
        }

        if ((notif.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        IslandLiveStore.notifActive = true
        IslandLiveStore.notifPackage = sbn.packageName
        IslandLiveStore.notifAppName = getFriendlyAppName(sbn.packageName)
        IslandLiveStore.notifTitle = title
        IslandLiveStore.notifText = text
        IslandLiveStore.notifTimestamp = System.currentTimeMillis()
        notifyIslandService()
    }

    private fun getFriendlyAppName(pkg: String?): String {
        if (pkg.isNullOrBlank()) return "تطبيق"
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }
    }

    private fun notifyIslandService() {
        val intent = Intent(DynamicIslandService.ACTION_REFRESH).setPackage(packageName)
        sendBroadcast(intent)
    }
}
