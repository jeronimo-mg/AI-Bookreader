package com.example.epubstoryteller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.epubstoryteller.MainActivity

/**
 * Serviço em Primeiro Plano (Foreground Service do tipo `mediaPlayback`),
 * idêntico ao mecanismo usado por apps de música/audiolivro (como Spotify e Audible).
 *
 * Mantém:
 * 1. O processo vivo com prioridade de mídia quando o usuário troca de app ou bloqueia a tela.
 * 2. A conexão de rede (WebSocket do Gemini 3.8 Live) ativa sem sofrer "Software caused connection abort" do Android.
 * 3. CPU Partial WakeLock + WifiLock de baixa latência enquanto a leitura está em andamento.
 * 4. Notificação de mídia na tela de bloqueio / barra de status com botões de Play/Pause, Anterior e Próximo.
 */
class StorytellerPlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "epub_storyteller_playback_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_OR_UPDATE = "com.example.epubstoryteller.action.START_OR_UPDATE"
        const val ACTION_STOP = "com.example.epubstoryteller.action.STOP"

        const val ACTION_NOTIFICATION_TOGGLE = "com.example.epubstoryteller.action.NOTIF_TOGGLE"
        const val ACTION_NOTIFICATION_PREV = "com.example.epubstoryteller.action.NOTIF_PREV"
        const val ACTION_NOTIFICATION_NEXT = "com.example.epubstoryteller.action.NOTIF_NEXT"

        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SUBTITLE = "extra_subtitle"
        private const val EXTRA_IS_PLAYING = "extra_is_playing"

        // Callbacks estáticos registrados pelo ReaderViewModel para responder aos botões da notificação/tela de bloqueio
        var onTogglePlayPauseCommand: (() -> Unit)? = null
        var onPrevParagraphCommand: (() -> Unit)? = null
        var onNextParagraphCommand: (() -> Unit)? = null

        fun startOrUpdate(
            context: Context,
            bookTitle: String,
            subtitle: String,
            isPlaying: Boolean
        ) {
            val intent = Intent(context, StorytellerPlaybackService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, bookTitle)
                putExtra(EXTRA_SUBTITLE, subtitle)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            if (isPlaying) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, StorytellerPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastTitle: String = "EPUB Storyteller"
    private var lastSubtitle: String = "Pronto para leitura"
    private var isCurrentlyPlaying: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_OR_UPDATE -> {
                lastTitle = intent.getStringExtra(EXTRA_TITLE) ?: lastTitle
                lastSubtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: lastSubtitle
                isCurrentlyPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)

                if (isCurrentlyPlaying) {
                    acquireLocks()
                    val notification = buildNotification(lastTitle, lastSubtitle, true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceCompat.startForeground(
                            this,
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } else {
                    releaseLocks()
                    val notification = buildNotification(lastTitle, lastSubtitle, false)
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, notification)
                }
            }

            ACTION_NOTIFICATION_TOGGLE -> {
                onTogglePlayPauseCommand?.invoke()
            }

            ACTION_NOTIFICATION_PREV -> {
                onPrevParagraphCommand?.invoke()
            }

            ACTION_NOTIFICATION_NEXT -> {
                onNextParagraphCommand?.invoke()
            }

            ACTION_STOP -> {
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun buildNotification(title: String, subtitle: String, isPlaying: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevPending = PendingIntent.getService(
            this,
            1,
            Intent(this, StorytellerPlaybackService::class.java).apply { action = ACTION_NOTIFICATION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val togglePending = PendingIntent.getService(
            this,
            2,
            Intent(this, StorytellerPlaybackService::class.java).apply { action = ACTION_NOTIFICATION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextPending = PendingIntent.getService(
            this,
            3,
            Intent(this, StorytellerPlaybackService::class.java).apply { action = ACTION_NOTIFICATION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSubText("Gemini 3.8 Live • Storyteller")
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", prevPending)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pausar" else "Ler",
                togglePending
            )
            .addAction(android.R.drawable.ic_media_next, "Próximo", nextPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Leitura em Voz Alta (Audiolivro)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém a narração do livro ativa em segundo plano e com a tela desligada"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "EpubStoryteller::GeminiLiveWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(4 * 60 * 60 * 1000L) // Até 4 horas contínuas
            }

            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockType, "EpubStoryteller::GeminiLiveWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
