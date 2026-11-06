package com.rout.air.service.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import com.rout.air.MainActivity
import com.rout.air.data.overlay.OverlayInfoStore
import com.rout.air.data.scene.SceneManager
import com.rout.air.data.common.UiEvents
import com.rout.air.data.overlay.buildOverlayInfoText
import com.rout.air.output.recording.CaptureEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Kerangka panel overlay mengambang yang dipakai overlay rekam dan live.
 *
 * Menyimpan tata letak, ukuran, dan helper tampilan yang sama untuk kedua
 * panel; masing-masing service hanya membangun isi panelnya sendiri.
 */
abstract class OverlayPanelService : Service() {

    protected lateinit var windowManager: WindowManager
    protected lateinit var root: FrameLayout
    protected lateinit var logo: ImageView
    protected lateinit var panel: FrameLayout
    protected lateinit var preview: TextureView
    protected var textureSurface: Surface? = null
    protected lateinit var previewBox: FrameLayout
    protected lateinit var vuBox: FrameLayout
    protected lateinit var audioCompose: ComposeView
    protected val overlayOwner = OverlayViewOwner()
    protected lateinit var resizeHandle: ImageView
    protected lateinit var action: ImageButton
    protected lateinit var timerView: TextView
    protected lateinit var infoBox: FrameLayout
    protected lateinit var params: WindowManager.LayoutParams

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    protected var panelMenuOpen = false
    protected var keepScreenOn = false
    protected var previewAttached = false
    protected var attachedSurface: Surface? = null

    protected val logoPx by lazy { dp(30) }
    protected val headerPx by lazy { dp(34) }
    protected val controlsPx by lazy { dp(46) }
    protected val sidePadPx by lazy { dp(12) }

    protected var logoX = 0
    protected var logoY = 0
    protected var panelW = 0
    protected var panelH = 0
    protected var previewH = 0

    /** Chip info di atas panel; tag dan ukuran ditentukan tiap service. */
    protected abstract val infoChip: InfoChip

    /** Bangun pohon view panel; dipanggil sekali dari [Service.onCreate]. */
    protected abstract fun buildOverlay()

    protected fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    protected fun attachPreview() {
        if (previewAttached) return
        if (!panelMenuOpen) return
        val surface = textureSurface ?: return
        if (!surface.isValid) return
        CaptureEngine.setPreviewVisible(true)
        attachedSurface = surface
        // Panel overlay menampilkan program yang mengudara, bukan scene hasil edit.
        CaptureEngine.attachOverlayProgram(surface, panelW - sidePadPx * 2, previewH)
        previewAttached = true
    }

    protected fun detachPreview() {
        if (!previewAttached) return
        CaptureEngine.detachOverlayProgram()
        attachedSurface = null
        if (!MainActivity.visible) CaptureEngine.setPreviewVisible(false)
        previewAttached = false
    }

    protected fun maybeAttach() {
        if (panelMenuOpen) attachPreview()
    }

    /** Chip info tetap berdetak walau panel overlay tertutup. */
    protected fun observeInfo() {
        scope.launch {
            OverlayInfoStore.items.collect { updateInfoChip() }
        }
    }

    protected fun updateInfoChip() {
        val text = buildOverlayInfoText(
            items = OverlayInfoStore.items.value,
            stats = CaptureEngine.stats.value,
            live = CaptureEngine.liveStats.value,
            sceneName = SceneManager.program.value?.activeScene()?.name,
            width = CaptureEngine.sessionWidth,
            height = CaptureEngine.sessionHeight,
        )
        infoChip.setText(text)
    }

    protected fun openStudio() {
        UiEvents.openStudio.tryEmit(Unit)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("open_tab", "studio")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    protected fun headerButton(res: Int, desc: String, onClick: () -> Unit): ImageButton =
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

    protected fun toolButton(res: Int, desc: String, onClick: (() -> Unit)?): ImageButton =
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

    protected fun panelBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#B8243A55"))
        }

    protected fun contentParams(): FrameLayout.LayoutParams =
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

    protected fun boxBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(Color.BLACK)
    }

    protected fun circleBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    /** Lebar minimum: ikut foto (±42% layar) dan cukup untuk header + ✕. */
    protected fun minPanelWidthPx(): Int {
        val screenW = resources.displayMetrics.widthPixels
        return maxOf(dp(200), (screenW * 0.42f).roundToInt())
    }

    protected fun computePanelSize() {
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
}
