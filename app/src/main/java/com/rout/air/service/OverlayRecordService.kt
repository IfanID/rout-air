package com.rout.air.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.rout.air.ui.OverlayAudioPanel
import com.rout.air.ui.theme.RoutAirTheme
import com.rout.air.MainActivity
import com.rout.air.R
import com.rout.air.core.AudioChannelId
import com.rout.air.core.RecordingState
import com.rout.air.data.AudioMixer
import com.rout.air.output.CaptureEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Overlay sistem ala panel perekam:
 * - Tertutup: logo transparan (cyan) yang bisa digeser.
 * - Terbuka: panel berisi header (timer + perkecil + tutup), pratinjau kecil
 *   format lanskap tetap, dan tombol Rekam/Stop.
 */
class OverlayRecordService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var logo: ImageView
    private lateinit var panel: FrameLayout
    private lateinit var preview: TextureView
    private var textureSurface: Surface? = null
    private lateinit var previewBox: FrameLayout
    private lateinit var messageBox: FrameLayout
    private lateinit var vuBox: FrameLayout
    private lateinit var audioCompose: ComposeView
    private val overlayOwner = OverlayViewOwner()
    private lateinit var resizeHandle: ImageView
    private lateinit var action: ImageButton
    private lateinit var timerView: TextView
    private lateinit var params: WindowManager.LayoutParams

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var menuOpen = false
    private var keepScreenOn = false
    private var previewAttached = false
    private var attachedSurface: android.view.Surface? = null

    private val logoPx by lazy { dp(30) }
    private val actionPx by lazy { dp(34) }
    private val headerPx by lazy { dp(34) }
    private val controlsPx by lazy { dp(46) }
    private val sidePadPx by lazy { dp(12) }

    private var logoX = 0
    private var logoY = 0
    private var panelW = 0
    private var panelH = 0
    private var previewH = 0

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    override fun onCreate() {
        super.onCreate()
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        startAsForeground()
        overlayOwner.attach()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { buildOverlay() }.onFailure {
            stopSelf()
            return
        }
        observeState()
        watchPreview()
        running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        overlayOwner.destroy()
        com.rout.air.data.DiagLog.line("OverlayService: onDestroy")
        running.value = false
        detachPreview()
        scope.cancel()
        runCatching { windowManager.removeView(root) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Notifikasi ----------------------------------------------------------

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.overlay_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val closeIntent = PendingIntent.getService(
            this,
            21,
            Intent(this, OverlayRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_title))
            .setContentText(getString(R.string.overlay_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.overlay_close), closeIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // --- Overlay -------------------------------------------------------------

    private fun buildOverlay() {
        root = FrameLayout(this).apply {
            elevation = 0f
            // Compose di dalam Service butuh owner pada pohon view.
            setViewTreeLifecycleOwner(overlayOwner)
            setViewTreeSavedStateRegistryOwner(overlayOwner)
        }

        logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_logo_mark)
            setColorFilter(Color.parseColor("#00E5FF"))
            contentDescription = getString(R.string.floating_button)
            setOnTouchListener(logoDragListener)
        }
        root.addView(logo, FrameLayout.LayoutParams(logoPx, logoPx).apply {
            gravity = Gravity.TOP or Gravity.START
        })

        panel = FrameLayout(this).apply {
            background = panelBackground()
            elevation = 0f
            visibility = View.GONE
        }
        root.addView(panel, FrameLayout.LayoutParams(0, 0).apply {
            gravity = Gravity.TOP or Gravity.START
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
            setOnTouchListener(panelDragListener)
        }
        val dot = View(this).apply {
            background = circleBackground(Color.parseColor("#E53935"))
        }
        header.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            marginEnd = dp(6)
        })

        timerView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            text = "00:00"
        }
        header.addView(timerView)

        header.addView(headerIcon(R.drawable.ic_person), LinearLayout.LayoutParams(dp(12), dp(12)).apply {
            marginStart = dp(12)
        })
        header.addView(headerLabel("0"))

        header.addView(headerIcon(R.drawable.ic_signal), LinearLayout.LayoutParams(dp(12), dp(12)).apply {
            marginStart = dp(12)
        })
        val kbps = com.rout.air.data.SettingsStore(this).load().videoBitrate / 1000
        header.addView(headerLabel("$kbps kbps"))

        // Dorong ikon kanan ke ujung.
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        fun addHeaderButton(view: View) {
            (view as ImageButton).setPadding(dp(7), dp(7), dp(7), dp(7))
            header.addView(view, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginStart = dp(2)
            })
        }
        // Hanya tombol tutup di header.
        addHeaderButton(headerButton(R.drawable.ic_overlay_close, getString(R.string.overlay_close)) {
            setMenuOpen(false)
        })

        panel.addView(header, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            headerPx,
        ).apply {
            gravity = Gravity.TOP
        })

        previewBox = FrameLayout(this).apply {
            elevation = 0f
            clipToOutline = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.BLACK)
            }
        }
        preview = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    surfaceTexture.setDefaultBufferSize(
                        (panelW - sidePadPx * 2).coerceAtLeast(1),
                        previewH.coerceAtLeast(1),
                    )
                    textureSurface?.release()
                    textureSurface = Surface(surfaceTexture)
                    maybeAttach()
                }

                override fun onSurfaceTextureSizeChanged(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) = Unit

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    detachPreview()
                    textureSurface?.release()
                    textureSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
        }
        previewBox.addView(preview, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        panel.addView(previewBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply {
            topMargin = headerPx + dp(4)
            bottomMargin = controlsPx + dp(6)
            leftMargin = sidePadPx
            rightMargin = sidePadPx
            gravity = Gravity.TOP
        })

        // Panel pesan (placeholder) dan panel VU audio.
        messageBox = FrameLayout(this).apply {
            background = boxBackground()
            visibility = View.GONE
            val title = TextView(this@OverlayRecordService).apply {
                text = getString(R.string.overlay_chat)
                setTextColor(Color.WHITE)
                textSize = 13f
            }
            val body = TextView(this@OverlayRecordService).apply {
                text = getString(R.string.overlay_no_messages)
                setTextColor(Color.parseColor("#B8C2CC"))
                textSize = 12f
            }
            addView(title, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(12)
                topMargin = dp(10)
            })
            addView(body, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
            })
        }
        panel.addView(messageBox, contentParams())

        vuBox = FrameLayout(this).apply {
            background = boxBackground()
            visibility = View.GONE
            audioCompose = ComposeView(this@OverlayRecordService).apply {
                setContent { RoutAirTheme { OverlayAudioPanel() } }
            }
            addView(audioCompose, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        panel.addView(vuBox, contentParams())

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(26), 0)
        }
        action = ImageButton(this).apply {
            stateListAnimator = null
            elevation = 0f
            background = circleBackground(Color.parseColor("#00E5FF"))
            setImageResource(R.drawable.ic_record)
            setColorFilter(Color.WHITE)
            contentDescription = getString(R.string.floating_record)
            setOnClickListener {
                val state = CaptureEngine.state.value
                when {
                    state is RecordingState.Recording -> CaptureEngine.stopRecording()
                    !CaptureEngine.sessionActive.value -> openApp()
                    else -> CaptureEngine.startRecording()
                }
            }
        }
        controls.gravity = Gravity.CENTER_VERTICAL

        fun addCell(view: View, size: Int = dp(0)) {
            (view as? ImageButton)?.apply {
                if (size > 0) {
                    // Tombol rekam: ikon hampir memenuhi lingkaran.
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                } else {
                    setPadding(dp(7), dp(10), dp(7), dp(10))
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            val cell = FrameLayout(this)
            if (size > 0) {
                // Tombol bulat (rekam) tetap ukuran tetap, sel tetap clickable.
                cell.addView(view, FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER
                })
                cell.setOnClickListener { view.performClick() }
                cell.isClickable = true
            } else {
                // Ikon biasa mengisi seluruh sel agar mudah ditekan.
                cell.addView(view, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ))
            }
            controls.addView(cell, LinearLayout.LayoutParams(0, controlsPx, 1f))
        }

        fun addEmptyCell() {
            controls.addView(FrameLayout(this), LinearLayout.LayoutParams(0, controlsPx, 1f))
        }

        // 1. Rekam/Stop, 2. kosong, 3. kotak isi mata, 4. pesan,
        // 5. VU meter audio, 6-8 kosong, 9. buka Studio.
        addCell(action, dp(32))
        addEmptyCell()
        addCell(
            toolButton(R.drawable.ic_eye_box, getString(R.string.overlay_preview)) {
                showContent(ContentMode.Preview)
            },
        )
        addCell(
            toolButton(R.drawable.ic_chat, getString(R.string.overlay_chat)) {
                showContent(ContentMode.Messages)
            },
        )
        addCell(
            toolButton(R.drawable.ic_volume, getString(R.string.overlay_master)) {
                showContent(ContentMode.Vu)
            },
        )
        addEmptyCell()
        addEmptyCell()
        addEmptyCell()
        addCell(
            toolButton(R.drawable.ic_studio, getString(R.string.overlay_studio)) { openStudio() },
        )

        panel.addView(controls, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            controlsPx,
        ).apply {
            gravity = Gravity.BOTTOM
        })

        // Pegangan resize di pojok kanan bawah.
        resizeHandle = ImageView(this).apply {
            setImageResource(R.drawable.ic_resize)
            setColorFilter(Color.WHITE)
            alpha = 0.8f
            contentDescription = getString(R.string.overlay_resize)
            setOnTouchListener(resizeTouchListener)
        }
        panel.addView(resizeHandle, FrameLayout.LayoutParams(dp(22), dp(22)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = dp(4)
            bottomMargin = dp(4)
        })

        val screenH = resources.displayMetrics.heightPixels
        logoX = dp(10)
        logoY = (screenH / 4).coerceAtLeast(dp(80))

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = logoX
            y = logoY
        }
        windowManager.addView(root, params)
    }

    private fun headerIcon(res: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        alpha = 0.85f
    }

    private fun headerLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#DCE3EA"))
        textSize = 11f
        setPadding(dp(4), 0, 0, 0)
    }

    private fun headerButton(res: Int, desc: String, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            stateListAnimator = null
            elevation = 0f
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(res)
            setColorFilter(Color.WHITE)
            contentDescription = desc
            alpha = 0.9f
            setOnClickListener { onClick() }
        }

    private fun toolButton(res: Int, desc: String, onClick: (() -> Unit)?): ImageButton =
        ImageButton(this).apply {
            stateListAnimator = null
            elevation = 0f
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(res)
            setColorFilter(Color.WHITE)
            contentDescription = desc
            alpha = if (onClick == null) 0.45f else 0.9f
            if (onClick != null) setOnClickListener { onClick() }
        }

    private fun panelBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#B8243A55"))
        }

    private fun contentParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply {
            topMargin = headerPx + dp(4)
            bottomMargin = controlsPx + dp(6)
            leftMargin = sidePadPx
            rightMargin = sidePadPx
            gravity = Gravity.TOP
        }

    private fun boxBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(Color.BLACK)
    }

    private fun circleBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    /**
     * Ukuran panel tetap (lanskap) di kedua orientasi, seperti panel perekam.
     * Tombol aksi berada di baris terpisah di bawah pratinjau.
     */
    /** Lebar minimum: ikut foto (±40% layar) dan cukup untuk header + ✕. */
    private fun minPanelWidthPx(): Int {
        val screenW = resources.displayMetrics.widthPixels
        return maxOf(dp(200), (screenW * 0.42f).roundToInt())
    }

    private fun computePanelSize() {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val base = min(screenW, screenH)
        val size = OverlayLayout.panelSize(
            requestedWidth = if (panelW > 0) panelW else (base * 0.80f).roundToInt(),
            screenWidth = screenW,
            screenHeight = screenH,
            sidePad = sidePadPx,
            header = headerPx,
            controls = controlsPx,
            gapTop = dp(4),
            gapBottom = dp(6),
            minWidth = minPanelWidthPx(),
            availableWidth = resources.displayMetrics.widthPixels - logoX,
            availableHeight = resources.displayMetrics.heightPixels - logoY,
        )
        panelW = size.panelWidth
        previewH = size.previewHeight
        panelH = size.panelHeight
        (panel.layoutParams as FrameLayout.LayoutParams).apply {
            width = panelW
            height = panelH
        }
        panel.requestLayout()
        previewBox.requestLayout()
    }

    // --- Geser ---------------------------------------------------------------

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    private val logoDragListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = logoX
                startY = logoY
                moved = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (abs(dx) > dp(5) || abs(dy) > dp(5)) moved = true
                if (moved) {
                    logoX = startX + dx.roundToInt()
                    logoY = startY + dy.roundToInt()
                    applyPosition(panelW, panelH)
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                if (!moved) setMenuOpen(true)
                true
            }

            else -> false
        }
    }

    private val panelDragListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = logoX
                startY = logoY
                true
            }

            MotionEvent.ACTION_MOVE -> {
                logoX = startX + (event.rawX - downRawX).roundToInt()
                logoY = startY + (event.rawY - downRawY).roundToInt()
                applyPosition(panelW, panelH)
                true
            }

            else -> false
        }
    }

    private var resizeDownX = 0f
    private var resizeDownY = 0f
    private var resizeStartW = 0
    private var lastPreviewSync = 0L

    private val resizeTouchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeDownX = event.rawX
                resizeDownY = event.rawY
                resizeStartW = panelW
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - resizeDownX
                val dy = event.rawY - resizeDownY
                resizePanel(
                    OverlayLayout.widthFromDrag(resizeStartW, dx, dy).toFloat(),
                    commit = false,
                )
                true
            }

            MotionEvent.ACTION_UP -> {
                resizePanel(panelW.toFloat(), commit = true)
                true
            }

            else -> false
        }
    }

    /** Ubah ukuran panel; commit=true saat jari dilepas. */
    private fun resizePanel(newWidth: Float, commit: Boolean) {
        val size = OverlayLayout.panelSize(
            requestedWidth = newWidth.roundToInt(),
            screenWidth = resources.displayMetrics.widthPixels,
            screenHeight = resources.displayMetrics.heightPixels,
            sidePad = sidePadPx,
            header = headerPx,
            controls = controlsPx,
            gapTop = dp(4),
            gapBottom = dp(6),
            minWidth = minPanelWidthPx(),
        )
        panelW = size.panelWidth
        previewH = size.previewHeight
        panelH = size.panelHeight
        (panel.layoutParams as FrameLayout.LayoutParams).apply {
            width = panelW
            height = panelH
        }
        panel.requestLayout()
        // Jangan geser posisi saat drag agar pegangan tidak "kabur".
        if (commit) {
            applyPosition(panelW, panelH)
        } else {
            params.x = logoX
            params.y = logoY
            runCatching { windowManager.updateViewLayout(root, params) }
        }

        val w = (panelW - sidePadPx * 2).coerceAtLeast(1)
        val now = android.os.SystemClock.uptimeMillis()
        // Ikutkan ukuran pratinjau secara berkala saat drag agar isi
        // (termasuk video vertikal) benar-benar mengikuti resize.
        if (commit || now - lastPreviewSync > 60) {
            lastPreviewSync = now
            preview.surfaceTexture?.setDefaultBufferSize(w, previewH.coerceAtLeast(1))
            if (previewAttached) {
                CaptureEngine.attachOverlayPreview(textureSurface, w, previewH)
            }
        }
    }

    private fun applyPosition(width: Int, height: Int) {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        logoX = logoX.coerceIn(0, (screenW - width).coerceAtLeast(0))
        logoY = logoY.coerceIn(0, (screenH - height).coerceAtLeast(0))
        params.x = logoX
        params.y = logoY
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    private fun setMenuOpen(open: Boolean) {
        menuOpen = open
        if (open) {
            computePanelSize()
            logo.visibility = View.GONE
            panel.visibility = View.VISIBLE
            applyPosition(panelW, panelH)
            maybeAttach()
        } else {
            // Simpan posisi panel agar logo muncul di tempat yang sama.
            applyPosition(logoPx, logoPx)
            panel.visibility = View.GONE
            logo.visibility = View.VISIBLE
            detachPreview()
        }
    }

    // --- Pratinjau -----------------------------------------------------------

    private fun attachPreview() {
        if (previewAttached) return
        if (!menuOpen) return
        val surface = textureSurface ?: return
        if (!surface.isValid) return
        CaptureEngine.setPreviewVisible(true)
        attachedSurface = surface
        CaptureEngine.attachOverlayPreview(surface, panelW - sidePadPx * 2, previewH)
        previewAttached = true
    }

    private fun detachPreview() {
        if (!previewAttached) return
        CaptureEngine.detachOverlayPreview()
        attachedSurface = null
        if (!MainActivity.visible) CaptureEngine.setPreviewVisible(false)
        previewAttached = false
    }

    private fun maybeAttach() {
        if (menuOpen) attachPreview()
    }

    private fun watchPreview() {
        scope.launch {
            while (true) {
                delay(500)
                if (menuOpen) {
                    maybeAttach()
                    updateTimer()
                }
            }
        }
    }

    private fun updateTimer() {
        val state = CaptureEngine.state.value
        val elapsed = if (state is RecordingState.Recording) {
            ((System.currentTimeMillis() - state.startedAtMs) / 1000).coerceAtLeast(0)
        } else {
            0
        }
        timerView.text = "%02d:%02d".format(elapsed / 60, elapsed % 60)
    }

    private fun observeState() {
        scope.launch {
            CaptureEngine.state.collect { state ->
                val recording = state is RecordingState.Recording
                // Cegah layar auto-lock selama merekam agar MediaProjection
                // tidak dihentikan sistem (izin tidak hilang).
                if (recording != keepScreenOn) {
                    keepScreenOn = recording
                    params.flags = if (recording) {
                        params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    } else {
                        params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                    }
                    runCatching { windowManager.updateViewLayout(root, params) }
                }
                action.setImageResource(
                    if (recording) R.drawable.ic_stop else R.drawable.ic_record,
                )
                action.background = circleBackground(
                    if (recording) Color.parseColor("#E53935") else Color.parseColor("#00E5FF"),
                )
                action.contentDescription = getString(
                    if (recording) R.string.floating_stop else R.string.floating_record,
                )
                action.isEnabled = state !is RecordingState.Preparing
                action.alpha = if (action.isEnabled) 1f else 0.4f
                updateTimer()
            }
        }
    }

    private enum class ContentMode { Preview, Messages, Vu }

    /** Ganti isi panel overlay: pratinjau, pesan, atau VU audio. */
    private fun showContent(mode: ContentMode) {
        previewBox.visibility = if (mode == ContentMode.Preview) View.VISIBLE else View.GONE
        messageBox.visibility = if (mode == ContentMode.Messages) View.VISIBLE else View.GONE
        vuBox.visibility = if (mode == ContentMode.Vu) View.VISIBLE else View.GONE
        if (mode == ContentMode.Preview) maybeAttach() else detachPreview()
    }

    private fun openStudio() {
        com.rout.air.data.UiEvents.openStudio.tryEmit(Unit)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("open_tab", "studio")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    companion object {
        private const val CHANNEL_ID = "routair_overlay"
        private const val NOTIFICATION_ID = 21
        const val ACTION_STOP = "com.rout.air.action.OVERLAY_STOP"

        val running = MutableStateFlow(false)

        fun start(context: Context) {
            val intent = Intent(context, OverlayRecordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayRecordService::class.java))
        }
    }
}
