package com.xmanplayer.vsco.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {

    // Строка списка на главном экране: info == null означает пункт SETTINGS
    class Entry(val label: String, val info: ResolveInfo?)

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("vsco", Context.MODE_PRIVATE)
    }

    private lateinit var pixel: Typeface
    private lateinit var allApps: List<ResolveInfo>
    private val labels = HashMap<String, String>()
    private val entries = ArrayList<Entry>()

    // Эффекты для страницы настроек: id и название. Новый эффект добавляется сюда
    // и в список effects внутри класса EffectsView.
    private val effectList = listOf("snow" to "СНЕГ")

    private val textSizes = listOf(12, 14, 16, 18, 20, 24)
    private val clockSizes = listOf(32, 40, 48, 56, 64, 72)
    private val styleNames = listOf("PIXEL", "PIXEL BOLD", "MONO")

    private lateinit var effectsView: EffectsView
    private lateinit var titleView: TextView
    private lateinit var timeView: TextView
    private lateinit var dateView: TextView
    private lateinit var topBattery: BatteryIconView
    private lateinit var bottomBattery: BatteryIconView
    private lateinit var bottomText: TextView
    private lateinit var bottomBox: LinearLayout
    private lateinit var bottomLine: View
    private lateinit var list: ListView
    private lateinit var messagesPanel: View
    private lateinit var settingsScroll: ScrollView
    private lateinit var settingsBody: LinearLayout
    private lateinit var appsScroll: ScrollView
    private lateinit var appsBody: LinearLayout

    private var panelWidth = 0
    private var messagesOpen = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateInfo()
            handler.postDelayed(this, 15000L)
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(p: Int) = entries[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val tv = (v as? TextView) ?: pixelText("", 16f)
            tv.text = entries[p].label
            styleListText(tv)
            tv.setPadding(dp(18), dp(11), dp(16), dp(11))
            return tv
        }
    }

    // ---------- Общие помощники ----------

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

    // Размер и стиль текста списка приложений (берутся из настроек)
    private fun styleListText(tv: TextView) {
        val style = prefs.getInt("text_style", 0)
        tv.typeface = if (style == 2) Typeface.MONOSPACE else pixel
        tv.paint.isFakeBoldText = (style == 1)
        tv.textSize = prefs.getInt("text_size", 16).toFloat()
        tv.setTextColor(Color.WHITE)
        tv.paint.isAntiAlias = false
    }

    private fun keyOf(ri: ResolveInfo) = ri.activityInfo.packageName + "/" + ri.activityInfo.name

    private fun labelOf(ri: ResolveInfo) = labels[keyOf(ri)] ?: ""

    // ---------- Создание экрана ----------

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
        allApps = pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        for (ri in allApps) {
            labels[keyOf(ri)] = ri.loadLabel(pm).toString().uppercase()
        }

        // Слои снизу вверх: фон и эффекты, главный экран, панель сообщений, страницы настроек
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)

        effectsView = EffectsView(this, prefs)
        frame.addView(effectsView, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(buildHome(), FrameLayout.LayoutParams(MATCH, MATCH))
        buildMessages(frame)
        buildPages(frame)

        setContentView(frame)
        applyPrefs()
    }

    private fun buildHome(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true

        // Верхняя строка: значок батареи слева, название по центру
        val top = FrameLayout(this)
        top.layoutParams = LinearLayout.LayoutParams(MATCH, dp(52))
        topBattery = BatteryIconView(this)
        val batteryParams = FrameLayout.LayoutParams(
            dp(30), dp(14), Gravity.START or Gravity.CENTER_VERTICAL
        )
        batteryParams.setMargins(dp(16), 0, 0, 0)
        top.addView(topBattery, batteryParams)
        titleView = pixelText("VSCO", 14f)
        top.addView(titleView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.addView(top)
        root.addView(line())

        // Большие часы и дата
        val clockBox = LinearLayout(this)
        clockBox.orientation = LinearLayout.VERTICAL
        clockBox.setPadding(dp(16), dp(10), dp(16), dp(10))
        timeView = pixelText("", 48f)
        dateView = pixelText("", 14f)
        clockBox.addView(timeView)
        clockBox.addView(dateView)
        root.addView(clockBox, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(line())

        // Список приложений текстом, без иконок. Фон прозрачный, чтобы был виден снег.
        list = ListView(this)
        list.divider = null
        list.dividerHeight = 0
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.cacheColorHint = Color.TRANSPARENT
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val info = entries[pos].info
            if (info == null) {
                showSettings()
            } else {
                val ai = info.activityInfo
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
        }
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Нижняя строка: маленький значок батареи и заряд
        bottomLine = line()
        root.addView(bottomLine)
        bottomBox = LinearLayout(this)
        bottomBox.orientation = LinearLayout.HORIZONTAL
        bottomBox.gravity = Gravity.CENTER_VERTICAL
        bottomBox.setPadding(dp(16), dp(12), dp(16), dp(12))
        bottomBattery = BatteryIconView(this)
        bottomBox.addView(bottomBattery, LinearLayout.LayoutParams(dp(22), dp(11)))
        bottomText = pixelText("BATTERY", 12f)
        bottomText.setPadding(dp(8), 0, 0, 0)
        bottomBox.addView(bottomText)
        root.addView(bottomBox, LinearLayout.LayoutParams(MATCH, WRAP))

        return root
    }

    // Панель сообщений справа: свайп влево открывает, свайп вправо закрывает
    private fun buildMessages(frame: FrameLayout) {
        panelWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.HORIZONTAL
        panel.setBackgroundColor(Color.BLACK)
        panel.isClickable = true
        panel.minimumHeight = dp(160)

        val border = View(this)
        border.setBackgroundColor(Color.WHITE)
        panel.addView(border, LinearLayout.LayoutParams(dp(2), MATCH))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        val head = pixelText("СООБЩЕНИЯ", 14f)
        head.setPadding(dp(14), dp(12), dp(14), dp(12))
        content.addView(head)
        content.addView(line())
        // Настоящие сообщения здесь пока не показываются: для этого нужно отдельное
        // разрешение Android. Панель честно сообщает об этом.
        val body = pixelText(
            "ДОСТУП К СООБЩЕНИЯМ НЕ ПОДКЛЮЧЕН.\n\nДЛЯ ЧТЕНИЯ НУЖНО ОТДЕЛЬНОЕ РАЗРЕШЕНИЕ ANDROID.",
            12f
        )
        body.setPadding(dp(14), dp(12), dp(14), dp(12))
        content.addView(body)
        content.addView(line())
        panel.addView(content, LinearLayout.LayoutParams(0, WRAP, 1f))

        val params = FrameLayout.LayoutParams(panelWidth, WRAP, Gravity.END or Gravity.TOP)
        params.setMargins(0, dp(160), 0, 0)
        panel.translationX = panelWidth.toFloat()
        messagesPanel = panel
        frame.addView(panel, params)
    }

    private fun buildPages(frame: FrameLayout) {
        settingsScroll = ScrollView(this)
        settingsScroll.setBackgroundColor(Color.BLACK)
        settingsBody = LinearLayout(this)
        settingsBody.orientation = LinearLayout.VERTICAL
        settingsScroll.addView(settingsBody, FrameLayout.LayoutParams(MATCH, WRAP))
        settingsScroll.visibility = View.GONE
        frame.addView(settingsScroll, FrameLayout.LayoutParams(MATCH, MATCH))

        appsScroll = ScrollView(this)
        appsScroll.setBackgroundColor(Color.BLACK)
        appsBody = LinearLayout(this)
        appsBody.orientation = LinearLayout.VERTICAL
        appsScroll.addView(appsBody, FrameLayout.LayoutParams(MATCH, WRAP))
        appsScroll.visibility = View.GONE
        frame.addView(appsScroll, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ---------- Применение настроек ----------

    private fun applyPrefs() {
        titleView.text = prefs.getString("title", "VSCO") ?: "VSCO"
        timeView.textSize = prefs.getInt("clock_size", 48).toFloat()
        dateView.visibility =
            if (prefs.getBoolean("show_date", true)) View.VISIBLE else View.GONE
        val showBattery = prefs.getBoolean("show_battery", true)
        topBattery.visibility = if (showBattery) View.VISIBLE else View.INVISIBLE
        bottomLine.visibility = if (showBattery) View.VISIBLE else View.GONE
        bottomBox.visibility = if (showBattery) View.VISIBLE else View.GONE
        effectsView.refresh()
        rebuildEntries()
    }

    private fun chosenKeys(): List<String> =
        (prefs.getString("apps_order", "") ?: "").split("\n").filter { it.isNotEmpty() }

    private fun saveChosen(keys: List<String>) {
        prefs.edit().putString("apps_order", keys.joinToString("\n")).apply()
        rebuildEntries()
    }

    // Если приложения не выбраны, показываем все по алфавиту. В конце всегда SETTINGS.
    private fun rebuildEntries() {
        entries.clear()
        val byKey = allApps.associateBy { keyOf(it) }
        val chosen = chosenKeys().filter { byKey.containsKey(it) }
        val source = if (chosen.isEmpty()) allApps else chosen.map { byKey[it]!! }
        for (ri in source) entries.add(Entry(labelOf(ri), ri))
        entries.add(Entry("SETTINGS", null))
        adapter.notifyDataSetChanged()
    }

    // ---------- Часы, дата, батарея ----------

    private fun updateInfo() {
        val now = Date()
        timeView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        dateView.text = SimpleDateFormat("EEE d MMM", Locale.forLanguageTag("ru"))
            .format(now).uppercase()

        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        topBattery.level = pct
        bottomBattery.level = pct
        bottomText.text = "BATTERY $pct%"
    }

    // ---------- Панель сообщений ----------

    private fun openMessages() {
        if (messagesOpen) return
        messagesOpen = true
        messagesPanel.animate()
            .translationX(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun closeMessages() {
        if (!messagesOpen) return
        messagesOpen = false
        messagesPanel.animate()
            .translationX(panelWidth.toFloat())
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // ---------- Страница SETTINGS ----------

    private fun row(left: String, right: String, onClick: () -> Unit): View {
        val r = FrameLayout(this)
        r.setPadding(dp(16), dp(14), dp(16), dp(14))
        r.addView(
            pixelText(left, 14f),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL)
        )
        if (right.isNotEmpty()) {
            r.addView(
                pixelText(right, 14f),
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL)
            )
        }
        r.setOnClickListener { onClick() }
        return r
    }

    private fun sectionTitle(text: String): View {
        val tv = pixelText("# $text", 11f)
        tv.setPadding(dp(16), dp(14), dp(16), dp(4))
        return tv
    }

    private fun cycle(values: List<Int>, current: Int): Int {
        val i = values.indexOf(current)
        return values[(i + 1) % values.size]
    }

    private fun onOff(on: Boolean) = if (on) "[ВКЛ]" else "[ВЫКЛ]"

    private fun renderSettings() {
        settingsBody.removeAllViews()
        settingsBody.addView(row("◄ SETTINGS", "") { showHome() })
        settingsBody.addView(line())

        settingsBody.addView(sectionTitle("ЭФФЕКТЫ"))
        for ((id, name) in effectList) {
            val on = prefs.getBoolean("fx_$id", true)
            settingsBody.addView(row(name, onOff(on)) {
                prefs.edit().putBoolean("fx_$id", !on).apply()
                effectsView.refresh()
                renderSettings()
            })
        }
        settingsBody.addView(line())

        settingsBody.addView(sectionTitle("ТЕКСТ"))
        val textSize = prefs.getInt("text_size", 16)
        settingsBody.addView(row("РАЗМЕР СПИСКА", "[$textSize]") {
            prefs.edit().putInt("text_size", cycle(textSizes, textSize)).apply()
            adapter.notifyDataSetChanged()
            renderSettings()
        })
        val style = prefs.getInt("text_style", 0).coerceIn(0, 2)
        settingsBody.addView(row("СТИЛЬ ТЕКСТА", "[" + styleNames[style] + "]") {
            prefs.edit().putInt("text_style", (style + 1) % 3).apply()
            adapter.notifyDataSetChanged()
            renderSettings()
        })
        val clockSize = prefs.getInt("clock_size", 48)
        settingsBody.addView(row("РАЗМЕР ЧАСОВ", "[$clockSize]") {
            prefs.edit().putInt("clock_size", cycle(clockSizes, clockSize)).apply()
            applyPrefs()
            renderSettings()
        })
        settingsBody.addView(line())

        settingsBody.addView(sectionTitle("ЭКРАН"))
        val currentTitle = prefs.getString("title", "VSCO") ?: "VSCO"
        settingsBody.addView(row("НАЗВАНИЕ", "[$currentTitle]") { askTitle() })
        val showDate = prefs.getBoolean("show_date", true)
        settingsBody.addView(row("ДАТА", onOff(showDate)) {
            prefs.edit().putBoolean("show_date", !showDate).apply()
            applyPrefs()
            renderSettings()
        })
        val showBattery = prefs.getBoolean("show_battery", true)
        settingsBody.addView(row("БАТАРЕЯ", onOff(showBattery)) {
            prefs.edit().putBoolean("show_battery", !showBattery).apply()
            applyPrefs()
            renderSettings()
        })
        settingsBody.addView(line())

        settingsBody.addView(sectionTitle("ПРИЛОЖЕНИЯ"))
        val count = chosenKeys().size
        settingsBody.addView(row("ВЫБОР И ПОРЯДОК", if (count == 0) "[ВСЕ]" else "[$count]") {
            showApps()
        })
    }

    private fun askTitle() {
        val input = EditText(this)
        input.setText(prefs.getString("title", "VSCO"))
        input.setSingleLine(true)
        AlertDialog.Builder(this)
            .setTitle("НАЗВАНИЕ")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val t = input.text.toString().trim()
                prefs.edit().putString("title", if (t.isEmpty()) "VSCO" else t).apply()
                applyPrefs()
                renderSettings()
            }
            .setNegativeButton("ОТМЕНА", null)
            .show()
    }

    private fun showSettings() {
        closeMessages()
        renderSettings()
        settingsScroll.visibility = View.VISIBLE
        appsScroll.visibility = View.GONE
    }

    private fun showHome() {
        settingsScroll.visibility = View.GONE
        appsScroll.visibility = View.GONE
        applyPrefs()
    }

    // ---------- Страница выбора приложений и порядка ----------

    private fun showApps() {
        renderAppsPage()
        appsScroll.visibility = View.VISIBLE
    }

    private fun closeApps() {
        appsScroll.visibility = View.GONE
        renderSettings()
    }

    private fun swap(src: List<String>, a: Int, b: Int): List<String> {
        val m = src.toMutableList()
        val t = m[a]
        m[a] = m[b]
        m[b] = t
        return m
    }

    private fun renderAppsPage() {
        val keepY = appsScroll.scrollY
        appsBody.removeAllViews()
        appsBody.addView(row("◄ НАЗАД", "") { closeApps() })
        appsBody.addView(line())
        appsBody.addView(row("СБРОС: ПОКАЗАТЬ ВСЕ", "") {
            saveChosen(emptyList())
            renderAppsPage()
        })
        appsBody.addView(line())

        val byKey = allApps.associateBy { keyOf(it) }
        val chosen = chosenKeys().filter { byKey.containsKey(it) }
        for ((i, k) in chosen.withIndex()) {
            appsBody.addView(appRow(byKey[k]!!, true, i, chosen))
        }
        val rest = allApps.filter { !chosen.contains(keyOf(it)) }
        for (ri in rest) {
            appsBody.addView(appRow(ri, false, -1, chosen))
        }
        appsScroll.post { appsScroll.scrollTo(0, keepY) }
    }

    // Строка приложения: касание включает или выключает, стрелки двигают вверх и вниз
    private fun appRow(ri: ResolveInfo, selected: Boolean, index: Int, chosen: List<String>): View {
        val k = keyOf(ri)
        val r = FrameLayout(this)
        r.setPadding(dp(16), dp(10), dp(8), dp(10))

        val name = pixelText((if (selected) "[X] " else "[ ] ") + labelOf(ri), 14f)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        val nameParams = FrameLayout.LayoutParams(
            WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL
        )
        nameParams.setMargins(0, 0, if (selected) dp(90) else 0, 0)
        r.addView(name, nameParams)

        r.setOnClickListener {
            val newList = if (selected) chosen.filter { it != k } else chosen + k
            saveChosen(newList)
            renderAppsPage()
        }

        if (selected) {
            val arrows = LinearLayout(this)
            arrows.orientation = LinearLayout.HORIZONTAL

            val up = pixelText("▲", 16f)
            up.setPadding(dp(14), dp(4), dp(14), dp(4))
            up.setOnClickListener {
                if (index > 0) {
                    saveChosen(swap(chosen, index, index - 1))
                    renderAppsPage()
                }
            }

            val down = pixelText("▼", 16f)
            down.setPadding(dp(14), dp(4), dp(14), dp(4))
            down.setOnClickListener {
                if (index < chosen.size - 1) {
                    saveChosen(swap(chosen, index, index + 1))
                    renderAppsPage()
                }
            }

            arrows.addView(up)
            arrows.addView(down)
            r.addView(
                arrows,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL)
            )
        }
        return r
    }

    // ---------- Жесты и жизненный цикл ----------

    // Горизонтальный свайп на главном экране открывает и закрывает панель сообщений.
    // События не перехватываются, поэтому прокрутка списка не страдает.
    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
            ): Boolean {
                if (e1 == null) return false
                if (settingsScroll.visibility == View.VISIBLE) return false
                val dx = e2.x - e1.x
                if (abs(dx) > dp(80) && abs(vx) > abs(vy) * 2) {
                    if (dx < 0) openMessages() else closeMessages()
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
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
        // Кнопка "Домой": закрываем настройки и сообщения, список возвращаем наверх
        if (::settingsScroll.isInitialized) showHome()
        if (::messagesPanel.isInitialized) closeMessages()
        if (::list.isInitialized) list.setSelection(0)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            appsScroll.visibility == View.VISIBLE -> closeApps()
            settingsScroll.visibility == View.VISIBLE -> showHome()
            messagesOpen -> closeMessages()
        }
    }
}

// ---------- Фоновые эффекты ----------

// Эффект рисуется на заднем плане. Чтобы добавить новый: создать класс с интерфейсом Effect,
// вписать его в список effects у EffectsView и в effectList у MainActivity.
interface Effect {
    val id: String
    fun draw(canvas: Canvas, w: Int, h: Int, dt: Float, d: Float)
}

// Снег: мелкие белые точки-пиксели медленно падают с лёгким покачиванием
class SnowEffect : Effect {
    override val id = "snow"

    private class Dot(
        var x: Float, var y: Float, val size: Float, val speed: Float,
        var phase: Float, val sway: Float, val alpha: Int
    )

    private val dots = ArrayList<Dot>()
    private val paint = Paint()
    private val rnd = java.util.Random()

    init {
        paint.isAntiAlias = false
        paint.color = Color.WHITE
    }

    private fun newDot(w: Int, h: Int, d: Float, randomY: Boolean): Dot {
        val size = (1.5f + rnd.nextFloat() * 1.5f) * d      // 1.5-3 dp
        val speed = (18f + rnd.nextFloat() * 30f) * d       // dp в секунду, медленно
        val sway = (4f + rnd.nextFloat() * 10f) * d
        val alpha = 90 + rnd.nextInt(110)                   // приглушённая яркость
        val y = if (randomY) rnd.nextFloat() * h else -size
        return Dot(rnd.nextFloat() * w, y, size, speed, rnd.nextFloat() * 6.28f, sway, alpha)
    }

    override fun draw(canvas: Canvas, w: Int, h: Int, dt: Float, d: Float) {
        while (dots.size < 45) dots.add(newDot(w, h, d, true))
        for (f in dots) {
            f.y += f.speed * dt
            f.phase += dt * 0.8f
            if (f.y > h) {
                f.y = -f.size
                f.x = rnd.nextFloat() * w
            }
            val x = f.x + sin(f.phase) * f.sway
            paint.alpha = f.alpha
            canvas.drawRect(x, f.y, x + f.size, f.y + f.size, paint)
        }
    }
}

// Слой эффектов: самый нижний, не принимает касания, рисует только включённые эффекты
class EffectsView(context: Context, private val prefs: SharedPreferences) : View(context) {

    private val effects: List<Effect> = listOf(SnowEffect())
    private val d = resources.displayMetrics.density
    private var last = 0L
    private var active: List<Effect> = loadActive()

    init {
        isClickable = false
        isFocusable = false
    }

    private fun loadActive(): List<Effect> =
        effects.filter { prefs.getBoolean("fx_" + it.id, true) }

    // Вызывать после изменения настроек эффектов
    fun refresh() {
        active = loadActive()
        last = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0 || active.isEmpty()) return

        val now = System.nanoTime()
        var dt = if (last == 0L) 0f else (now - last) / 1000000000f
        last = now
        if (dt > 0.05f) dt = 0.05f

        for (e in active) e.draw(canvas, width, height, dt, d)
        postInvalidateOnAnimation()
    }
}

// ---------- Значок батареи в 1-битном стиле ----------

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