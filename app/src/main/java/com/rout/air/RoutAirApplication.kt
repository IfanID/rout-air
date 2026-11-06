package com.rout.air

import android.app.Application
import android.content.ContentValues
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.rout.air.data.logging.DiagLog
import android.provider.MediaStore
import com.rout.air.encode.video.OutputTarget

/** Application yang mencatat crash ke Download/RoutAir/RoutAir_crash.txt. */
class RoutAirApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(error) }
            previous?.uncaughtException(thread, error)
        }
        // Rekam jejak crash ke log/crash.txt sebelum proses dimatikan.
        com.rout.air.data.logging.CrashLog.install(this)
        // Tema (aksen & AMOLED) dibaca sekali di awal proses.
        com.rout.air.ui.theme.Appearance.attach(this)
        // Teks untuk kelas non-UI (encoder, jaringan) memakai context aplikasi.
        com.rout.air.data.common.Msg.attach(this)
        // Satu berkas log per sesi aplikasi; ditimpa setiap aplikasi dibuka.
        DiagLog.startSession(this)
        installAnrWatchdog()
        // File pending dari sesi yang mati tanpa menutup MP4 tidak terlihat di
        // galeri; bersihkan di latar belakang agar tidak menumpuk.
        Thread({ runCatching { OutputTarget.cleanupOrphans(this) } }, "rout-cleanup").start()
    }

    /**
     * Catat stack trace main thread bila UI berhenti merespons, supaya freeze
     * yang tidak menghasilkan crash tetap bisa didiagnosis dari DiagLog.
     */
    private fun installAnrWatchdog() {
        val main = Handler(Looper.getMainLooper())
        val beat = java.util.concurrent.atomic.AtomicLong(SystemClock.uptimeMillis())
        val heartbeat = object : Runnable {
            override fun run() {
                beat.set(SystemClock.uptimeMillis())
                main.postDelayed(this, 1_000)
            }
        }
        main.post(heartbeat)

        val watchdog = Handler(HandlerThread("rout-anr").apply { start() }.looper)
        watchdog.post(object : Runnable {
            override fun run() {
                val lag = SystemClock.uptimeMillis() - beat.get()
                if (lag > 4_000) {
                    DiagLog.line("ANR: main thread macet ${lag}ms")
                    Looper.getMainLooper().thread.stackTrace.take(40)
                        .forEach { DiagLog.line("  at $it") }
                    DiagLog.flush(this@RoutAirApplication)
                    beat.set(SystemClock.uptimeMillis())
                }
                watchdog.postDelayed(this, 2_000)
            }
        })
    }

    private fun writeCrash(error: Throwable) {
        val text = buildString {
            append("RoutAir crash\n")
            append(error.stackTraceToString())
            append("\n\nPenyebab:\n")
            var cause = error.cause
            while (cause != null) {
                append(cause.stackTraceToString()).append('\n')
                cause = cause.cause
            }
        }
        val resolver = contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "RoutAir_crash.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/RoutAir",
            )
        }
        val uri = resolver.insert(collection, values) ?: return
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
    }
}
