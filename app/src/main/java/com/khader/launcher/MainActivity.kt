package com.khader.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * لانشر خضر - Khader Launcher
 * Developer: خضر أبو صالحه  <mankhader@gmail.com>
 */
class VH(val cell: LinearLayout, val iv: ImageView, val tv: TextView) : RecyclerView.ViewHolder(cell)

class DotsView(ctx: Context, private val bg: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = bg
        canvas.drawRoundRect(0f, 0f, w, h, w * 0.3f, w * 0.3f, p)
        p.color = Color.WHITE
        for (i in 0..2) for (j in 0..2) {
            canvas.drawCircle(w * (0.28f + 0.22f * i), h * (0.28f + 0.22f * j), w * 0.055f, p)
        }
    }
}

class MainActivity : Activity() {

    data class AppItem(val label: String, val pkg: String, val intent: Intent, val icon: Drawable)

    private lateinit var prefs: SharedPreferences
    private lateinit var counts: SharedPreferences
    private var apps: List<AppItem> = emptyList()

    private lateinit var home: LinearLayout
    private lateinit var dockBar: LinearLayout
    private lateinit var usedRow: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var searchBox: EditText
    private lateinit var batteryView: TextView
    private lateinit var adapter: AppAdapter
    private lateinit var detector: GestureDetector
    private var drawerOpen = false
    private var receiverRegistered = false

    private val accents = intArrayOf(
        c(0xFF7C4DFF), c(0xFF00BFA5), c(0xFFFF6D00), c(0xFFFF4081), c(0xFF2979FF)
    )
    private val accentNames = arrayOf("بنفسجي", "فيروزي", "برتقالي", "وردي", "أزرق")
    private val accent: Int
        get() = accents[prefs.getInt("accent", 0).coerceIn(0, accents.size - 1)]

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadApps()
        }
    }

    // ---------- helpers ----------
    private fun c(v: Long): Int = v.toInt()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun glass(radius: Int, fill: Int = c(0x26FFFFFF), stroke: Int = c(0x33FFFFFF)): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(radius).toFloat()
        g.setStroke(dp(1), stroke)
        return g
    }

    private fun fresh(d: Drawable): Drawable = d.constantState?.newDrawable()?.mutate() ?: d

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        counts = getSharedPreferences("counts", MODE_PRIVATE)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dy) < abs(dx)) return false
                if (!drawerOpen && vy < -800 && dy < -dp(80)) {
                    openDrawer(false); return true
                }
                if (!drawerOpen && vy > 800 && dy > dp(80)) {
                    expandStatusBar(); return true
                }
                if (drawerOpen && vy > 1500 && dy > dp(120) && !list.canScrollVertically(-1)) {
                    closeDrawer(); return true
                }
                return false
            }
        })

        setContentView(buildUi())
        loadApps()

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pkgReceiver, f)
        }
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        updateBattery()
        refreshHome()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            try { unregisterReceiver(pkgReceiver) } catch (e: Exception) { }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (drawerOpen) closeDrawer()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (drawerOpen) closeDrawer()
        // launcher: do nothing on back from home
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ---------- UI ----------
    private fun buildUi(): View {
        val M = ViewGroup.LayoutParams.MATCH_PARENT
        val W = ViewGroup.LayoutParams.WRAP_CONTENT

        val outer = FrameLayout(this)
        outer.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(c(0x40000000), c(0xAA000000))
        )
        val content = FrameLayout(this)
        content.fitsSystemWindows = true
        outer.addView(content, FrameLayout.LayoutParams(M, M))

        // ----- home -----
        home = LinearLayout(this)
        home.orientation = LinearLayout.VERTICAL
        home.setPadding(dp(18), dp(36), dp(18), dp(14))
        home.isLongClickable = true
        home.setOnLongClickListener { showHomeMenu(); true }

        val clock = TextClock(this)
        clock.format12Hour = "hh:mm"
        clock.format24Hour = "HH:mm"
        clock.textSize = 80f
        clock.setTextColor(Color.WHITE)
        clock.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        clock.gravity = Gravity.CENTER
        home.addView(clock, lp(M, W))

        val date = TextClock(this)
        date.format12Hour = "EEEE d MMMM"
        date.format24Hour = "EEEE d MMMM"
        date.textSize = 17f
        date.setTextColor(accent)
        date.gravity = Gravity.CENTER
        home.addView(date, lp(M, W))

        batteryView = TextView(this)
        batteryView.textSize = 12f
        batteryView.setTextColor(Color.WHITE)
        batteryView.gravity = Gravity.CENTER
        batteryView.background = glass(14)
        batteryView.setPadding(dp(14), dp(5), dp(14), dp(5))
        val bl = lp(W, W)
        bl.gravity = Gravity.CENTER_HORIZONTAL
        bl.topMargin = dp(12)
        home.addView(batteryView, bl)

        home.addView(View(this), lp(M, 0, 1f))

        // most-used card
        val usedCard = LinearLayout(this)
        usedCard.orientation = LinearLayout.VERTICAL
        usedCard.background = glass(28)
        usedCard.setPadding(dp(14), dp(12), dp(14), dp(10))
        val usedTitle = TextView(this)
        usedTitle.text = "الأكثر استخداماً"
        usedTitle.textSize = 12f
        usedTitle.setTextColor(c(0xAAFFFFFF))
        usedCard.addView(usedTitle, lp(W, W))
        usedRow = LinearLayout(this)
        usedRow.orientation = LinearLayout.HORIZONTAL
        usedRow.gravity = Gravity.CENTER
        val ur = lp(M, W)
        ur.topMargin = dp(6)
        usedCard.addView(usedRow, ur)
        home.addView(usedCard, lp(M, W))

        // search pill
        val pill = TextView(this)
        pill.text = "🔍   ابحث في تطبيقاتك"
        pill.textSize = 15f
        pill.setTextColor(c(0xCCFFFFFF))
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.setPadding(dp(20), 0, dp(20), 0)
        pill.background = glass(26)
        pill.setOnClickListener { openDrawer(true) }
        val pl = lp(M, dp(52))
        pl.topMargin = dp(14)
        home.addView(pill, pl)

        // dock
        dockBar = LinearLayout(this)
        dockBar.orientation = LinearLayout.HORIZONTAL
        dockBar.gravity = Gravity.CENTER
        dockBar.background = glass(32, c(0x40FFFFFF), c(0x44FFFFFF))
        dockBar.setPadding(dp(8), dp(10), dp(8), dp(10))
        val dl = lp(M, W)
        dl.topMargin = dp(14)
        home.addView(dockBar, dl)

        content.addView(home, FrameLayout.LayoutParams(M, M))

        // ----- drawer -----
        drawer = LinearLayout(this)
        drawer.orientation = LinearLayout.VERTICAL
        drawer.setBackgroundColor(c(0xF00B0F1A))
        drawer.visibility = View.GONE
        drawer.setPadding(dp(12), dp(10), dp(12), 0)
        // swallow touches so they don't reach the home layer
        drawer.isClickable = true

        val handle = View(this)
        handle.background = glass(3, c(0x66FFFFFF), c(0x00000000))
        val hl = lp(dp(44), dp(5))
        hl.gravity = Gravity.CENTER_HORIZONTAL
        hl.bottomMargin = dp(12)
        handle.setOnClickListener { closeDrawer() }
        drawer.addView(handle, hl)

        searchBox = EditText(this)
        searchBox.hint = "ابحث عن تطبيق"
        searchBox.setHintTextColor(c(0x88FFFFFF))
        searchBox.setTextColor(Color.WHITE)
        searchBox.textSize = 16f
        searchBox.background = glass(26)
        searchBox.setPadding(dp(20), 0, dp(20), 0)
        searchBox.isSingleLine = true
        searchBox.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString() ?: "")
            }
        })
        searchBox.setOnEditorActionListener { _, _, _ ->
            val first = adapter.first()
            if (first != null) launch(first)
            true
        }
        val sl = lp(M, dp(50))
        sl.bottomMargin = dp(10)
        drawer.addView(searchBox, sl)

        adapter = AppAdapter()
        list = RecyclerView(this)
        list.layoutManager = GridLayoutManager(this, 4)
        list.adapter = adapter
        list.clipToPadding = false
        list.setPadding(0, dp(4), 0, dp(12))
        list.overScrollMode = View.OVER_SCROLL_NEVER
        drawer.addView(list, lp(M, 0, 1f))

        val credit = TextView(this)
        credit.text = "تطوير: خضر أبو صالحه  •  mankhader@gmail.com"
        credit.textSize = 10f
        credit.setTextColor(c(0x66FFFFFF))
        credit.gravity = Gravity.CENTER
        credit.setPadding(0, dp(6), 0, dp(8))
        drawer.addView(credit, lp(M, W))

        content.addView(drawer, FrameLayout.LayoutParams(M, M))
        return outer
    }

    private fun pressFeedback(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    private fun iconCell(app: AppItem, size: Int, label: Boolean): View {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER
        cell.setPadding(dp(4), dp(6), dp(4), dp(6))
        val iv = ImageView(this)
        iv.setImageDrawable(fresh(app.icon))
        cell.addView(iv, lp(dp(size), dp(size)))
        if (label) {
            val tv = TextView(this)
            tv.text = app.label
            tv.setTextColor(Color.WHITE)
            tv.textSize = 11f
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.gravity = Gravity.CENTER
            val tl = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tl.topMargin = dp(4)
            cell.addView(tv, tl)
        }
        cell.setOnClickListener { launch(app) }
        cell.setOnLongClickListener { showAppMenu(app); true }
        pressFeedback(cell)
        return cell
    }

    private fun drawerButton(): View {
        val cell = LinearLayout(this)
        cell.gravity = Gravity.CENTER
        cell.setPadding(dp(4), dp(6), dp(4), dp(6))
        cell.addView(DotsView(this, accent), lp(dp(54), dp(54)))
        cell.setOnClickListener { openDrawer(false) }
        pressFeedback(cell)
        return cell
    }

    private fun refreshHome() {
        if (!::dockBar.isInitialized) return
        val W = ViewGroup.LayoutParams.WRAP_CONTENT
        dockBar.removeAllViews()
        for (p in dockPkgs()) {
            val app = apps.firstOrNull { it.pkg == p } ?: continue
            dockBar.addView(iconCell(app, 54, false), lp(0, W, 1f))
        }
        dockBar.addView(drawerButton(), lp(0, W, 1f))

        usedRow.removeAllViews()
        val top = apps.filter { counts.getInt(it.pkg, 0) > 0 }
            .sortedByDescending { counts.getInt(it.pkg, 0) }
            .take(4)
        if (top.isEmpty()) {
            val hint = TextView(this)
            hint.text = "تطبيقاتك الأكثر استخداماً ستظهر هنا"
            hint.setTextColor(c(0x88FFFFFF))
            hint.textSize = 13f
            hint.gravity = Gravity.CENTER
            hint.setPadding(0, dp(18), 0, dp(18))
            usedRow.addView(hint, lp(ViewGroup.LayoutParams.MATCH_PARENT, W))
        } else {
            for (a in top) usedRow.addView(iconCell(a, 46, true), lp(0, W, 1f))
        }
    }

    private fun updateBattery() {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        if (level >= 0 && scale > 0) {
            batteryView.text = (if (charging) "⚡ " else "🔋 ") + (level * 100 / scale) + "%"
        } else {
            batteryView.text = "🔋"
        }
    }

    // ---------- drawer ----------
    private fun openDrawer(focus: Boolean) {
        if (drawerOpen) return
        drawerOpen = true
        drawer.visibility = View.VISIBLE
        drawer.translationY = resources.displayMetrics.heightPixels.toFloat()
        drawer.animate().translationY(0f).setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (focus && drawerOpen) {
                    searchBox.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(searchBox, 0)
                }
            }.start()
        home.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(300).start()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
        searchBox.setText("")
        searchBox.clearFocus()
        drawer.animate().translationY(resources.displayMetrics.heightPixels.toFloat())
            .setDuration(250)
            .withEndAction { if (!drawerOpen) drawer.visibility = View.GONE }
            .start()
        home.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).start()
    }

    private fun applyFilter(q: String) {
        val t = q.trim().lowercase()
        adapter.submit(if (t.isEmpty()) apps else apps.filter { it.label.lowercase().contains(t) })
    }

    // ---------- apps ----------
    private fun loadApps() {
        Thread {
            val pm = packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val result = pm.queryIntentActivities(query, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { ri ->
                    val pkg = ri.activityInfo.packageName
                    val launch = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(pkg, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    AppItem(ri.loadLabel(pm).toString(), pkg, launch, ri.loadIcon(pm))
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                apps = result
                applyFilter(if (::searchBox.isInitialized) searchBox.text.toString() else "")
                refreshHome()
            }
        }.start()
    }

    private fun launch(app: AppItem) {
        try {
            counts.edit().putInt(app.pkg, counts.getInt(app.pkg, 0) + 1).apply()
            startActivity(app.intent)
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            if (drawerOpen) {
                Handler(Looper.getMainLooper()).postDelayed({ closeDrawer() }, 400)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر فتح التطبيق", Toast.LENGTH_SHORT).show()
        }
    }

    private fun defaultDock(): List<String> {
        val pm = packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_VIEW, Uri.parse("sms:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        return intents.mapNotNull {
            pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.distinct()
    }

    private fun dockPkgs(): List<String> {
        val saved = prefs.getString("dock", null)
        val base = if (saved != null) saved.split(",").filter { it.isNotEmpty() } else defaultDock()
        return base.filter { p -> apps.any { it.pkg == p } }.take(4)
    }

    private fun togglePin(pkg: String) {
        val cur = dockPkgs().toMutableList()
        if (cur.contains(pkg)) {
            cur.remove(pkg)
        } else {
            if (cur.size >= 4) {
                Toast.makeText(this, "الشريط ممتلئ (٤ تطبيقات كحد أقصى)", Toast.LENGTH_SHORT).show()
                return
            }
            cur.add(pkg)
        }
        prefs.edit().putString("dock", cur.joinToString(",")).apply()
        refreshHome()
    }

    // ---------- menus ----------
    private fun showAppMenu(app: AppItem) {
        val pinned = dockPkgs().contains(app.pkg)
        val items = arrayOf(
            if (pinned) "إزالة من الشريط السفلي" else "تثبيت في الشريط السفلي",
            "معلومات التطبيق",
            "إلغاء التثبيت"
        )
        AlertDialog.Builder(this).setTitle(app.label).setItems(items) { _, which ->
            try {
                when (which) {
                    0 -> togglePin(app.pkg)
                    1 -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                    )
                    2 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                }
            } catch (e: Exception) {
                Toast.makeText(this, "تعذّر تنفيذ الأمر", Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    private fun showHomeMenu() {
        val items = arrayOf("تغيير الخلفية", "لون التمييز", "إعدادات الهاتف", "عن المطور")
        AlertDialog.Builder(this).setItems(items) { _, w ->
            try {
                when (w) {
                    0 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "الخلفية"))
                    1 -> AlertDialog.Builder(this).setTitle("لون التمييز")
                        .setItems(accentNames) { _, i ->
                            prefs.edit().putInt("accent", i).apply()
                            recreate()
                        }.show()
                    2 -> startActivity(Intent(Settings.ACTION_SETTINGS))
                    3 -> showAbout()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "تعذّر تنفيذ الأمر", Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("لانشر خضر")
            .setMessage("الإصدار 1.0\n\nتطوير: خضر أبو صالحه\nmankhader@gmail.com\n\nاسحب للأعلى لفتح كل التطبيقات، واسحب للأسفل لفتح الإشعارات، واضغط مطولاً على الشاشة للإعدادات.")
            .setPositiveButton("حسناً", null)
            .show()
    }

    private fun expandStatusBar() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel").invoke(sb)
        } catch (e: Exception) {
        }
    }

    // ---------- adapter ----------
    inner class AppAdapter : RecyclerView.Adapter<VH>() {
        private var items: List<AppItem> = emptyList()

        fun submit(l: List<AppItem>) {
            items = l
            notifyDataSetChanged()
        }

        fun first(): AppItem? = items.firstOrNull()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val cell = LinearLayout(this@MainActivity)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER
            cell.setPadding(dp(4), dp(10), dp(4), dp(10))
            cell.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
            )
            val iv = ImageView(this@MainActivity)
            cell.addView(iv, lp(dp(56), dp(56)))
            val tv = TextView(this@MainActivity)
            tv.setTextColor(Color.WHITE)
            tv.textSize = 11f
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.gravity = Gravity.CENTER
            val tl = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tl.topMargin = dp(5)
            cell.addView(tv, tl)
            pressFeedback(cell)
            return VH(cell, iv, tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val a = items[position]
            holder.iv.setImageDrawable(fresh(a.icon))
            holder.tv.text = a.label
            holder.cell.setOnClickListener { launch(a) }
            holder.cell.setOnLongClickListener { showAppMenu(a); true }
        }

        override fun getItemCount(): Int = items.size
    }
}
