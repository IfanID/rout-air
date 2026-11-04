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
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
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
import android.widget.RemoteViews
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rout.air.live.prepareYouTubeBroadcast
import com.rout.air.live.ChatBubble
import com.rout.air.live.ChatMessage
import com.rout.air.live.VERIFIED_COLOR
import com.rout.air.live.bubbleDurationMs
import com.rout.air.live.emoji
import com.rout.air.live.nameColor
import com.rout.air.ui.ChatBubbleStack
import com.rout.air.ui.LiveSetupCard
import com.rout.air.ui.OverlayAudioPanel
import com.rout.air.ui.OverlayScenePanel
import com.rout.air.ui.OverlayScreensaverPanel
import com.rout.air.ui.theme.RoutAirTheme
import com.rout.air.MainActivity
import com.rout.air.R
import com.rout.air.core.AudioChannelId
import com.rout.air.core.RecordingState
import com.rout.air.data.AudioMixer
import com.rout.air.data.SceneManager
import com.rout.air.output.CaptureEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Overlay live streaming ala panel perekam:
 * - Tertutup: logo transparan (cyan) yang bisa digeser.
 * - Terbuka: panel berisi header (timer + status), pratinjau kecil, panel
 *   pesan/chat, VU audio, dan tombol Rekam/Stop.
 *
 * Untuk perekaman dipakai [OverlayRecordingService].
 */
class OverlayLiveService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var logo: ImageView
    private lateinit var panel: FrameLayout
    private lateinit var preview: TextureView
    private var textureSurface: Surface? = null
    private lateinit var previewBox: FrameLayout
    private lateinit var messageBox: FrameLayout
    private lateinit var chatBody: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var chatJumpButton: ImageButton
    private lateinit var viewerLabel: TextView
    private lateinit var signalLabel: TextView
    private lateinit var bubbleCompose: ComposeView
    private lateinit var screensaverButton: ImageButton
    private var setupWindow: ComposeView? = null
    private lateinit var vuBox: FrameLayout
    private lateinit var jedaBox: FrameLayout
    private lateinit var sceneBox: FrameLayout
    private lateinit var audioCompose: ComposeView
    private val overlayOwner = OverlayViewOwner()
    private lateinit var resizeHandle: ImageView
    private lateinit var action: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var bubbleToggle: ImageButton
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
    private lateinit var logoParams: FrameLayout.LayoutParams
    private var panelW = 0
    private var panelH = 0
    private var previewH = 0

    /** Bubble chat yang tampil di bawah logo; maksimal [MAX_BUBBLES]. */
    private val bubbleState = mutableStateOf<List<ChatBubble>>(emptyList())
    /** Timer per bubble; dipakai ulang saat bubble diketuk atau pesan berulang. */
    private val bubbleJobs = mutableMapOf<String, Job>()
    /** Bubble bisa disembunyikan tanpa menutup panel obrolan. */
    private var bubblesEnabled = true
    private val seenBubbleIds = LinkedHashSet<String>()
    /** Pesan lama saat siaran baru terdeteksi tidak ikut muncul sebagai bubble. */
    private var bubblePrimed = false
    /** Penanda transisi live -> tidak live untuk membersihkan bubble. */
    private var chatWasLive = false
    private val bubbleGapPx by lazy { dp(6) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    override fun onCreate() {
        super.onCreate()
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        // Pastikan koleksi scene tersedia walau Activity sudah dimatikan.
        runCatching { SceneManager.attach(this) }
        // Overlay bisa berjalan tanpa Activity, jadi mixer perlu disiapkan.
        runCatching { AudioMixer.attach(this) }
        runCatching { com.rout.air.data.ScreensaverStore.attach(this) }
        startAsForeground()
        overlayOwner.attach()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { buildOverlay() }.onFailure {
            stopSelf()
            return
        }
        observeState()
        observeChat()
        observeMixer()
        observeScreensaver()
        observeLive()
        startTicker()
        watchPreview()
        com.rout.air.live.ChatMonitor.start(this)
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
        com.rout.air.live.ChatMonitor.stop()
        com.rout.air.data.DiagLog.line("OverlayLiveService: onDestroy")
        running.value = false
        hideLiveSetupOverlay()
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
            Intent(this, OverlayLiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Tombol tutup ditaruh di dalam layout agar terlihat tanpa perlu
        // memperluas notifikasi: cukup satu ketukan.
        val views = RemoteViews(packageName, R.layout.notification_overlay).apply {
            setTextViewText(R.id.notification_title, getString(R.string.overlay_title))
            setTextViewText(R.id.notification_text, getString(R.string.overlay_text))
            setTextViewText(R.id.notification_close, getString(R.string.overlay_close))
            setOnClickPendingIntent(R.id.notification_root, openApp)
            setOnClickPendingIntent(R.id.notification_close, closeIntent)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openApp)
            // Decorated style memakai content view yang sama saat ringkas
            // maupun diperluas, jadi tombol tutup tidak hilang.
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(views)
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
        logoParams = FrameLayout.LayoutParams(logoPx, logoPx).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        root.addView(logo, logoParams)

        bubbleCompose = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                RoutAirTheme {
                    ChatBubbleStack(
                        bubbles = bubbleState.value,
                        onGone = { id -> removeBubble(id) },
                        onTap = { id -> extendBubble(id) },
                    )
                }
            }
        }
        root.addView(
            bubbleCompose,
            FrameLayout.LayoutParams(bubbleWidthPx(false), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = logoPx + bubbleGapPx
            },
        )

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
        viewerLabel = headerLabel("0")
        header.addView(viewerLabel)

        header.addView(headerIcon(R.drawable.ic_signal), LinearLayout.LayoutParams(dp(12), dp(12)).apply {
            marginStart = dp(12)
        })
        signalLabel = headerLabel("-- kbps")
        header.addView(signalLabel)

        // Dorong ikon kanan ke ujung.
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        fun addHeaderButton(view: View) {
            (view as ImageButton).setPadding(dp(7), dp(7), dp(7), dp(7))
            header.addView(view, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginStart = dp(2)
            })
        }
        // Bubble chat bisa disembunyikan tanpa menutup panel obrolan.
        bubbleToggle = headerButton(
            R.drawable.ic_chat,
            getString(R.string.overlay_bubbles_hide),
        ) {
            bubblesEnabled = !bubblesEnabled
            updateBubbleToggle()
            updateBubbleVisibility()
        }
        addHeaderButton(bubbleToggle)
        updateBubbleToggle()
        // Tombol tutup di header.
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

        // Panel obrolan: judul tetap di atas, isi memenuhi sisa panel dan
        // bisa digulir bila pesan lebih banyak dari tinggi panel.
        messageBox = FrameLayout(this).apply {
            background = boxBackground()
            visibility = View.GONE
            val title = TextView(this@OverlayLiveService).apply {
                text = getString(R.string.overlay_chat)
                setTextColor(Color.parseColor("#DCE3EA"))
                textSize = 12f
                setPadding(dp(14), dp(10), dp(14), dp(8))
            }
            chatBody = TextView(this@OverlayLiveService).apply {
                text = getString(R.string.overlay_no_messages)
                setTextColor(Color.parseColor("#E6EAEE"))
                textSize = 12f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(14), dp(10), dp(14), dp(12))
            }
            chatScroll = ScrollView(this@OverlayLiveService).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = true
                addView(
                    chatBody,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            // Tombol ke pesan terbaru: hanya terlihat saat digulir ke atas.
            chatJumpButton = ImageButton(this@OverlayLiveService).apply {
                stateListAnimator = null
                elevation = 0f
                setBackgroundColor(Color.TRANSPARENT)
                setImageResource(R.drawable.ic_arrow_down)
                setColorFilter(Color.parseColor("#00E5FF"))
                contentDescription = getString(R.string.overlay_chat_latest)
                setPadding(dp(5), dp(5), dp(5), dp(5))
                visibility = View.GONE
                setOnClickListener {
                    chatScroll.smoothScrollTo(0, chatBody.height)
                    visibility = View.GONE
                }
            }
            chatScroll.setOnScrollChangeListener { _, _, _, _, _ -> updateChatJumpButton() }
            addView(title, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            })
            addView(View(this@OverlayLiveService).apply {
                setBackgroundColor(Color.parseColor("#1FFFFFFF"))
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(1),
            ).apply {
                gravity = Gravity.TOP
                topMargin = dp(34)
            })
            addView(chatScroll, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                gravity = Gravity.TOP
                topMargin = dp(35)
            })
            addView(chatJumpButton, FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(3)
                marginEnd = dp(8)
            })
        }
        panel.addView(messageBox, contentParams())

        vuBox = FrameLayout(this).apply {
            background = boxBackground()
            visibility = View.GONE
            audioCompose = ComposeView(this@OverlayLiveService).apply {
                setContent { RoutAirTheme { OverlayAudioPanel() } }
            }
            addView(audioCompose, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        panel.addView(vuBox, contentParams())

        jedaBox = FrameLayout(this).apply {
            background = boxBackground()
            visibility = View.GONE
            addView(
                ComposeView(this@OverlayLiveService).apply {
                    setContent {
                        RoutAirTheme {
                            OverlayScreensaverPanel()
                        }
                    }
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        panel.addView(jedaBox, contentParams())

        sceneBox = FrameLayout(this).apply {
            background = boxBackground()
            visibility = View.GONE
            addView(
                ComposeView(this@OverlayLiveService).apply {
                    setContent {
                        RoutAirTheme {
                            OverlayScenePanel(
                                onPick = { id -> SceneManager.switchProgramScene(id) },
                            )
                        }
                    }
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        panel.addView(sceneBox, contentParams())

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(26), 0)
        }
        action = ImageButton(this).apply {
            stateListAnimator = null
            elevation = 0f
            background = circleBackground(Color.parseColor("#00E5FF"))
            setImageResource(R.drawable.ic_live)
            setColorFilter(Color.WHITE)
            contentDescription = getString(R.string.overlay_live_start)
            setOnClickListener {
                when {
                    CaptureEngine.liveStats.value.active -> CaptureEngine.stopLive()
                    // Di dalam RoutAir: dialog muncul di jendela aplikasi.
                    MainActivity.visible -> {
                        com.rout.air.data.UiEvents.openLiveSetup.tryEmit(Unit)
                        openApp("live")
                    }
                    // Belum login Google: tidak perlu judul/deskripsi.
                    !com.rout.air.live.GoogleAuthStore(this@OverlayLiveService).loggedIn -> startLiveNow()
                    // Di aplikasi lain: dialog melayang di atas layar.
                    else -> showLiveSetupOverlay()
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

        // 1. Rekam/Stop, 2. kosong, 3. pratinjau, 4. obrolan, 5. audio,
        // 6. adegan, 7. mikrofon, 8. layar jeda, 9. buka Studio.
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
        // Slot 6: daftar scene untuk ganti program dari overlay.
        addCell(
            toolButton(R.drawable.ic_grid, getString(R.string.overlay_scenes)) {
                showContent(ContentMode.Scenes)
            },
        )
        // Slot 7: mute mikrofon cepat tanpa membuka panel audio.
        micButton = toolButton(
            R.drawable.ic_mic,
            getString(R.string.overlay_mic_mute),
        ) {
            val channel = AudioMixer.mix.value.channel(AudioChannelId.Microphone)
            AudioMixer.update(channel.copy(muted = !channel.muted))
        }
        addCell(micButton)
        updateMicButton(AudioMixer.mix.value.channel(AudioChannelId.Microphone))
        // Slot 8: panel layar jeda (daftar scene penutup yang tersedia).
        screensaverButton = toolButton(
            R.drawable.ic_screensaver,
            getString(R.string.overlay_screensaver),
        ) {
            showContent(ContentMode.Screensaver)
        }
        addCell(screensaverButton)
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
                // Harus jalur program; kalau tidak, resize mengembalikan
                // pratinjau ke scene edit.
                CaptureEngine.attachOverlayProgram(textureSurface, w, previewH)
            }
        }
    }

    private fun applyPosition(width: Int, height: Int) {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        if (menuOpen) {
            // Panel selalu tumbuh ke kanan dari titik logo.
            logoX = logoX.coerceIn(0, (screenW - width).coerceAtLeast(0))
            logoY = logoY.coerceIn(0, (screenH - height).coerceAtLeast(0))
            params.x = logoX
            params.y = logoY
            runCatching { windowManager.updateViewLayout(root, params) }
            return
        }

        // Sisi bubble mengikuti posisi logo: logo di kiri -> bubble ke kanan,
        // logo di kanan -> bubble ke kiri agar tidak keluar layar.
        val logoCenter = logoX + logoPx / 2f
        val bubbleOnRight = logoCenter <= screenW / 2f
        val bubbleW = bubbleWidthPx(bubbleOnRight)
        (bubbleCompose.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.width != bubbleW) {
                lp.width = bubbleW
                bubbleCompose.layoutParams = lp
            }
        }

        // Jendela selebar bubble; logo digeser ke tepi kanan jendela saat
        // bubble berada di kiri.
        val windowW = maxOf(logoPx, bubbleW)
        val marginLeft = if (bubbleOnRight) 0 else windowW - logoPx
        if (logoParams.leftMargin != marginLeft) {
            logoParams.leftMargin = marginLeft
            logo.layoutParams = logoParams
        }

        // Sisakan ruang bawah untuk bubble yang sedang tampil.
        val reserve = bubbleReservePx()
        if (bubbleOnRight) {
            logoX = logoX.coerceIn(0, (screenW - windowW).coerceAtLeast(0))
        } else {
            logoX = logoX.coerceIn(
                marginLeft,
                (screenW - logoPx).coerceAtLeast(0),
            )
        }
        logoY = logoY.coerceIn(0, (screenH - logoPx - reserve).coerceAtLeast(0))

        params.x = logoX - marginLeft
        params.y = logoY
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    /** Perkiraan tinggi bubble yang sedang tampil agar jendela tidak terpotong. */
    private fun bubbleReservePx(): Int {
        val count = bubbleState.value.count { !it.leaving }
        if (count == 0) return 0
        return count * dp(58) + (count - 1) * bubbleGapPx
    }

    private fun setMenuOpen(open: Boolean) {
        menuOpen = open
        if (open) {
            computePanelSize()
            logo.visibility = View.GONE
            bubbleCompose.visibility = View.GONE
            panel.visibility = View.VISIBLE
            applyPosition(panelW, panelH)
            maybeAttach()
        } else {
            // Simpan posisi panel agar logo muncul di tempat yang sama.
            applyPosition(logoPx, logoPx)
            panel.visibility = View.GONE
            logo.visibility = View.VISIBLE
            updateBubbleVisibility()
            updateChatJumpButton()
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
        // Saat live, pratinjau overlay menampilkan program yang mengudara
        // (termasuk layar jeda), bukan scene hasil edit.
        CaptureEngine.attachOverlayProgram(surface, panelW - sidePadPx * 2, previewH)
        previewAttached = true
    }

    private fun detachPreview() {
        if (!previewAttached) return
        CaptureEngine.detachOverlayProgram()
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
        val live = CaptureEngine.liveStats.value
        if (live.active) {
            val seconds = (live.uptimeMs / 1000).coerceAtLeast(0)
            timerView.text = formatElapsed(seconds)
            return
        }
        val state = CaptureEngine.state.value
        val elapsed = if (state is RecordingState.Recording) {
            if (state.isPaused) {
                (state.pausedAtMs ?: 0L) / 1000
            } else {
                ((System.currentTimeMillis() - state.startedAtMs - state.pausedTotalMs) / 1000)
                    .coerceAtLeast(0)
            }
        } else {
            0
        }
        timerView.text = formatElapsed(elapsed)
    }

    /** MM:SS, atau H:MM:SS setelah siaran melewati satu jam. */
    private fun formatElapsed(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val rest = seconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, rest)
        } else {
            "%02d:%02d".format(minutes, rest)
        }
    }

    private fun observeChat() {
        scope.launch {
            com.rout.air.live.ChatMonitor.state.collect { chat ->
                viewerLabel.text = if (chat.viewers >= 0) chat.viewers.toString() else "–"
                if (!chat.live) {
                    // Siaran berakhir: bubble lama dibuang dan id dilupakan.
                    if (chatWasLive) {
                        chatWasLive = false
                        bubblePrimed = false
                        seenBubbleIds.clear()
                        bubbleJobs.values.forEach { it.cancel() }
                        bubbleJobs.clear()
                        bubbleState.value = emptyList()
                    }
                } else {
                    chatWasLive = true
                    syncBubbles(chat.messages)
                }
                renderChat(chat.messages, chat.status)
                updateBubbleVisibility()
            }
        }
    }

    /** Isi panel Obrolan: pesan terakhir dari chat YouTube. */
    private fun renderChat(messages: List<ChatMessage>, status: String) {
        val shown = messages.takeLast(CHAT_LINES)
        // Tetap di bawah bila pengguna belum menggulir ke atas.
        val stickToBottom = chatScroll.height == 0 ||
            chatScroll.scrollY + chatScroll.height >= chatBody.height - dp(24)
        chatBody.text = when {
            shown.isNotEmpty() -> chatSpans(shown)
            status.isNotBlank() && status != "Terhubung" -> status
            com.rout.air.live.ChatMonitor.state.value.live ->
                getString(R.string.overlay_chat_waiting)
            status.isNotBlank() -> status
            else -> getString(R.string.overlay_no_messages)
        }
        if (stickToBottom) {
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
        chatScroll.post { updateChatJumpButton() }
    }

    /** Tombol panah hanya tampil saat isi obrolan tidak berada di dasar. */
    private fun updateChatJumpButton() {
        if (!::chatJumpButton.isInitialized || !::chatScroll.isInitialized) return
        val atBottom = chatBody.height == 0 ||
            chatScroll.scrollY + chatScroll.height >= chatBody.height - dp(24)
        val show = !atBottom && menuOpen && messageBox.visibility == View.VISIBLE
        chatJumpButton.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Bubble tampil saat live atau saat ada pesan uji di layar. */
    private fun updateBubbleVisibility() {
        val visible = bubblesEnabled && !menuOpen && (
            com.rout.air.live.ChatMonitor.state.value.live || bubbleState.value.isNotEmpty()
            )
        bubbleCompose.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Ikon header mencerminkan bubble sedang tampil atau disembunyikan. */
    private fun updateBubbleToggle() {
        bubbleToggle.alpha = if (bubblesEnabled) 0.9f else 0.4f
        bubbleToggle.contentDescription = getString(
            if (bubblesEnabled) R.string.overlay_bubbles_hide else R.string.overlay_bubbles_show,
        )
    }

    /** Sakelar mikrofon di baris kontrol overlay. */
    private fun updateMicButton(channel: com.rout.air.core.AudioChannel) {
        micButton.setImageResource(
            if (channel.muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
        )
        micButton.setColorFilter(
            if (channel.muted) Color.parseColor("#FF6B6B") else Color.WHITE,
        )
        micButton.contentDescription = getString(
            if (channel.muted) R.string.overlay_mic_unmute else R.string.overlay_mic_mute,
        )
    }

    /** Ikuti perubahan mixer agar ikon mikrofon selalu sesuai keadaan. */
    private fun observeMixer() {
        scope.launch {
            AudioMixer.mix.collect { mix ->
                updateMicButton(mix.channel(AudioChannelId.Microphone))
            }
        }
    }

    /** Mencatat pesan baru sebagai bubble; pesan lama saat baru tersambung dilewati. */
    private fun syncBubbles(messages: List<ChatMessage>) {
        val fresh = messages.filter { it.id.isNotBlank() }
        if (!bubblePrimed) {
            bubblePrimed = true
            fresh.forEach { rememberBubbleId(it.id) }
            return
        }
        fresh.forEach { message ->
            if (rememberBubbleId(message.id)) showBubble(message)
        }
    }

    /** true bila id ini belum pernah tampil. */
    private fun rememberBubbleId(id: String): Boolean {
        if (!seenBubbleIds.add(id)) return false
        if (seenBubbleIds.size > SEEN_LIMIT) {
            val oldest = seenBubbleIds.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        return true
    }

    private fun showBubble(message: ChatMessage) {
        // Pesan sama dari penulis sama tidak menumpuk: bubble lama dipakai
        // ulang, dipindah ke atas, dan durasinya diperpanjang.
        val duplicate = bubbleState.value.firstOrNull {
            !it.leaving && it.author == message.author && it.text == message.text
        }
        val id = duplicate?.id ?: message.id
        val bubble = ChatBubble(
            id = id,
            author = message.author,
            text = message.text,
            role = message.role,
            verified = message.verified,
        )
        // Bubble baru di atas; hanya [MAX_BUBBLES] yang terlihat. Pesan ke-4
        // mendorong yang paling bawah keluar, seperti chat YouTube.
        var visible = 0
        val rest = bubbleState.value.filterNot { it.id == id }
        bubbleState.value = (listOf(bubble) + rest).map { item ->
            val keep = !item.leaving && visible < MAX_BUBBLES
            if (keep) visible++
            if (keep || item.leaving) item else item.copy(leaving = true)
        }
        // Bubble yang terdorong keluar tidak perlu timer lagi.
        bubbleState.value.filter { it.leaving }.forEach {
            bubbleJobs.remove(it.id)?.cancel()
        }
        // Bubble hilang sendiri agar tidak menutupi layar saat chat sepi.
        scheduleBubbleExit(id, bubble.text)
        if (!menuOpen) {
            // Pastikan ruang di bawah logo cukup untuk bubble yang tampil.
            applyPosition(logoPx, logoPx)
        }
    }

    /** Ketuk bubble agar tampil lebih lama (berguna untuk pesan panjang). */
    private fun extendBubble(id: String) {
        val bubble = bubbleState.value.firstOrNull { it.id == id && !it.leaving } ?: return
        scheduleBubbleExit(id, bubble.text, extraMs = 2_000L)
    }

    /** Jadwalkan bubble keluar sendiri; timer lama dibatalkan lebih dulu. */
    private fun scheduleBubbleExit(id: String, text: String, extraMs: Long = 0L) {
        bubbleJobs.remove(id)?.cancel()
        bubbleJobs[id] = scope.launch {
            delay(bubbleDurationMs(text) + extraMs)
            markBubbleLeaving(id)
        }
    }

    private fun markBubbleLeaving(id: String) {
        bubbleJobs.remove(id)?.cancel()
        bubbleState.value = bubbleState.value.map {
            if (it.id == id && !it.leaving) it.copy(leaving = true) else it
        }
    }

    private fun removeBubble(id: String) {
        bubbleJobs.remove(id)?.cancel()
        bubbleState.value = bubbleState.value.filterNot { it.id == id }
        updateBubbleVisibility()
    }

    /** Panel obrolan: nama (berwarna per peran) di atas, pesan di bawah. */
    private fun chatSpans(messages: List<ChatMessage>): CharSequence {
        val out = SpannableStringBuilder()
        messages.forEachIndexed { index, message ->
            if (index > 0) out.append("\n\n")
            val badge = message.role.emoji()
            val nameStart = out.length
            if (badge.isNotEmpty()) out.append(badge).append(' ')
            out.append(message.author)
            out.setSpan(
                ForegroundColorSpan(message.role.nameColor()),
                nameStart,
                out.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            out.setSpan(
                StyleSpan(Typeface.BOLD),
                nameStart,
                out.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (message.verified) {
                val checkStart = out.length
                out.append(" ✔")
                out.setSpan(
                    ForegroundColorSpan(VERIFIED_COLOR),
                    checkStart,
                    out.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            out.append('\n').append(message.text)
        }
        return out
    }

    /** Lebar bubble mengikuti ruang di sisi logo agar tidak keluar layar. */
    private fun bubbleWidthPx(bubbleOnRight: Boolean): Int {
        val screenW = resources.displayMetrics.widthPixels
        val room = if (bubbleOnRight) {
            screenW - logoX - dp(8)
        } else {
            logoX + logoPx - dp(8)
        }
        return min(dp(260), room.coerceAtLeast(dp(140)))
    }


    /** Ikon tombol dan angka kbps mengikuti status siaran sebenarnya. */
    private fun observeLive() {
        scope.launch {
            CaptureEngine.liveStats.collect { stats ->
                val live = stats.active
                action.setImageResource(if (live) R.drawable.ic_stop else R.drawable.ic_live)
                action.background = circleBackground(
                    if (live) Color.parseColor("#E53935") else Color.parseColor("#00E5FF"),
                )
                action.contentDescription = getString(
                    if (live) R.string.floating_stop else R.string.overlay_live_start,
                )
                signalLabel.text = if (stats.bitrateKbps > 0) {
                    "${stats.bitrateKbps} kbps"
                } else {
                    "-- kbps"
                }
                updateTimer()
            }
        }
    }

    /** Ikon slot 8 menyala merah selama layar jeda tampil. */
    private fun observeScreensaver() {
        scope.launch {
            SceneManager.program.collect {
                val active = SceneManager.screensaverActive()
                screensaverButton.setColorFilter(
                    if (active) Color.parseColor("#FF4C4C") else Color.WHITE,
                )
            }
        }
    }

    /** Timer harus berdetak tiap detik, bukan hanya saat status berubah. */
    private fun startTicker() {
        scope.launch {
            while (true) {
                updateTimer()
                delay(1000)
            }
        }
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
                action.isEnabled = state !is RecordingState.Preparing
                action.alpha = if (action.isEnabled) 1f else 0.4f
                updateTimer()
            }
        }
    }

    private enum class ContentMode { Preview, Messages, Vu, Screensaver, Scenes }

    /** Ganti isi panel overlay: pratinjau, pesan, atau VU audio. */
    private fun showContent(mode: ContentMode) {
        previewBox.visibility = if (mode == ContentMode.Preview) View.VISIBLE else View.GONE
        messageBox.visibility = if (mode == ContentMode.Messages) View.VISIBLE else View.GONE
        vuBox.visibility = if (mode == ContentMode.Vu) View.VISIBLE else View.GONE
        jedaBox.visibility = if (mode == ContentMode.Screensaver) View.VISIBLE else View.GONE
        sceneBox.visibility = if (mode == ContentMode.Scenes) View.VISIBLE else View.GONE
        if (mode == ContentMode.Preview) maybeAttach() else detachPreview()
        // Saat panel obrolan dibuka, tampilkan pesan terbaru di bawah.
        if (mode == ContentMode.Messages) {
            chatScroll.post {
                chatScroll.fullScroll(View.FOCUS_DOWN)
                updateChatJumpButton()
            }
        } else {
            updateChatJumpButton()
        }
    }

    private fun openStudio() {
        com.rout.air.data.UiEvents.openStudio.tryEmit(Unit)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("open_tab", "studio")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun toast(res: Int) {
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Dialog siap-siaran melayang saat pengguna berada di luar RoutAir. */
    private fun showLiveSetupOverlay() {
        if (setupWindow != null) return
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(overlayOwner)
            setViewTreeSavedStateRegistryOwner(overlayOwner)
            setContent {
                RoutAirTheme {
                    var busy by remember { mutableStateOf(false) }
                    var error by remember { mutableStateOf<String?>(null) }
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Box(modifier = Modifier.padding(16.dp)) {
                                LiveSetupCard(
                                    busy = busy,
                                    error = error,
                                    onCancel = { hideLiveSetupOverlay() },
                                    onStart = { title, description, privacy ->
                                        busy = true
                                        error = null
                                        scope.launch {
                                            val result = prepareYouTubeBroadcast(
                                                this@OverlayLiveService,
                                                title,
                                                description,
                                                privacy,
                                            )
                                            busy = false
                                            result.onSuccess { target ->
                                                val store = com.rout.air.data.SettingsStore(
                                                    this@OverlayLiveService,
                                                )
                                                store.saveLive(
                                                    store.loadLive().copy(
                                                        serverUrl = target.server,
                                                        streamKey = target.key,
                                                        accountLinked = true,
                                                    ),
                                                )
                                                hideLiveSetupOverlay()
                                                startLiveNow()
                                            }.onFailure {
                                                error = it.message ?: "Gagal membuat siaran."
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            dimAmount = 0.7f
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) {
            // Izin overlay dicabut: kembali ke jalur aplikasi.
            com.rout.air.data.UiEvents.openLiveSetup.tryEmit(Unit)
            openApp("live")
            return
        }
        setupWindow = view
    }

    private fun hideLiveSetupOverlay() {
        setupWindow?.let { runCatching { windowManager.removeView(it) } }
        setupWindow = null
    }

    /** Memulai siaran memakai setelan yang tersimpan. */
    private fun startLiveNow() {
        if (!CaptureEngine.sessionActive.value) {
            // Sesi tangkapan belum aktif; izin harus diminta dari aplikasi.
            openApp()
            return
        }
        val config = com.rout.air.data.SettingsStore(this).loadLive()
        if (config.isConfigured) CaptureEngine.startLive(config)
    }

    private fun openApp(tab: String? = null) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (tab != null) intent.putExtra("open_tab", tab)
        startActivity(intent)
    }

    companion object {
        private const val CHANNEL_ID = "routair_overlay"
        private const val NOTIFICATION_ID = 21
        /** Jumlah pesan terakhir yang tampil di panel Obrolan (sama dengan buffer ChatMonitor). */
        private const val CHAT_LINES = 60
        /** Maksimal bubble chat yang tampil bersamaan di bawah logo. */
        private const val MAX_BUBBLES = 3
        /** Batas ingatan id pesan agar chat lama tidak muncul lagi. */
        private const val SEEN_LIMIT = 200
        const val ACTION_STOP = "com.rout.air.action.OVERLAY_LIVE_STOP"

        val running = MutableStateFlow(false)

        fun start(context: Context) {
            val intent = Intent(context, OverlayLiveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayLiveService::class.java))
        }
    }
}
