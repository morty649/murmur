package com.morty649.murmur

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Foreground service that owns the floating mic bubble, the microphone and the loaded model.
 * Tap the bubble to start listening, tap again to transcribe and type.
 */
class BubbleService : Service() {

    private enum class UiState { LOADING, IDLE, RECORDING, BUSY }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private var bubble: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var pulse: ValueAnimator? = null
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

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        wm = getSystemService(WindowManager::class.java)
        val size = dp(58)
        val view = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            elevation = dp(6).toFloat()
            addView(
                ImageView(context).apply { setImageResource(R.drawable.ic_mic) },
                FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER),
            )
            contentDescription = "Murmur dictation"
        }
        val (x, y) = Prefs.bubblePos(this)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable: the app underneath keeps its text box focused while you tap.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { v, e ->
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
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (dragging) {
                        val screenW = resources.displayMetrics.widthPixels
                        lp.x = if (lp.x + size / 2 < screenW / 2) 0 else screenW - size
                        lp.y = lp.y.coerceAtLeast(0)
                        wm.updateViewLayout(v, lp)
                        Prefs.setBubblePos(this, lp.x, lp.y)
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        onTap()
                    }
                }
            }
            true
        }
        wm.addView(view, lp)
        bubble = view
        params = lp
        setState(state)
    }

    private fun setState(s: UiState) {
        state = s
        val view = bubble ?: return
        val color = when (s) {
            UiState.LOADING -> 0xFF757575.toInt()
            UiState.IDLE -> 0xFF3D5AFE.toInt()
            UiState.RECORDING -> 0xFFE53935.toInt()
            UiState.BUSY -> 0xFFFFA000.toInt()
        }
        (view.background as GradientDrawable).setColor(color)
        view.alpha = if (s == UiState.IDLE) 0.85f else 1f

        pulse?.cancel()
        pulse = null
        view.getChildAt(0).scaleX = 1f
        view.getChildAt(0).scaleY = 1f
        if (s == UiState.RECORDING || s == UiState.BUSY || s == UiState.LOADING) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                view.getChildAt(0),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f),
            ).apply {
                duration = if (s == UiState.RECORDING) 550 else 300
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onDestroy() {
        running = false
        pulse?.cancel()
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
