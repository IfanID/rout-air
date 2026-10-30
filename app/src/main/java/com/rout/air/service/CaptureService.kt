package com.rout.air.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rout.air.MainActivity
import com.rout.air.R
import com.rout.air.core.CaptureConfig
import com.rout.air.core.ContentMode
import com.rout.air.core.RecordingState
import com.rout.air.output.CaptureEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service bertipe mediaProjection: wajib ada selama tangkap layar
 * berjalan, sekaligus menjadi tempat tombol Stop di luar aplikasi.
 */
class CaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sawActiveState = false

    override fun onCreate() {
        super.onCreate()
        createChannel()

        scope.launch {
            CaptureEngine.state.collect { state ->
                when (state) {
                    RecordingState.Preparing,
                    RecordingState.Previewing,
                    is RecordingState.Recording,
                    -> {
                        sawActiveState = true
                        notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
                    }

                    RecordingState.Idle,
                    is RecordingState.Error,
                    -> Unit
                }
            }
        }

        // Sesi berakhir (pratinjau ditutup atau gagal disiapkan): matikan
        // foreground service. Error tetap tampil di aplikasi.
        scope.launch {
            CaptureEngine.sessionActive.collect { active ->
                if (!active && sawActiveState) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Saat merekam, tombol notifikasi menghentikan rekaman;
                // saat hanya pratinjau, tombol menutup sesi.
                if (CaptureEngine.state.value is RecordingState.Recording) {
                    CaptureEngine.stopRecording()
                } else {
                    CaptureEngine.closeSession()
                }
                return START_NOT_STICKY
            }
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(RecordingState.Preparing),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val request = intent ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            request.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            request.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
        val resultCode = request.getIntExtra(EXTRA_RESULT_CODE, 0)
        if (projectionData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val config = CaptureConfig(
            width = request.getIntExtra(EXTRA_WIDTH, 1920),
            height = request.getIntExtra(EXTRA_HEIGHT, 1080),
            fps = request.getIntExtra(EXTRA_FPS, 30),
            videoBitrate = request.getIntExtra(EXTRA_BITRATE, 6_000_000),
            recordAudio = request.getBooleanExtra(EXTRA_RECORD_AUDIO, true),
            contentMode = runCatching {
                ContentMode.valueOf(
                    request.getStringExtra(EXTRA_CONTENT_MODE) ?: ContentMode.Fit.name,
                )
            }.getOrDefault(ContentMode.Fit),
        )

        CaptureEngine.startSession(
            context = this,
            resultCode = resultCode,
            projectionData = projectionData,
            config = config,
            autoRecord = request.getBooleanExtra(EXTRA_AUTO_RECORD, false),
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (CaptureEngine.sessionActive.value) {
            CaptureEngine.closeSession()
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(state: RecordingState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = when (state) {
            RecordingState.Preparing -> "Menyiapkan tangkap layar"
            RecordingState.Previewing -> "Pratinjau aktif, siap merekam"
            is RecordingState.Recording -> "Merekam layar"
            is RecordingState.Error -> state.message
            RecordingState.Idle -> "Tidak ada rekaman"
        }
        val actionLabel = if (state is RecordingState.Recording) "Stop" else "Tutup"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RoutAir")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, actionLabel, stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Perekaman layar",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Notifikasi selama RoutAir merekam layar"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        const val ACTION_START = "com.rout.air.action.START"
        const val ACTION_STOP = "com.rout.air.action.STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_RECORD_AUDIO = "record_audio"
        const val EXTRA_CONTENT_MODE = "content_mode"
        const val EXTRA_AUTO_RECORD = "auto_record"

        private const val CHANNEL_ID = "rout_air_capture"
        private const val NOTIFICATION_ID = 1001

        /** Intent siap pakai untuk memulai service dari Activity. */
        fun startIntent(
            context: Context,
            resultCode: Int,
            projectionData: Intent,
            config: CaptureConfig,
            autoRecord: Boolean = false,
        ): Intent = Intent(context, CaptureService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_PROJECTION_DATA, projectionData)
            putExtra(EXTRA_WIDTH, config.width)
            putExtra(EXTRA_HEIGHT, config.height)
            putExtra(EXTRA_FPS, config.fps)
            putExtra(EXTRA_BITRATE, config.videoBitrate)
            putExtra(EXTRA_RECORD_AUDIO, config.recordAudio)
            putExtra(EXTRA_CONTENT_MODE, config.contentMode.name)
            putExtra(EXTRA_AUTO_RECORD, autoRecord)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).apply { action = ACTION_STOP }
    }
}
