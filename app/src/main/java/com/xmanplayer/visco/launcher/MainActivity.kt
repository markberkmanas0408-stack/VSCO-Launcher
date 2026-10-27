package com.xmanplayer.visco.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {

    // Текст сообщения в плашке TODAY (поменяй на свой)
    private val todayMessage = "СООБЩЕНИЙ НЕТ"

    private lateinit var pixel: Typeface
    private lateinit var apps: List<ResolveInfo>
    private lateinit var batteryIcon: BatteryIconView
    private lateinit var batteryText: TextView
    private lateinit var timeView: TextView
    private lateinit var dateView: TextView
    private lateinit var todayArrow: TextView
    private lateinit var todayPanel: TextView
    private lateinit var list: ListView

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateInfo()
            handler.postDelayed(this, 15000L)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pixelText(text: String, sizeSp: Float): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.typeface = pixel
        tv.textSize = sizeSp
        tv.setTextColor(Color.WHITE)
        tv.paint.isAntiAlias = false
        return tv
    }

    private fun line(): View {
        val v = View(this)
        v.setBackgroundColor(Color.WHITE)
        v.layoutParams = LinearLayout.LayoutParams(MATCH, dp(2))
        return v
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        // Шрифт из assets/pixel.ttf (если файла нет, берём обычный моноширинный)
        pixel = try {
            Typeface.createFromAsset(assets, "pixel.ttf")
        } catch (e: Exception) {
            Typeface.MONOSPACE
        }

        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        // Нижний слой: чёрный фон и снег. Сверху лежит весь интерфейс.
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        frame.addView(SnowView(this), FrameLayout.LayoutParams(MATCH, MATCH))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true

        // Верхняя строка: батарея слева, VSCO по центру
        val top = FrameLayout(this)
        top.layoutParams = LinearLayout.LayoutParams(MATCH, dp(56))

        val batteryBox = LinearLayout(this)
        batteryBox.orientation = LinearLayout.HORIZONTAL
        batteryBox.gravity = Gravity.CENTER_VERTICAL
        batteryIcon = BatteryIconView(this)
        batteryBox.addView(batteryIcon, LinearLayout.LayoutParams(dp(30), dp(14)))
        batteryText = pixelText("", 12f)
        batteryText.setPadding(dp(8), 0, 0, 0)
        batteryBox.addView(batteryText)
        val batteryParams = FrameLayout.LayoutParams(
            WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL
        )
        batteryParams.setMargins(dp(16), 0, 0, 0)
        top.addView(batteryBox, batteryParams)

        val title = pixelText("VSCO", 14f)
        top.addView(title, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        root.addView(top)
        root.addView(line())

        // Большие часы и дата
        val clockBox = LinearLayout(this)
        clockBox.orientation = LinearLayout.VERTICAL
        clockBox.setPadding(dp(16), dp(12), dp(16), dp(12))
        timeView = pixelText("", 48f)
        dateView = pixelText("", 14f)
        clockBox.addView(timeView)
        clockBox.addView(dateView)
        root.addView(clockBox, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(line())

        // Плашка TODAY: свайп влево или вправо (или касание) открывает и закрывает сообщение
        val todayBar = FrameLayout(this)
        todayBar.setBackgroundColor(Color.WHITE)

        val todayLabel = pixelText("TODAY", 14f)
        todayLabel.setTextColor(Color.BLACK)
        val labelParams = FrameLayout.LayoutParams(
            WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL
        )
        labelParams.setMargins(dp(16), 0, 0, 0)
        todayBar.addView(todayLabel, labelParams)

        todayArrow = pixelText("►", 14f)
        todayArrow.setTextColor(Color.BLACK)
        val arrowParams = FrameLayout.LayoutParams(
            WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL
        )
        arrowParams.setMargins(0, 0, dp(16), 0)
        todayBar.addView(todayArrow, arrowParams)

        val barGestures = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    toggleToday()
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
                ): Boolean {
                    if (e1 != null && abs(e2.x - e1.x) > dp(60) && abs(vx) > abs(vy)) {
                        toggleToday()
                        return true
                    }
                    return false
                }
            }
        )
        todayBar.setOnTouchListener { _, ev -> barGestures.onTouchEvent(ev) }
        root.addView(todayBar, LinearLayout.LayoutParams(MATCH, dp(52)))

        todayPanel = pixelText(todayMessage, 14f)
        todayPanel.setPadding(dp(16), dp(14), dp(16), dp(14))
        todayPanel.visibility = View.GONE
        root.addView(todayPanel, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(line())

        // Список приложений текстом (без иконок, фон прозрачный, чтобы был виден снег)
        list = ListView(this)
        list.divider = null
        list.dividerHeight = 0
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.cacheColorHint = Color.TRANSPARENT
        list.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(p: Int) = apps[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val tv = (v as? TextView) ?: pixelText("", 16f)
                tv.text = apps[p].loadLabel(pm).toString().uppercase()
                tv.setPadding(dp(18), dp(14), dp(16), dp(14))
                return tv
            }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            val info = apps[pos].activityInfo
            val launch = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(info.packageName, info.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        }
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))

        frame.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(frame)
    }

    private fun toggleToday() {
        val open = todayPanel.visibility != View.VISIBLE
        todayPanel.visibility = if (open) View.VISIBLE else View.GONE
        todayArrow.text = if (open) "▼" else "►"
    }

    private fun updateInfo() {
        val now = Date()
        timeView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        dateView.text = SimpleDateFormat("EEE d MMM", Locale.forLanguageTag("ru"))
            .format(now).uppercase()

        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        batteryIcon.level = pct
        batteryText.text = "$pct%"
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Кнопка "Домой": список наверх, сообщение закрывается
        if (::list.isInitialized) list.setSelection(0)
        if (::todayPanel.isInitialized && todayPanel.visibility == View.VISIBLE) toggleToday()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (todayPanel.visibility == View.VISIBLE) toggleToday()
    }
}

// Снег: мелкие белые точки-пиксели медленно падают. Не принимает касания.
class SnowView(context: Context) : View(context) {

    private class Dot(
        var x: Float, var y: Float, val size: Float, val speed: Float,
        var phase: Float, val sway: Float, val alpha: Int
    )

    private val dots = ArrayList<Dot>()
    private val paint = Paint()
    private val rnd = java.util.Random()
    private val d = resources.displayMetrics.density
    private var last = 0L

    init {
        paint.isAntiAlias = false
        paint.color = Color.WHITE
        isClickable = false
        isFocusable = false
    }

    private fun newDot(randomY: Boolean): Dot {
        val size = (1.5f + rnd.nextFloat() * 1.5f) * d      // 1.5-3 dp
        val speed = (18f + rnd.nextFloat() * 30f) * d       // dp в секунду, медленно
        val sway = (4f + rnd.nextFloat() * 10f) * d         // лёгкое покачивание
        val alpha = 90 + rnd.nextInt(110)                   // приглушённая яркость
        val y = if (randomY) rnd.nextFloat() * height else -size
        return Dot(rnd.nextFloat() * width, y, size, speed, rnd.nextFloat() * 6.28f, sway, alpha)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        while (dots.size < 45) dots.add(newDot(true))

        val now = System.nanoTime()
        var dt = if (last == 0L) 0f else (now - last) / 1000000000f
        last = now
        if (dt > 0.05f) dt = 0.05f

        for (f in dots) {
            f.y += f.speed * dt
            f.phase += dt * 0.8f
            if (f.y > height) {
                f.y = -f.size
                f.x = rnd.nextFloat() * width
            }
            val x = f.x + sin(f.phase) * f.sway
            paint.alpha = f.alpha
            canvas.drawRect(x, f.y, x + f.size, f.y + f.size, paint)
        }
        postInvalidateOnAnimation()
    }
}

// Значок батареи в 1-битном стиле: белая рамка и заливка по уровню заряда
class BatteryIconView(context: Context) : View(context) {

    var level = 0
        set(value) {
            field = value
            invalidate()
        }

    private val p = Paint()

    init {
        p.isAntiAlias = false
        p.color = Color.WHITE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = resources.displayMetrics.density * 2f
        val w = width.toFloat()
        val h = height.toFloat()
        val bodyW = w - s * 2f

        p.style = Paint.Style.STROKE
        p.strokeWidth = s
        canvas.drawRect(s / 2f, s / 2f, bodyW - s / 2f, h - s / 2f, p)

        p.style = Paint.Style.FILL
        canvas.drawRect(bodyW, h * 0.3f, w, h * 0.7f, p)

        val inner = bodyW - s * 4f
        val fillW = inner * level / 100f
        canvas.drawRect(s * 2f, s * 2f, s * 2f + fillW, h - s * 2f, p)
    }
}