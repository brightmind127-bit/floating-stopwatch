package com.floatwatch

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject



data class StopwatchConfig(
    var id: Int,
    var name: String,
    var countdown: Boolean = false,
    var countdownMs: Long = 20 * 60 * 1000L,
    var vibrate: Boolean = false,
    var repeat: Boolean = false,
    var size: Int = 30,
    var runningColor: Int = Color.parseColor("#2E7D32"),
    var pauseColor: Int = Color.parseColor("#A31521"),
    var textColor: Int = Color.WHITE,
    var showMillis: Boolean = true,
    var showHours: Boolean = true,
    var digital: Boolean = false,
    var posX: Int = -1,
    var posY: Int = -1
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("countdown", countdown); put("countdownMs", countdownMs)
        put("vibrate", vibrate); put("repeat", repeat); put("size", size)
        put("runningColor", runningColor); put("pauseColor", pauseColor); put("textColor", textColor)
        put("showMillis", showMillis); put("showHours", showHours); put("digital", digital)
        put("posX", posX); put("posY", posY)
    }

    companion object {
        fun fromJson(o: JSONObject): StopwatchConfig {
            val d = StopwatchConfig(o.getInt("id"), o.optString("name", "Stopwatch"))
            return d.copy(
                countdown = o.optBoolean("countdown", d.countdown),
                countdownMs = o.optLong("countdownMs", d.countdownMs),
                vibrate = o.optBoolean("vibrate", d.vibrate),
                repeat = o.optBoolean("repeat", d.repeat),
                size = o.optInt("size", d.size),
                runningColor = o.optInt("runningColor", d.runningColor),
                pauseColor = o.optInt("pauseColor", d.pauseColor),
                textColor = o.optInt("textColor", d.textColor),
                showMillis = o.optBoolean("showMillis", d.showMillis),
                showHours = o.optBoolean("showHours", d.showHours),
                digital = o.optBoolean("digital", d.digital),
                posX = o.optInt("posX", -1),
                posY = o.optInt("posY", -1)
            )
        }
    }
}

/** Saves all stopwatch settings on the phone. */
object Store {
    private const val PREFS = "floatwatch"
    private const val KEY = "stopwatches"

    fun load(ctx: Context): MutableList<StopwatchConfig> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return defaults().also { save(ctx, it) }
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { StopwatchConfig.fromJson(arr.getJSONObject(it)) }
    }

    fun save(ctx: Context, list: List<StopwatchConfig>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun get(ctx: Context, id: Int): StopwatchConfig? = load(ctx).find { it.id == id }

    fun update(ctx: Context, cfg: StopwatchConfig) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == cfg.id }
        if (i >= 0) list[i] = cfg else list.add(cfg)
        save(ctx, list)
    }

    fun delete(ctx: Context, id: Int) = save(ctx, load(ctx).filter { it.id != id })

    fun nextId(list: List<StopwatchConfig>) = (list.maxOfOrNull { it.id } ?: 0) + 1

    private fun defaults() = mutableListOf(
        StopwatchConfig(1, "Stopwatch 1", countdown = true),
        StopwatchConfig(2, "Stopwatch 2", pauseColor = Color.parseColor("#AD1457")),
        StopwatchConfig(3, "Stopwatch 3",
            runningColor = Color.parseColor("#689F38"), pauseColor = Color.parseColor("#6A1B9A"))
    )
}

val PALETTE = intArrayOf(
    Color.parseColor("#2E7D32"), Color.parseColor("#689F38"), Color.parseColor("#43A047"), Color.parseColor("#00897B"),
    Color.parseColor("#0277BD"), Color.parseColor("#1565C0"), Color.parseColor("#283593"), Color.parseColor("#6A1B9A"),
    Color.parseColor("#AD1457"), Color.parseColor("#A31521"), Color.parseColor("#C62828"), Color.parseColor("#D84315"),
    Color.parseColor("#EF6C00"), Color.parseColor("#F9A825"), Color.parseColor("#FFEB3B"), Color.parseColor("#80DEEA"),
    Color.parseColor("#5D4037"), Color.parseColor("#424242"), Color.BLACK, Color.WHITE
)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun swatchDrawable(ctx: Context, color: Int, selected: Boolean = false) = GradientDrawable().apply {
    setColor(color)
    setStroke(ctx.dp(if (selected) 3 else 1), Color.parseColor(if (selected) "#F26B1D" else "#666666"))
}

fun formatDuration(ms: Long): String {
    val t = ms / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
}



/**
 * Keeps the floating timers on top of every app.
 * Tap = start/pause · Hold = Reset / Edit / Close menu · Drag = move.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.floatwatch.SHOW"
        const val ACTION_HIDE = "com.floatwatch.HIDE"
        const val ACTION_REFRESH = "com.floatwatch.REFRESH"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "floating_timers"
        private const val NOTIF_ID = 42

        /** Which stopwatches are currently floating. */
        val activeIds = mutableSetOf<Int>()
        var onChanged: (() -> Unit)? = null

        fun send(ctx: Context, action: String, id: Int) {
            val i = Intent(ctx, OverlayService::class.java).setAction(action).putExtra(EXTRA_ID, id)
            when {
                action == ACTION_SHOW -> ctx.startForegroundService(i)
                id in activeIds -> ctx.startService(i)
            }
        }
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val timers = linkedMapOf<Int, FloatingTimer>()

    private val ticker = object : Runnable {
        override fun run() {
            timers.values.forEach { it.update() }
            if (timers.isNotEmpty()) handler.postDelayed(this, 33)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Floating timers", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val id = intent?.getIntExtra(EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_SHOW -> show(id)
            ACTION_HIDE -> hide(id)
            ACTION_REFRESH -> timers[id]?.reload()
        }
        if (timers.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    private fun show(id: Int) {
        if (id in timers || !Settings.canDrawOverlays(this)) return
        val cfg = Store.get(this, id) ?: return
        val t = FloatingTimer(cfg)
        timers[id] = t
        activeIds.add(id)
        wm.addView(t.root, t.params)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        changed()
    }

    private fun hide(id: Int) {
        timers.remove(id)?.let { runCatching { wm.removeView(it.root) } }
        activeIds.remove(id)
        changed()
        if (timers.isEmpty()) stopSelf()
    }

    private fun changed() {
        if (timers.isNotEmpty()) goForeground()
        onChanged?.invoke()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        timers.values.forEach { runCatching { wm.removeView(it.root) } }
        timers.clear()
        activeIds.clear()
        onChanged?.invoke()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = timers.size
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Floating Stopwatch")
            .setContentText(if (n == 1) "1 timer on screen" else "$n timers on screen")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun vibrate() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        else getSystemService(Vibrator::class.java)
        v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 600), -1))
    }

    /** One floating timer window. */
    inner class FloatingTimer(private var cfg: StopwatchConfig) {
        private val ctx = this@OverlayService

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        private val bg = GradientDrawable().apply { cornerRadius = ctx.dp(8).toFloat() }
        private val label = TextView(ctx).apply {
            background = bg
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        private val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(18).toFloat()
                setColor(Color.parseColor("#E6202020"))
            }
            setPadding(ctx.dp(4), ctx.dp(2), ctx.dp(4), ctx.dp(2))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (cfg.posX >= 0) cfg.posX else ctx.dp(16)
            y = if (cfg.posY >= 0) cfg.posY else ctx.dp(80) + timers.size * ctx.dp(72)
        }

        private var running = false
        private var accumulated = 0L
        private var startedAt = 0L
        private var lastText = ""

        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

        private val hideMenu = Runnable { menu.visibility = View.GONE }
        private val longPress = Runnable {
            longPressed = true
            label.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            menu.visibility = View.VISIBLE
            handler.removeCallbacks(hideMenu)
            handler.postDelayed(hideMenu, 4000)
        }

        init {
            root.addView(label)
            root.addView(menu, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(6) })
            menu.addView(menuButton("↺ Reset") { reset() })
            menu.addView(menuButton("✎ Edit") { openEditor() })
            menu.addView(menuButton("✕ Close") { hide(cfg.id) })
            label.setOnTouchListener { _, e -> onTouch(e) }
            applyStyle()
            update()
        }

        private fun menuButton(title: String, action: () -> Unit) = TextView(ctx).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            setOnClickListener {
                handler.removeCallbacks(hideMenu)
                menu.visibility = View.GONE
                action()
            }
        }

        private fun onTouch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false; longPressed = false
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) moveTo(startX + dx.toInt(), startY + dy.toInt())
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (dragging) savePosition()
                    else if (!longPressed) {
                        menu.visibility = View.GONE
                        toggle()
                        label.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            return true
        }

        private fun moveTo(x: Int, y: Int) {
            val dm = resources.displayMetrics
            params.x = x.coerceIn(0, max(0, dm.widthPixels - root.width))
            params.y = y.coerceIn(0, max(0, dm.heightPixels - root.height))
            runCatching { wm.updateViewLayout(root, params) }
        }

        private fun savePosition() {
            Store.get(ctx, cfg.id)?.let {
                it.posX = params.x; it.posY = params.y
                Store.update(ctx, it)
                cfg = it
            }
        }

        private fun toggle() {
            val now = SystemClock.elapsedRealtime()
            if (running) {
                accumulated += now - startedAt
                running = false
            } else {
                if (cfg.countdown && accumulated >= cfg.countdownMs) accumulated = 0
                startedAt = now
                running = true
            }
            applyStyle()
            update()
        }

        private fun reset() {
            running = false
            accumulated = 0
            applyStyle()
            update()
        }

        private fun openEditor() {
            startActivity(
                Intent(ctx, EditActivity::class.java)
                    .putExtra("id", cfg.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        fun reload() {
            Store.get(ctx, cfg.id)?.let { cfg = it; applyStyle(); update() }
        }

        private fun applyStyle() {
            bg.setColor(if (running) cfg.runningColor else cfg.pauseColor)
            label.setTextColor(cfg.textColor)
            label.textSize = 16f + cfg.size * 0.5f
            label.typeface = if (cfg.digital) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                             else Typeface.DEFAULT_BOLD
            val padH = ctx.dp(10 + cfg.size / 8)
            val padV = ctx.dp(4 + cfg.size / 12)
            label.setPadding(padH, padV, padH, padV)
            lastText = ""
        }

        fun update() {
            val now = SystemClock.elapsedRealtime()
            var ms = accumulated + if (running) now - startedAt else 0L
            if (cfg.countdown) {
                var remaining = cfg.countdownMs - ms
                if (remaining <= 0 && running) {
                    if (cfg.vibrate) vibrate()
                    if (cfg.repeat) {
                        accumulated = 0; startedAt = now; remaining = cfg.countdownMs
                    } else {
                        running = false; accumulated = cfg.countdownMs; remaining = 0
                        applyStyle()
                    }
                }
                ms = max(0L, remaining)
            }
            val txt = format(ms)
            if (txt.toString() != lastText) {
                label.text = txt
                lastText = txt.toString()
            }
        }

        private fun format(ms: Long): CharSequence {
            val s = ms / 1000
            val main = if (cfg.showHours)
                String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
            else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
            if (!cfg.showMillis) return main
            val full = main + String.format(Locale.US, ":%02d", (ms % 1000) / 10)
            return SpannableString(full).apply {
                setSpan(RelativeSizeSpan(0.55f), main.length, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}



/** List of stopwatches (like your 3rd screenshot). */
class MainActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        col.addView(Button(this).apply {
            text = "+ Add stopwatch"
            setOnClickListener { addStopwatch() }
        })
        col.addView(TextView(this).apply {
            text = "Turn a switch on to float that timer over your screen.\n" +
                "• Tap the floating timer: start / pause\n" +
                "• Hold it: reset, edit or close\n" +
                "• Drag it: move it anywhere\n" +
                "• Tap a card here to edit its settings"
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 14f
            setPadding(dp(4), dp(16), dp(4), 0)
        })
        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        OverlayService.onChanged = { runOnUiThread { render() } }
        render()
    }

    override fun onPause() {
        super.onPause()
        OverlayService.onChanged = null
    }

    private fun render() {
        list.removeAllViews()
        Store.load(this).forEach { list.addView(card(it)) }
    }

    private fun card(cfg: StopwatchConfig): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(dp(16), dp(12), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            setOnClickListener {
                startActivity(Intent(this@MainActivity, EditActivity::class.java).putExtra("id", cfg.id))
            }
            setOnLongClickListener { confirmDelete(cfg); true }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "⌛"; textSize = 22f; setPadding(0, 0, dp(12), 0)
        })
        header.addView(TextView(this).apply {
            text = cfg.name
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#DDDDDD"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        header.addView(Switch(this).apply {
            isChecked = cfg.id in OverlayService.activeIds
            setOnCheckedChangeListener { b, on ->
                if (on) {
                    if (Settings.canDrawOverlays(this@MainActivity)) {
                        OverlayService.send(this@MainActivity, OverlayService.ACTION_SHOW, cfg.id)
                    } else {
                        b.isChecked = false
                        askOverlayPermission()
                    }
                } else {
                    OverlayService.send(this@MainActivity, OverlayService.ACTION_HIDE, cfg.id)
                }
            }
        })
        card.addView(header)

        card.addView(CheckBox(this).apply {
            isChecked = cfg.countdown
            isEnabled = false
            isClickable = false
            isFocusable = false
            text = if (cfg.countdown) "Countdown mode (${formatDuration(cfg.countdownMs)})" else "Countdown mode"
            setPadding(dp(6), dp(8), 0, dp(8))
        })

        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        r1.addView(swatchLabel(cfg.runningColor, "Running color"))
        r1.addView(swatchLabel(cfg.pauseColor, "Pause color"))
        card.addView(r1)

        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        r2.addView(swatchLabel(cfg.textColor, "Text color"))
        r2.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        card.addView(r2)
        return card
    }

    private fun swatchLabel(color: Int, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        addView(View(this@MainActivity).apply { background = swatchDrawable(this@MainActivity, color) },
            LinearLayout.LayoutParams(dp(26), dp(26)))
        addView(TextView(this@MainActivity).apply {
            text = label; textSize = 16f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(dp(14), 0, 0, 0)
        })
    }

    private fun addStopwatch() {
        val l = Store.load(this)
        val id = Store.nextId(l)
        l.add(StopwatchConfig(id, "Stopwatch $id", pauseColor = PALETTE[(id * 3) % 12]))
        Store.save(this, l)
        render()
    }

    private fun confirmDelete(cfg: StopwatchConfig) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${cfg.name}?")
            .setPositiveButton("Delete") { _, _ ->
                OverlayService.send(this, OverlayService.ACTION_HIDE, cfg.id)
                Store.delete(this, cfg.id)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("Allow display over other apps")
            .setMessage("To float timers on top of other apps, turn on \"Display over other apps\" for Floating Stopwatch on the next screen, then come back.")
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}



/** "Edit Stopwatch" screen (like your 2nd screenshot). */
class EditActivity : Activity() {
    private lateinit var cfg: StopwatchConfig
    private lateinit var col: LinearLayout
    private val subColor = Color.parseColor("#9E9E9E")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Store.get(this, intent.getIntExtra("id", -1)) ?: run { finish(); return }
        title = "Edit Stopwatch"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(col) })
        build()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    /** Save and update the floating window live if it's on screen. */
    private fun save() {
        val fresh = Store.get(this, cfg.id)
        if (fresh != null) { cfg.posX = fresh.posX; cfg.posY = fresh.posY }
        Store.update(this, cfg)
        if (cfg.id in OverlayService.activeIds) {
            OverlayService.send(this, OverlayService.ACTION_REFRESH, cfg.id)
        }
    }

    private fun rebuild() = col.post { build() }

    private fun build() {
        col.removeAllViews()
        textRow(cfg.name, "You can name your stopwatch") { editName() }
        switchRow("Countdown mode", null, cfg.countdown) { cfg.countdown = it; save(); rebuild() }
        if (cfg.countdown) {
            textRow("Countdown time interval", formatDuration(cfg.countdownMs)) { editInterval() }
            switchRow("Vibrate on countdown finish", null, cfg.vibrate) { cfg.vibrate = it; save() }
            switchRow("Repeat when finished", null, cfg.repeat) { cfg.repeat = it; save() }
        }
        sizeRow()
        colorRow("Running color", "Background color while running", cfg.runningColor) {
            cfg.runningColor = it; save(); rebuild()
        }
        colorRow("Pause color", "Background color while paused", cfg.pauseColor) {
            cfg.pauseColor = it; save(); rebuild()
        }
        colorRow("Text color", "Stopwatch text color", cfg.textColor) {
            cfg.textColor = it; save(); rebuild()
        }
        switchRow("Show milliseconds", "Show milliseconds in stopwatch overlay", cfg.showMillis) {
            cfg.showMillis = it; save()
        }
        switchRow("Show hours", "Show hours in stopwatch overlay", cfg.showHours) {
            cfg.showHours = it; save()
        }
        switchRow("Use digital style", "Monospaced digital-looking digits", cfg.digital) {
            cfg.digital = it; save()
        }
    }

    // ---------- row builders ----------

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, dp(14))
        col.addView(this)
    }

    private fun titleBlock(title: String, subtitle: String?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        addView(TextView(this@EditActivity).apply { text = title; textSize = 18f; setTextColor(Color.WHITE) })
        if (subtitle != null) addView(TextView(this@EditActivity).apply {
            text = subtitle; textSize = 15f; setTextColor(subColor)
        })
    }

    private fun textRow(title: String, subtitle: String?, onClick: () -> Unit) {
        val r = row()
        r.addView(titleBlock(title, subtitle))
        r.setOnClickListener { onClick() }
    }

    private fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        val r = row()
        r.addView(titleBlock(title, subtitle))
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
    }

    private fun colorRow(title: String, subtitle: String, color: Int, onPick: (Int) -> Unit) {
        val r = row()
        r.addView(titleBlock(title, subtitle))
        r.addView(View(this).apply { background = swatchDrawable(this@EditActivity, color) },
            LinearLayout.LayoutParams(dp(36), dp(36)))
        r.setOnClickListener { pickColor(color, onPick) }
    }

    private fun sizeRow() {
        col.addView(TextView(this).apply {
            text = "Stopwatch size"; textSize = 18f; setTextColor(Color.WHITE)
            setPadding(0, dp(14), 0, dp(8))
        })
        col.addView(SeekBar(this).apply {
            max = 100
            progress = cfg.size
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) { cfg.size = p; save() }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
    }

    // ---------- dialogs ----------

    private fun editName() {
        val et = EditText(this).apply { setText(cfg.name); setSelection(text.length) }
        val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(this)
            .setTitle("Stopwatch name")
            .setView(wrap)
            .setPositiveButton("OK") { _, _ ->
                cfg.name = et.text.toString().trim().ifBlank { cfg.name }
                save(); build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editInterval() {
        val total = cfg.countdownMs / 1000
        fun picker(maxV: Int, v: Long) = NumberPicker(this).apply {
            minValue = 0; maxValue = maxV; value = v.toInt()
            setFormatter { String.format(Locale.US, "%02d", it) }
        }
        fun colon() = TextView(this).apply { text = ":"; textSize = 24f; setPadding(dp(6), 0, dp(6), 0) }
        val h = picker(23, total / 3600)
        val m = picker(59, (total / 60) % 60)
        val s = picker(59, total % 60)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            addView(h); addView(colon()); addView(m); addView(colon()); addView(s)
        }
        AlertDialog.Builder(this)
            .setTitle("Countdown time (hh:mm:ss)")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                val ms = (h.value * 3600L + m.value * 60L + s.value) * 1000L
                if (ms > 0) { cfg.countdownMs = ms; save(); build() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickColor(current: Int, onPick: (Int) -> Unit) {
        var dialog: AlertDialog? = null
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), 0)
        }
        PALETTE.toList().chunked(5).forEach { rowColors ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowColors.forEach { c ->
                r.addView(View(this).apply {
                    background = swatchDrawable(this@EditActivity, c, c == current)
                    setOnClickListener { onPick(c); dialog?.dismiss() }
                }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            }
            grid.addView(r)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Pick a color")
            .setView(grid)
            .setNegativeButton("Cancel", null)
            .show()
    }
}

