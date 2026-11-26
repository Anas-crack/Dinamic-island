package com.dynamicisland.redminote8

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AnticipateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DynamicIslandService : Service() {

    companion object {
        const val ACTION_REFRESH = "com.dynamicisland.redminote8.ACTION_REFRESH"
        const val ACTION_RELOAD_CONFIG = "com.dynamicisland.redminote8.ACTION_RELOAD_CONFIG"
        const val ACTION_TEST_PING = "com.dynamicisland.redminote8.ACTION_TEST_PING"
        const val ACTION_TEST_ANIM = "com.dynamicisland.redminote8.ACTION_TEST_ANIM"
        private const val CHANNEL_ID = "dynamic_island_overlay_channel"
        private const val NOTIF_ID = 8008
        @Volatile var isRunning: Boolean = false
    }

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var settings: IslandSettings = IslandSettings()
    private var isExpanded: Boolean = false
    private var isIslandHidden: Boolean = false
    private var lastActivityTime: Long = System.currentTimeMillis()
    private var wasCharging: Boolean = false
    private var chargingPopupUntil: Long = 0L

    private var cachedAvatarUri: String? = null
    private var cachedAvatarBitmap: Bitmap? = null

    private val handler = Handler(Looper.getMainLooper())
    private val clockTicker = object : Runnable {
        override fun run() {
            pollActiveMediaSessions()
            checkChargingTransition()
            evaluateAutoHide()
            updateIslandUi()
            handler.postDelayed(this, 1000L)
        }
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_RELOAD_CONFIG -> {
                    settings = IslandConfig.load(this@DynamicIslandService)
                    cachedAvatarUri = null
                    wakeUpIsland(forceAnim = false)
                    applyWindowPosition()
                    updateIslandUi()
                }
                ACTION_TEST_ANIM -> {
                    hideIslandAnimated()
                    handler.postDelayed({ wakeUpIsland(forceAnim = true) }, 750L)
                }
                ACTION_TEST_PING -> {
                    IslandLiveStore.notifActive = true
                    IslandLiveStore.notifPackage = packageName
                    IslandLiveStore.notifAppName = "Dynamic Island"
                    IslandLiveStore.notifTitle = "الإصدار v${BuildConfig.VERSION_NAME} يعمل بنجاح!"
                    IslandLiveStore.notifText = "اضغط في أي مكان بالشاشة لتصغير الجزيرة"
                    IslandLiveStore.notifTimestamp = System.currentTimeMillis()
                    wakeUpIsland(forceAnim = true)
                    setExpanded(true)
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    wasCharging = true
                    chargingPopupUntil = System.currentTimeMillis() + 6000L
                    wakeUpIsland(forceAnim = true)
                    setExpanded(true)
                    handler.postDelayed({
                        if (System.currentTimeMillis() >= chargingPopupUntil && isExpanded) {
                            setExpanded(false)
                        }
                    }, 6100L)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    wasCharging = false
                    chargingPopupUntil = 0L
                    updateIslandUi()
                }
                ACTION_REFRESH,
                Intent.ACTION_BATTERY_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED,
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    if (intent.action == ACTION_REFRESH) wakeUpIsland(forceAnim = false)
                    checkChargingTransition()
                    updateIslandUi()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = IslandConfig.load(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        wasCharging = getBatteryInfo().second
        lastActivityTime = System.currentTimeMillis()

        try {
            val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIF_ID, buildForegroundNotification(), fgType)
        } catch (_: Exception) {
            try {
                startForeground(NOTIF_ID, buildForegroundNotification())
            } catch (_: Exception) {
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            isRunning = false
            Toast.makeText(this, "فعّل إذن الظهور فوق التطبيقات أولًا", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        if (!attachOverlayView()) {
            isRunning = false
            stopSelf()
            return
        }

        isRunning = true

        val filter = IntentFilter().apply {
            addAction(ACTION_REFRESH)
            addAction(ACTION_RELOAD_CONFIG)
            addAction(ACTION_TEST_PING)
            addAction(ACTION_TEST_ANIM)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(updateReceiver, filter)
        }

        wakeUpIsland(forceAnim = true)
        handler.post(clockTicker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        settings = IslandConfig.load(this)
        if (rootView == null && Settings.canDrawOverlays(this)) {
            if (attachOverlayView()) {
                isRunning = true
            }
        }
        wakeUpIsland(forceAnim = false)
        applyWindowPosition()
        pollActiveMediaSessions()
        updateIslandUi()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(clockTicker)
        try {
            unregisterReceiver(updateReceiver)
        } catch (_: Exception) {
        }
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        rootView = null
        super.onDestroy()
    }

    // Juicy iPhone-style Elastic Pop-In Animation
    private fun wakeUpIsland(forceAnim: Boolean) {
        lastActivityTime = System.currentTimeMillis()
        val pill = rootView?.findViewById<View>(R.id.islandPill) ?: return
        if (isIslandHidden || forceAnim) {
            isIslandHidden = false
            pill.animate().cancel()
            pill.visibility = View.VISIBLE
            pill.alpha = 0f
            pill.translationY = -dpToPx(38).toFloat()
            pill.scaleX = 0.34f
            pill.scaleY = 0.45f
            pill.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(480L)
                .setInterpolator(OvershootInterpolator(1.35f))
                .start()
        }
    }

    // Juicy iPhone-style Elastic Pop-Out Animation
    private fun hideIslandAnimated() {
        val pill = rootView?.findViewById<View>(R.id.islandPill) ?: return
        if (isIslandHidden) return
        isIslandHidden = true
        isExpanded = false
        pill.animate().cancel()
        pill.animate()
            .alpha(0f)
            .translationY(-dpToPx(40).toFloat())
            .scaleX(0.30f)
            .scaleY(0.42f)
            .setDuration(360L)
            .setInterpolator(AnticipateInterpolator(1.2f))
            .withEndAction {
                if (isIslandHidden) pill.visibility = View.GONE
            }
            .start()
    }

    private fun evaluateAutoHide() {
        if (!settings.autoHideEnabled || settings.autoHideSeconds <= 0) {
            if (isIslandHidden) wakeUpIsland(forceAnim = true)
            return
        }
        val hasRecentNotif = IslandLiveStore.notifActive &&
            (System.currentTimeMillis() - IslandLiveStore.notifTimestamp < 7000L)
        val hasCharging = System.currentTimeMillis() < chargingPopupUntil
        val isBusy = isExpanded || IslandLiveStore.mediaPlaying || hasRecentNotif || hasCharging

        if (isBusy) {
            wakeUpIsland(forceAnim = false)
        } else {
            val waitMs = settings.autoHideSeconds.coerceIn(5, 300) * 1000L
            if (!isIslandHidden && (System.currentTimeMillis() - lastActivityTime >= waitMs)) {
                hideIslandAnimated()
            }
        }
    }

    private fun isBluetoothOn(): Boolean {
        return try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isSilentOrVibrate(): Boolean {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode != AudioManager.RINGER_MODE_NORMAL
        } catch (_: Exception) {
            false
        }
    }

    private fun checkChargingTransition() {
        val (_, nowCharging) = getBatteryInfo()
        if (nowCharging && !wasCharging) {
            wasCharging = true
            chargingPopupUntil = System.currentTimeMillis() + 6000L
            wakeUpIsland(forceAnim = true)
            setExpanded(true)
            handler.postDelayed({
                if (System.currentTimeMillis() >= chargingPopupUntil && isExpanded) {
                    setExpanded(false)
                }
            }, 6100L)
        } else if (!nowCharging && wasCharging) {
            wasCharging = false
            chargingPopupUntil = 0L
        }
    }

    private fun handleMediaControl(action: String) {
        wakeUpIsland(forceAnim = false)
        pollActiveMediaSessions()
        val ctrl = IslandLiveStore.activeController

        when (action) {
            "prev" -> {
                var handled = false
                try {
                    if (ctrl != null) { ctrl.transportControls.skipToPrevious(); handled = true }
                } catch (_: Exception) {}
                if (!handled) sendUniversalMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            "next" -> {
                var handled = false
                try {
                    if (ctrl != null) { ctrl.transportControls.skipToNext(); handled = true }
                } catch (_: Exception) {}
                if (!handled) sendUniversalMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            "toggle" -> {
                val wantPause = IslandLiveStore.mediaPlaying
                var handled = false
                try {
                    if (ctrl != null) {
                        if (wantPause) ctrl.transportControls.pause()
                        else ctrl.transportControls.play()
                        handled = true
                    }
                } catch (_: Exception) {}
                if (!handled) {
                    sendUniversalMediaKey(if (wantPause) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY)
                    sendUniversalMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                }
                IslandLiveStore.mediaPlaying = !wantPause
                updateIslandUi()
            }
        }
    }

    private fun sendUniversalMediaKey(keyCode: Int) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now + 10, now + 10, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (_: Exception) {
        }
    }

    private fun pollActiveMediaSessions() {
        val hasNotifPerm = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (hasNotifPerm) {
            try {
                val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val comp = ComponentName(this, IslandNotificationListener::class.java)
                val sessions = msm.getActiveSessions(comp)
                if (!sessions.isNullOrEmpty()) {
                    val best = sessions.firstOrNull {
                        val st = it.playbackState?.state
                        st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
                    } ?: sessions.firstOrNull {
                        it.playbackState?.state == PlaybackState.STATE_PAUSED
                    }

                    if (best != null) {
                        val st = best.playbackState?.state ?: PlaybackState.STATE_NONE
                        val meta = best.metadata
                        val pkg = best.packageName ?: ""
                        val appLabel = getFriendlyAppName(pkg)
                        val wasPlaying = IslandLiveStore.mediaPlaying
                        IslandLiveStore.activeController = best
                        IslandLiveStore.mediaActive = true
                        IslandLiveStore.mediaPlaying = (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING)
                        IslandLiveStore.mediaPackage = pkg
                        IslandLiveStore.mediaTitle = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                            ?.takeIf { it.isNotBlank() } ?: appLabel
                        IslandLiveStore.mediaArtist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            ?.takeIf { it.isNotBlank() }
                            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
                            ?: appLabel
                        IslandLiveStore.mediaArt = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        if (!wasPlaying && IslandLiveStore.mediaPlaying) wakeUpIsland(forceAnim = true)
                        return
                    }
                }
            } catch (_: Exception) {
            }
        }

        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.isMusicActive) {
                if (!IslandLiveStore.mediaActive) {
                    IslandLiveStore.mediaActive = true
                    IslandLiveStore.mediaPlaying = true
                    IslandLiveStore.mediaTitle = "وسائط قيد التشغيل"
                    IslandLiveStore.mediaArtist = "اضغط للتحكم بالتشغيل"
                    IslandLiveStore.mediaPackage = "com.google.android.youtube"
                    wakeUpIsland(forceAnim = true)
                }
            } else if (IslandLiveStore.activeController == null) {
                IslandLiveStore.mediaActive = false
                IslandLiveStore.mediaPlaying = false
            }
        } catch (_: Exception) {
        }
    }

    private fun getFriendlyAppName(pkg: String?): String {
        if (pkg.isNullOrBlank()) return "وسائط"
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Dynamic Island",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_island_notif)
            .setContentTitle("Dynamic Island (v${BuildConfig.VERSION_NAME})")
            .setContentText("الجزيرة العائمة تعمل الآن")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun setExpanded(expand: Boolean) {
        if (isExpanded == expand) return
        isExpanded = expand
        lastActivityTime = System.currentTimeMillis()
        val pill = rootView?.findViewById<ViewGroup>(R.id.islandPill)
        if (pill != null) {
            val transition = AutoTransition().apply { duration = 220 }
            TransitionManager.beginDelayedTransition(pill, transition)
        }
        applyWindowPosition()
        updateIslandUi()
    }

    private fun attachOverlayView(): Boolean {
        return try {
            val themedCtx = ContextThemeWrapper(this, R.style.Theme_DynamicIsland)
            val view = LayoutInflater.from(themedCtx).inflate(R.layout.layout_dynamic_island, null)
            rootView = view

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val lp = WindowManager.LayoutParams(
                if (settings.coverStatusBar) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = if (settings.coverStatusBar) 0 else dpToPx(settings.topOffsetDp)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            layoutParams = lp
            windowManager.addView(view, lp)

            clipRounded(view.findViewById(R.id.ivCompactIcon), 999f)
            clipRounded(view.findViewById(R.id.ivExpandedIcon), dpToPx(16).toFloat())

            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE && isExpanded) {
                    setExpanded(false)
                    return@setOnTouchListener true
                }
                false
            }

            val pill = view.findViewById<View>(R.id.islandPill)
            pill.setOnClickListener {
                lastActivityTime = System.currentTimeMillis()
                setExpanded(!isExpanded)
            }

            view.findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { handleMediaControl("prev") }
            view.findViewById<ImageButton>(R.id.btnPlayPause).setOnClickListener { handleMediaControl("toggle") }
            view.findViewById<ImageButton>(R.id.btnNext).setOnClickListener { handleMediaControl("next") }
            view.findViewById<TextView>(R.id.btnOpenSettings).setOnClickListener {
                setExpanded(false)
                val i = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(i)
            }
            true
        } catch (e: Exception) {
            rootView = null
            Toast.makeText(
                this,
                "خطأ في إظهار الجزيرة: ${e.javaClass.simpleName}: ${e.localizedMessage ?: ""}",
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    private fun clipRounded(v: View, radiusPx: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = if (radiusPx > 500f) view.width / 2f else radiusPx
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        v.clipToOutline = true
    }

    private fun applyWindowPosition() {
        val v = rootView ?: return
        val lp = layoutParams ?: return
        lp.width = if (settings.coverStatusBar && !isExpanded) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }
        lp.y = if (settings.coverStatusBar && !isExpanded) 0 else dpToPx(settings.topOffsetDp)
        try {
            windowManager.updateViewLayout(v, lp)
        } catch (_: Exception) {
        }
    }

    private fun getNetworkStatus(): Pair<Boolean, Boolean> {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return Pair(false, false)
            val caps = cm.getNetworkCapabilities(net) ?: return Pair(false, false)
            Pair(
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            )
        } catch (_: Exception) {
            Pair(false, false)
        }
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        return Pair(pct, bm.isCharging)
    }

    // Crisp anti-aliased center-square high-res bitmap decoder (ZERO pixelation / blur!)
    private fun getCrispAvatarBitmap(): Bitmap? {
        val uriStr = settings.avatarUri
        if (uriStr.isNullOrBlank()) return null
        if (uriStr == cachedAvatarUri && cachedAvatarBitmap != null) return cachedAvatarBitmap
        return try {
            val uri = Uri.parse(uriStr)
            val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, optsBounds) }
            val maxDim = maxOf(optsBounds.outWidth, optsBounds.outHeight)
            var sample = 1
            while (maxDim / sample > 512) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            val side = minOf(raw.width, raw.height)
            val x = (raw.width - side) / 2
            val y = (raw.height - side) / 2
            val cropped = Bitmap.createBitmap(raw, x, y, side, side)
            val crisp = Bitmap.createScaledBitmap(cropped, 256, 256, true)
            cachedAvatarUri = uriStr
            cachedAvatarBitmap = crisp
            crisp
        } catch (_: Exception) {
            null
        }
    }

    private fun updateIslandUi() {
        val view = rootView ?: return
        val pill = view.findViewById<LinearLayout>(R.id.islandPill)
        val compactRow = view.findViewById<LinearLayout>(R.id.compactRow)
        val expandedBox = view.findViewById<LinearLayout>(R.id.expandedBox)
        val mediaControls = view.findViewById<LinearLayout>(R.id.mediaControlsRow)
        val notchCenterGap = view.findViewById<Space>(R.id.notchCenterGap)

        val ivBluetooth = view.findViewById<ImageView>(R.id.ivBluetooth)
        val ivSilent = view.findViewById<ImageView>(R.id.ivSilent)
        val ivWifi = view.findViewById<ImageView>(R.id.ivWifi)
        val ivSignal = view.findViewById<ImageView>(R.id.ivSignal)
        val tvBatteryLeft = view.findViewById<TextView>(R.id.tvBatteryLeft)
        val tvCompactLeftStatus = view.findViewById<TextView>(R.id.tvCompactLeftStatus)
        val tvCompactRightName = view.findViewById<TextView>(R.id.tvCompactRightName)
        val ivCompactIcon = view.findViewById<ImageView>(R.id.ivCompactIcon)

        val ivExpandedIcon = view.findViewById<ImageView>(R.id.ivExpandedIcon)
        val tvExpandedTitle = view.findViewById<TextView>(R.id.tvExpandedTitle)
        val tvExpandedSub = view.findViewById<TextView>(R.id.tvExpandedSub)
        val tvExpandedMeta = view.findViewById<TextView>(R.id.tvExpandedMeta)
        val btnPlayPause = view.findViewById<ImageButton>(R.id.btnPlayPause)

        val (hasWifi, hasCell) = getNetworkStatus()
        val (batteryPct, isCharging) = getBatteryInfo()
        val btOn = isBluetoothOn()
        val silentOn = isSilentOrVibrate()
        val isChargingAlert = isCharging && System.currentTimeMillis() < chargingPopupUntil

        val solidBg = IslandConfig.themeSolidColor(settings.themeId)
        val isLight = IslandConfig.isLightTheme(settings.themeId)
        val strokeColor = when {
            isChargingAlert -> Color.rgb(52, 211, 153)
            isLight -> Color.rgb(203, 213, 225)
            else -> Color.rgb(30, 41, 59)
        }
        val primaryText = if (isLight) Color.rgb(15, 23, 42) else Color.WHITE
        val mutedText = if (isLight) Color.rgb(71, 85, 105) else Color.rgb(190, 204, 224)

        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            if (settings.coverStatusBar && !isExpanded) {
                val r = dpToPx(14).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            } else {
                cornerRadius = dpToPx(settings.cornerRadiusDp).toFloat()
            }
            setColor(solidBg)
            setStroke(dpToPx(1), strokeColor)
        }
        pill.background = drawable

        val targetWidth = when {
            isExpanded -> dpToPx(344)
            settings.coverStatusBar -> ViewGroup.LayoutParams.MATCH_PARENT
            else -> dpToPx(settings.compactWidthDp)
        }
        val targetMinHeight = if (settings.coverStatusBar && !isExpanded) {
            dpToPx(settings.barHeightDp.coerceIn(30, 58))
        } else {
            0
        }
        val pParams = pill.layoutParams
        if (pParams.width != targetWidth) {
            pParams.width = targetWidth
            pill.layoutParams = pParams
        }
        pill.minimumHeight = targetMinHeight

        val pad = if (settings.coverStatusBar && !isExpanded) 0 else dpToPx(4)
        view.setPadding(pad, if (settings.coverStatusBar && !isExpanded) 0 else pad, pad, pad)

        val gapDp = settings.notchGapDp.coerceIn(22, 90)
        val gapPx = dpToPx(gapDp)
        if (notchCenterGap.layoutParams.width != gapPx) {
            val gp = notchCenterGap.layoutParams
            gp.width = gapPx
            notchCenterGap.layoutParams = gp
        }

        tvCompactLeftStatus.setTextColor(primaryText)
        tvCompactRightName.setTextColor(primaryText)
        tvExpandedTitle.setTextColor(primaryText)
        tvExpandedSub.setTextColor(mutedText)
        tvExpandedMeta.setTextColor(if (isChargingAlert) Color.rgb(52, 211, 153) else mutedText)

        ivBluetooth.visibility = if (settings.showStatusIcons && btOn) View.VISIBLE else View.GONE
        ivBluetooth.setColorFilter(Color.rgb(96, 165, 250))

        ivSilent.visibility = if (settings.showStatusIcons && silentOn) View.VISIBLE else View.GONE
        ivSilent.setColorFilter(Color.rgb(251, 191, 36))

        ivWifi.visibility = if (settings.showStatusIcons && hasWifi) View.VISIBLE else View.GONE
        ivWifi.setColorFilter(if (isLight) Color.rgb(2, 132, 199) else Color.rgb(56, 189, 248))

        ivSignal.visibility = if (settings.showStatusIcons && (hasCell || !hasWifi)) View.VISIBLE else View.GONE
        ivSignal.setColorFilter(if (hasCell) (if (isLight) Color.rgb(15, 23, 42) else Color.WHITE) else Color.rgb(100, 116, 139))

        tvBatteryLeft.visibility = if (settings.showStatusIcons) View.VISIBLE else View.GONE
        tvBatteryLeft.text = if (isCharging) "⚡$batteryPct%" else "$batteryPct%"
        tvBatteryLeft.setTextColor(
            when {
                isCharging -> Color.rgb(52, 211, 153)
                batteryPct <= 20 -> Color.rgb(251, 113, 133)
                else -> primaryText
            }
        )

        val showNotif = IslandLiveStore.notifActive &&
            (System.currentTimeMillis() - IslandLiveStore.notifTimestamp < 7000L)

        val now = Date()
        val timeFmt = SimpleDateFormat(if (settings.use24Hour) "HH:mm" else "hh:mm", Locale.ENGLISH)
        val fullTimeFmt = SimpleDateFormat(if (settings.use24Hour) "HH:mm" else "hh:mm a", Locale("ar"))
        val dateFmt = SimpleDateFormat("EEEE، d MMMM", Locale("ar"))
        val statusSummary = buildString {
            append(if (hasWifi) "Wi-Fi" else if (hasCell) "بيانات" else "بدون نت")
            if (btOn) append(" • بلوتوث")
            if (silentOn) append(" • صامت")
            append(" • ${if (isCharging) "⚡" else ""}$batteryPct%")
        }

        compactRow.visibility = if (isExpanded) View.GONE else View.VISIBLE
        expandedBox.visibility = if (isExpanded) View.VISIBLE else View.GONE

        when {
            isChargingAlert -> {
                ivCompactIcon.setImageResource(R.drawable.ic_charging_bolt)
                ivExpandedIcon.setImageResource(R.drawable.ic_charging_bolt)
                tvCompactRightName.text = "جاري الشحن ⚡"
                tvCompactLeftStatus.text = "$batteryPct%"
                tvExpandedTitle.text = "جاري الشحن السريع ⚡"
                tvExpandedSub.text = "نسبة البطارية الحالية: $batteryPct%"
                tvExpandedMeta.text = statusSummary
                mediaControls.visibility = View.GONE
            }

            showNotif -> {
                val appIcon = loadAppIcon(IslandLiveStore.notifPackage)
                if (appIcon != null) {
                    ivCompactIcon.setImageDrawable(appIcon)
                    ivExpandedIcon.setImageDrawable(appIcon)
                } else {
                    ivCompactIcon.setImageResource(R.drawable.app_logo)
                    ivExpandedIcon.setImageResource(R.drawable.app_logo)
                }
                tvCompactRightName.text = IslandLiveStore.notifAppName
                tvCompactLeftStatus.text = "رسالة"
                tvExpandedTitle.text = IslandLiveStore.notifTitle.ifBlank { IslandLiveStore.notifAppName }
                tvExpandedSub.text = IslandLiveStore.notifText
                tvExpandedMeta.text = "${IslandLiveStore.notifAppName} • $statusSummary"
                mediaControls.visibility = View.GONE
            }

            IslandLiveStore.mediaActive -> {
                val appIcon = loadAppIcon(IslandLiveStore.mediaPackage)
                val art = IslandLiveStore.mediaArt
                if (appIcon != null) ivCompactIcon.setImageDrawable(appIcon)
                else if (art != null) ivCompactIcon.setImageBitmap(art)
                else ivCompactIcon.setImageResource(R.drawable.app_logo)

                if (art != null) ivExpandedIcon.setImageBitmap(art)
                else if (appIcon != null) ivExpandedIcon.setImageDrawable(appIcon)
                else ivExpandedIcon.setImageResource(R.drawable.app_logo)

                tvCompactRightName.text = IslandLiveStore.mediaTitle
                tvCompactLeftStatus.text = if (IslandLiveStore.mediaPlaying) "♪" else "⏸"
                tvExpandedTitle.text = IslandLiveStore.mediaTitle
                tvExpandedSub.text = IslandLiveStore.mediaArtist
                tvExpandedMeta.text = "${if (IslandLiveStore.mediaPlaying) "يعمل الآن" else "متوقف"} • $statusSummary"
                btnPlayPause.setImageResource(
                    if (IslandLiveStore.mediaPlaying) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play
                )
                mediaControls.visibility = View.VISIBLE
            }

            else -> {
                bindCrispAvatar(ivCompactIcon)
                bindCrispAvatar(ivExpandedIcon)
                tvCompactRightName.text = settings.userName
                tvCompactLeftStatus.text = timeFmt.format(now)
                tvExpandedTitle.text = "${settings.userName} • ${fullTimeFmt.format(now)}"
                tvExpandedSub.text = dateFmt.format(now)
                tvExpandedMeta.text = statusSummary
                mediaControls.visibility = View.GONE
            }
        }
    }

    private fun bindCrispAvatar(iv: ImageView) {
        val bmp = getCrispAvatarBitmap()
        if (bmp != null) {
            iv.setImageBitmap(bmp)
        } else {
            iv.setImageResource(R.drawable.app_logo)
        }
    }

    private fun loadAppIcon(pkg: String): android.graphics.drawable.Drawable? {
        if (pkg.isBlank()) return null
        return try {
            packageManager.getApplicationIcon(pkg)
        } catch (_: Exception) {
            null
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
