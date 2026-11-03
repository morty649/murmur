package com.morty649.murmur

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sin

/**
 * Foreground service that owns the floating bubble, the microphone and the loaded model.
 * Tap the bubble to start listening, tap again to transcribe and type.
 */
class BubbleService : Service() {

    private enum class UiState { LOADING, IDLE, RECORDING, BUSY }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private var bubble: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var recorder: Recorder? = null
    private var state = UiState.LOADING

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (bubble == null) showBubble()
        preload()
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Dictation bubble", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Murmur bubble is on")
            .setContentText("Tap the bubble to dictate. Audio stays on your phone.")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .build()
        return try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
            true
        } catch (e: Exception) {
            toast("Couldn't start the bubble: ${e.message}")
            false
        }
    }

    private fun preload() {
        setState(UiState.LOADING)
        worker.execute {
            val model = Prefs.selectedModel(this)
            val error = runCatching { Transcriber.load(this, model) }.exceptionOrNull()
            main.post {
                if (error != null) toast("Couldn't load ${model.title}: ${error.message}")
                if (state == UiState.LOADING) setState(UiState.IDLE)
            }
        }
    }

    // ---------------------------------------------------------------- dictation

    private fun onTap() {
        when (state) {
            UiState.IDLE -> startListening()
            UiState.RECORDING -> stopAndTranscribe()
            UiState.LOADING -> toast("Loading the speech model…")
            UiState.BUSY -> Unit
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Microphone permission is off. Open Murmur to grant it.")
            return
        }
        val r = Recorder(onLimitReached = { main.post { if (state == UiState.RECORDING) stopAndTranscribe() } })
        if (!r.start()) {
            toast("Microphone is busy or unavailable")
            return
        }
        recorder = r
        bubble?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        setState(UiState.RECORDING)
    }

    private fun stopAndTranscribe() {
        val samples = recorder?.stop() ?: return
        recorder = null
        bubble?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (samples.size < Transcriber.SAMPLE_RATE / 3) {
            setState(UiState.IDLE)
            return
        }
        setState(UiState.BUSY)
        worker.execute {
            val result = runCatching {
                Transcriber.load(this, Prefs.selectedModel(this)) // no-op unless the model changed
                Cleanup.apply(Transcriber.transcribe(samples), Prefs.cleanup(this))
            }
            main.post {
                setState(UiState.IDLE)
                result.onSuccess { deliver(it) }
                    .onFailure { toast("Transcription failed: ${it.message}") }
            }
        }
    }

    private fun deliver(text: String) {
        if (text.isBlank()) {
            toast("Didn't catch that")
            return
        }
        val typed = TypeService.instance?.insert(text) == true
        if (!typed) {
            TypeService.copyToClipboard(this, text)
            toast(
                if (TypeService.instance == null) "Copied. Turn on Murmur in Accessibility to type directly."
                else "No text box selected, so it's copied to the clipboard",
            )
        }
    }

    // ---------------------------------------------------------------- bubble UI
    //
    // Idle: one of three styles (glass dot, edge tab, app icon), see-through by the user's
    // transparency setting. Loading / listening / transcribing: a solid Muesli-style capsule
    // with 5 bars (live loudness while listening, a slow wave otherwise).

    private val envelope = floatArrayOf(0.6f, 0.85f, 1f, 0.85f, 0.6f)
    private val bars = ArrayList<View>()
    private var idleView: View? = null
    private var capsule: LinearLayout? = null
    private var stopSquare: View? = null
    private var dockRight = true
    private var smoothed = 0f
    private var animStart = 0L
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_STYLE || key == Prefs.KEY_TRANSPARENCY) main.post { rebuildContent() }
    }

    private val animate = object : Runnable {
        override fun run() {
            val s = state
            if (bubble == null || s == UiState.IDLE) return
            val t = (SystemClock.uptimeMillis() - animStart) / 1000f
            if (s == UiState.RECORDING) {
                smoothed = 0.48f * (recorder?.level ?: 0f) + 0.52f * smoothed
                for (i in bars.indices) {
                    val h = 3f + 15f * smoothed * envelope[i] // dp, of 18
                    bars[i].scaleY = h / 18f
                    bars[i].alpha = 1f
                }
            } else {
                for (i in bars.indices) {
                    val v = 0.5f + 0.5f * sin(t * 5.8f + i * 0.72f)
                    bars[i].scaleY = 0.3f + 0.5f * v
                    bars[i].alpha = 0.55f + 0.45f * v
                }
            }
            main.postDelayed(this, 33)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        wm = getSystemService(WindowManager::class.java)
        val root = FrameLayout(this).apply { contentDescription = "Murmur dictation" }
        val (right, y) = Prefs.bubbleDock(this)
        dockRight = right
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable: the app underneath keeps its text box focused while you tap.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Anchored to the docked edge, so the capsule grows inwards when it widens.
            gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
            this.y = y
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        root.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = (startX + (if (dockRight) -dx else dx).toInt()).coerceAtLeast(0)
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (dragging) {
                        val nowRight = e.rawX > resources.displayMetrics.widthPixels / 2f
                        val sideChanged = nowRight != dockRight
                        dockRight = nowRight
                        lp.gravity = Gravity.TOP or (if (nowRight) Gravity.END else Gravity.START)
                        lp.y = lp.y.coerceAtLeast(0)
                        Prefs.setBubbleDock(this, nowRight, lp.y)
                        if (sideChanged) rebuildContent() else { lp.x = edgeMargin(); wm.updateViewLayout(v, lp) }
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        onTap()
                    }
                }
            }
            true
        }
        bubble = root
        params = lp
        Prefs.register(this, prefsListener)
        rebuildContent(addToWindow = true)
    }

    /** Builds the idle view for the chosen style plus the capsule, then reapplies the state. */
    private fun rebuildContent(addToWindow: Boolean = false) {
        val root = bubble ?: return
        val lp = params ?: return
        root.removeAllViews()
        bars.clear()

        val idle = when (Prefs.bubbleStyle(this)) {
            BubbleStyle.GLASS -> FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(GLASS)
                    setStroke(dp(1), RIM)
                }
                addView(mark(), FrameLayout.LayoutParams(dp(34), dp(19), Gravity.CENTER))
                layoutParams = FrameLayout.LayoutParams(dp(54), dp(54))
            }
            BubbleStyle.EDGE -> FrameLayout(this).apply {
                val r = dp(32).toFloat()
                background = GradientDrawable().apply {
                    setColor(GLASS)
                    setStroke(dp(1), RIM)
                    cornerRadii = if (dockRight) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                    else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                }
                addView(
                    mark(),
                    FrameLayout.LayoutParams(dp(26), dp(14), Gravity.CENTER).apply {
                        if (dockRight) leftMargin = dp(4) else rightMargin = dp(4)
                    },
                )
                layoutParams = FrameLayout.LayoutParams(dp(40), dp(64))
            }
            BubbleStyle.ICON -> FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(0xFF2A2F5B.toInt())
                }
                clipToOutline = true
                addView(
                    ImageView(context).apply {
                        setImageDrawable(
                            LayerDrawable(
                                arrayOf(
                                    ContextCompat.getDrawable(context, R.drawable.ic_launcher_background)!!,
                                    ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!,
                                ),
                            ),
                        )
                        scaleType = ImageView.ScaleType.FIT_XY
                    },
                    FrameLayout.LayoutParams(dp(54), dp(54)),
                )
                layoutParams = FrameLayout.LayoutParams(dp(54), dp(54))
            }
        }

        val stop = View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(1).toFloat() + 0.5f; setColor(0xD9FFFFFF.toInt()) }
        }
        val cap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            elevation = dp(4).toFloat()
            repeat(5) { i ->
                val bar = View(context).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(2).toFloat() }
                }
                bars += bar
                addView(bar, LinearLayout.LayoutParams(dp(3), dp(18)).apply { if (i > 0) leftMargin = dp(4) })
            }
            addView(stop, LinearLayout.LayoutParams(dp(8), dp(8)).apply { leftMargin = dp(10) })
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(44))
        }

        root.addView(idle)
        root.addView(cap)
        idleView = idle
        capsule = cap
        stopSquare = stop
        lp.x = edgeMargin()
        if (addToWindow) wm.addView(root, lp) else wm.updateViewLayout(root, lp)
        setState(state)
    }

    private fun mark() = ImageView(this).apply { setImageResource(R.drawable.bubble_mark) }

    /** The edge tab sits flush against the screen edge; the others float just off it. */
    private fun edgeMargin() = if (Prefs.bubbleStyle(this) == BubbleStyle.EDGE) 0 else dp(6)

    private fun setState(s: UiState) {
        state = s
        val root = bubble ?: return
        val cap = capsule ?: return
        val idle = s == UiState.IDLE
        idleView?.visibility = if (idle) View.VISIBLE else View.GONE
        cap.visibility = if (idle) View.GONE else View.VISIBLE
        root.alpha = if (idle) 1f - Prefs.transparency(this) / 100f else 1f
        main.removeCallbacks(animate)
        if (idle) return

        val r = dp(22).toFloat()
        val edge = Prefs.bubbleStyle(this) == BubbleStyle.EDGE
        cap.background = GradientDrawable().apply {
            setColor(
                when (s) {
                    UiState.RECORDING -> 0xE0DC2626.toInt()
                    UiState.BUSY -> 0xC716171E.toInt()
                    else -> GLASS
                },
            )
            setStroke(dp(1), 0x29FFFFFF)
            cornerRadii = when {
                !edge -> floatArrayOf(r, r, r, r, r, r, r, r)
                dockRight -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                else -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
        }
        val barColor = when (s) {
            UiState.RECORDING -> 0xFFFFFFFF.toInt()
            UiState.BUSY -> 0xFFF59E0B.toInt()
            else -> 0x80FFFFFF.toInt()
        }
        bars.forEach { (it.background as GradientDrawable).setColor(barColor) }
        stopSquare?.visibility = if (s == UiState.RECORDING) View.VISIBLE else View.GONE
        smoothed = 0f
        animStart = SystemClock.uptimeMillis()
        main.post(animate)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onDestroy() {
        running = false
        main.removeCallbacks(animate)
        Prefs.unregister(this, prefsListener)
        recorder?.stop()
        recorder = null
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        // Free the model's memory (~1 GB) once the bubble is off.
        worker.execute { if (!running) Transcriber.release() }
        worker.shutdown()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL = "bubble"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.morty649.murmur.STOP"
        private const val GLASS = 0x9E16171E.toInt() // Muesli-style dark glass, ~62%
        private const val RIM = 0x38FFFFFF // white 22% border

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, BubbleService::class.java))

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BubbleService::class.java))
        }
    }
}
