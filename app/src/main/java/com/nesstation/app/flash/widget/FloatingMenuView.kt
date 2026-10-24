package com.nesstation.app.flash.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast

/**
 * 可拖动悬浮球 + 快捷菜单。
 *
 * ★ v12 全面翻新：球体渐变正圆 + 自绘汉堡图标；菜单网格卡片化。
 * ★ v14 紧凑化（用户反馈："悬浮球太大、点出的菜单也大、不要背景"）：
 * - 球体：54dp → 38dp（图标同步缩至 18dp intrinsic）；
 * - 菜单：横屏 5 列 / 竖屏 4 列紧凑网格（56dp 单元 + 26dp 徽章 + 9sp 标签），
 *   彻底去掉深色容器背景与单元格底色 —— 只留彩色徽章 + 文字 + 涟漪，
 *   透明 PopupWindow 悬浮在页面上。
 * ★ v21: 半透明 + 闲置自动隐藏（用户反馈：悬浮球应该半透明，并支持自动
 * 隐藏到边角，点击再回来）：
 * - 球体常态半透明（alpha 0.55，原 0.96），按下时暂时恢复全实方便看清；
 * - 闲置 5 秒无交互 → 自动滑到最近的屏幕边角 + 变极淡（alpha 0.30）；
 * - 点击隐藏态的球 → 动画回原位置（本次点击不弹菜单），拖动/菜单开合/
 *   全屏切换都会重置计时；菜单打开与全屏视频期间不自动隐藏。
 * ★ v23: 悬浮球更透 + 菜单去单元背景 + 紧凑化（用户反馈：「悬浮球半透明，
 * 菜单每个按钮周围的背景去掉，并紧凑一点，小一点」）：
 * - 球体常态 alpha 0.55 → 0.45（更透），按下 0.9 → 0.8；
 * - 菜单单元撤掉 v15 的小深色芯片托底（徽章实色 + 白描边 + 字影保留，
 *   任意底色仍可辨）；
 * - 单元 56dp → 48dp、徽章 26dp → 22dp、字形 11sp → 10sp、标签 9sp → 8sp、
 *   列数横屏 5 → 6 / 竖屏 4 → 5、行距 4dp → 3dp、容器内距 2dp → 1dp。
 * ★ v24 竖屏紧凑化（用户反馈：「悬浮球调出的菜单排列太长了」）：
 * - 竖屏不再四节自上而下堆叠（约 4×52dp ≈ 210dp 长条），改为【四列并排】
 *   —— 每列 = 节标签 + 该节功能项竖排，总高收至 ~180dp、宽 ~215dp，
 *   整体呈紧凑表格，竖屏下半屏不再被长条菜单挡住；
 * - 竖屏单元 48dp → 44dp、列间距 8dp；横屏 2×2 分区布局保持不变。
 * ★ v15 可见性修复（用户反馈："悬浮菜单看不见了，颜色弄好点"）：
 * v14 全透明悬浮在浅色/噪点网页上被淹没（徽章底仅 26% alpha、标签浅灰蓝
 * DDE3EE）。本次：尺寸不动（38dp 球 / 56dp 单元 / 26dp 徽章 / 9sp），每个
 * 单元自带一片小深色圆角芯片托底（createCellChipBg，90% 深蓝黑 —— 单元
 * 级小芯片而非整块面板背景，兼顾"无大背景"与"看得清"）；徽章实色化
 * （26% -> 95% alpha）+ 白描边；字形/标签纯白 + 阴影，任何页面底色都清晰。
 * 拖动/边缘停靠/回调接口与 v12 完全兼容，宿主（WebGameScreen）零改动。
 */
class FloatingMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Callbacks {
        fun onToggleFullscreen()
        fun onToggleOrientation()
        fun onToggleGamepad()
        fun onToggleMouse()
        fun onOpenKeyMapping()
        fun onOpenFlashSettings()
        fun onOpenPageZoom()
        fun onOpenUaMode()
        fun onRefresh()
        fun onBack()
        fun onClose()
        fun onExtractSwf()
        fun onToggleCameraRotation()
        fun onOpenAspectRatio()
        /** ★ 菜单优化版：回浏览器主页（收藏夹网格）。带默认空实现 ——
         *  SwfPlayerScreen 等无主页概念的宿主零改动不受影响。 */
        fun onGoHome() {}
    }

    private val triggerBtn: ImageButton
    private var popup: PopupWindow? = null
    private var callbacks: Callbacks? = null

    private var isMenuOpen = false

    // ============================ ★ v21: 半透明 + 自动隐藏到边角 ============================
    companion object {
        /** 闲置多久后自动滑到最近屏幕边角（毫秒） */
        private const val AUTO_HIDE_MS = 5000L
        /** 常态半透明（用户反馈：球“应该半透明”；★ v23 再加深 0.55 → 0.45） */
        private const val BALL_ALPHA_NORMAL = 0.45f
        /** 边角隐藏态极淡（点击恢复） */
        private const val BALL_ALPHA_HIDDEN = 0.30f
    }

    private val autoHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideRunnable = Runnable { hideToNearestEdge() }
    private var autoHidden = false
    private var savedTX = 0f
    private var savedTY = 0f
    private var edgeAnim: android.animation.ValueAnimator? = null
    /** 点击隐藏球恢复原位时，消费本次点击（不弹菜单） */
    private var suppressRestoreClick = false

    /** 每个功能的徽章强调色（与浏览器收藏卡片同一色系） */
    private val menuAccents = intArrayOf(
        0xFF8A7BFF.toInt(), 0xFFE74C3C.toInt(), 0xFF27AE60.toInt(), 0xFF3498DB.toInt(),
        0xFFE67E22.toInt(), 0xFF1ABC9C.toInt(), 0xFF9B59B6.toInt(), 0xFFF1C40F.toInt()
    )

    var isFullscreen = false
        set(value) {
            field = value
            // WebView 视频全屏时 customView 会盖住本球，隐藏并收起菜单
            triggerBtn.visibility = if (value) GONE else VISIBLE
            if (value && isMenuOpen) {
                popup?.dismiss()
                isMenuOpen = false
            }
            // ★ v21: GONE（球不可见）期间挂起自动隐藏计时，恢复可见后重新计起
            if (value) autoHideHandler.removeCallbacks(hideRunnable) else scheduleAutoHide()
        }

    var isLandscape = true
        set(value) { field = value }

    init {
        triggerBtn = ImageButton(context).apply {
            background = createBallBg()
            setImageDrawable(MenuGlyphDrawable(resources.displayMetrics.density))
            setPadding(0, 0, 0, 0)
            elevation = dp(6).toFloat()
            // ★ v21: 常态半透明（0.96 → 0.55），按下时暂时全实方便看清
            alpha = BALL_ALPHA_NORMAL
            contentDescription = "菜单"
        }
        addView(triggerBtn, LayoutParams(dp(38), dp(38)).apply { gravity = Gravity.CENTER })
        setupDrag()
        // ★ v21: attach 后开始闲置计时（5s 无交互 → 滑到屏幕边角变极淡）
        scheduleAutoHide()
    }

    fun setCallbacks(cb: Callbacks) {
        callbacks = cb
    }

    fun attachTo(parent: ViewGroup) {
        if (parent !== this.parent) {
            (parent as? ViewGroup)?.removeView(this)
            parent.addView(this, LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(48)
                marginEnd = dp(12)
            })
        }
        // ★ v21: 重新挂载（切标签/重建 WebView 容器）后位置归零，重启闲置计时
        autoHidden = false
        suppressRestoreClick = false
        scheduleAutoHide()
    }

    // ============================ ★ v21: 自动隐藏到边角 ============================

    /** 重启闲置计时（5s 无交互 → 滑到最近屏幕边角 + 变极淡） */
    private fun scheduleAutoHide() {
        autoHideHandler.removeCallbacks(hideRunnable)
        if (isFullscreen || isMenuOpen || autoHidden) return
        autoHideHandler.postDelayed(hideRunnable, AUTO_HIDE_MS)
    }

    /** 滑到最近的屏幕边角 + 变极淡（点击球后动画回原位） */
    private fun hideToNearestEdge() {
        if (autoHidden || isFullscreen || isMenuOpen) return
        val parentView = parent as? ViewGroup ?: return
        val w = if (width > 0) width else dp(38)
        val h = if (height > 0) height else dp(38)
        val inset = dp(6).toFloat()
        // 当前视觉位置（parent 坐标系：布局锚点 + 平移量）
        val cx = left + translationX + w / 2f
        val cy = top + translationY + h / 2f
        val xs = floatArrayOf(inset, (parentView.width - w - inset).coerceAtLeast(inset))
        val ys = floatArrayOf(inset, (parentView.height - h - inset).coerceAtLeast(inset))
        var bestX = xs[0]; var bestY = ys[0]; var bestD = Float.MAX_VALUE
        for (x in xs) for (y in ys) {
            val d = (x + w / 2f - cx) * (x + w / 2f - cx) + (y + h / 2f - cy) * (y + h / 2f - cy)
            if (d < bestD) { bestD = d; bestX = x; bestY = y }
        }
        savedTX = translationX
        savedTY = translationY
        autoHidden = true
        animateBallTo(bestX - left, bestY - top, BALL_ALPHA_HIDDEN)
    }

    /** 点击隐藏态的球 → 回到隐藏前位置 + 常态半透明（本次点击不弹菜单） */
    private fun restoreFromEdge() {
        if (!autoHidden) return
        autoHidden = false
        animateBallTo(savedTX, savedTY, BALL_ALPHA_NORMAL)
        scheduleAutoHide()
    }

    /** 整个视图平移滑动动画 + 球体 alpha 过渡（ViewPropertyAnimator 不便同时插值两目标） */
    private fun animateBallTo(tx: Float, ty: Float, targetAlpha: Float) {
        edgeAnim?.cancel()
        val sx = translationX; val sy = translationY; val sa = triggerBtn.alpha
        edgeAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                translationX = sx + (tx - sx) * f
                translationY = sy + (ty - sy) * f
                triggerBtn.alpha = sa + (targetAlpha - sa) * f
            }
            start()
        }
    }

    private fun setupDrag() {
        triggerBtn.setOnTouchListener(object : OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private var startTransX = 0f
            private var startTransY = 0f
            private var moved = false
            private var downTime = 0L

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // ★ v21: 任何触摸都重启闲置计时；点击隐藏态的球先恢复原位
                        //   （本次点击被消费，不弹菜单）。
                        scheduleAutoHide()
                        if (autoHidden) {
                            suppressRestoreClick = true
                            restoreFromEdge()
                        } else {
                            suppressRestoreClick = false
                            // 按下时暂时提亮（★ v23 0.9 → 0.8），方便看清拖动位置
                            triggerBtn.alpha = 0.8f
                        }
                        startX = e.rawX
                        startY = e.rawY
                        startTransX = translationX
                        startTransY = translationY
                        moved = false
                        downTime = System.currentTimeMillis()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - startX
                        val dy = e.rawY - startY
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                            moved = true
                            translationX = (startTransX + dx)
                            translationY = (startTransY + dy)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        // ★ v21: 松手回落常态半透明；恢复点击在此消费（不弹菜单）
                        if (triggerBtn.alpha > BALL_ALPHA_NORMAL) triggerBtn.alpha = BALL_ALPHA_NORMAL
                        if (suppressRestoreClick) {
                            suppressRestoreClick = false
                        } else if (!moved && System.currentTimeMillis() - downTime < 300) {
                            toggleMenu()
                        }
                    }
                }
                return true
            }
        })
    }

    private fun toggleMenu() {
        if (isMenuOpen) {
            popup?.dismiss()
        } else {
            showMenu()
        }
    }

    /**
     * ★ v11: 外部入口（浏览器工具栏☰按钮）—— 主页等 Compose 覆盖层会盖住
     * 本触发器的场景下，工具栏菜单按钮仍可打开完整菜单。
     */
    fun openMenu() {
        if (!isMenuOpen) showMenu()
    }

    fun dismissMenu() {
        popup?.dismiss()
    }

    // ============================ ★ 菜单优化版：功能分组 ============================

    private class MenuItem(
        val title: String,
        /** 圆形徽章里的单字/短字图标（汉字为主，语义直达；换资源图标为 0 依赖） */
        val glyph: String,
        val action: () -> Unit
    )

    /** ★ 菜单优化版：功能分组节（节标签 + 该组的功能项）。 */
    private class MenuSection(
        val label: String,
        val items: List<MenuItem>
    )

    /**
     * ★ 菜单优化版（用户需求：优化悬浮球菜单排列）：
     * 原 14 项平铺网格在横屏 6 列下排成 6+6+2 —— 末行孤零零 2 项，且功能
     * 无任何归类（全屏与刷新相邻、手柄与提取 SWF 混排）。现按语义分 4 组，
     * 每组独立一行并带节标签，新增「主页」项与浏览器主页联动：
     *   网页：主页 / 刷新 / 返回 / 关闭      （导航四件套，最常用排最前）
     *   显示：全屏切换 / 横竖屏 / 画面比例 / 页面缩放
     *   输入：手柄 / 鼠标 / 按键映射
     *   引擎：Flash引擎 / 兼容模式 / 提取SWF / 视角旋转
     * 横屏 2×2 分区并排（高度反比旧版矮）、竖屏四节堆叠 —— 任意方向都不出孤行。
     */
    private fun menuSections(): List<MenuSection> = listOf(
        MenuSection("网页", listOf(
            MenuItem("主页", "主") { callbacks?.onGoHome() },
            MenuItem("刷新", "刷") { callbacks?.onRefresh() },
            MenuItem("返回", "退") { callbacks?.onBack() },
            MenuItem("关闭", "关") { callbacks?.onClose() }
        )),
        MenuSection("显示", listOf(
            MenuItem("全屏切换", "全") { callbacks?.onToggleFullscreen() },
            MenuItem("横竖屏", "转") { callbacks?.onToggleOrientation() },
            MenuItem("画面比例", "比") { callbacks?.onOpenAspectRatio() },
            MenuItem("页面缩放", "缩") { callbacks?.onOpenPageZoom() }
        )),
        MenuSection("输入", listOf(
            MenuItem("手柄", "柄") { callbacks?.onToggleGamepad() },
            MenuItem("鼠标", "鼠") { callbacks?.onToggleMouse() },
            MenuItem("按键映射", "键") { callbacks?.onOpenKeyMapping() }
        )),
        MenuSection("引擎", listOf(
            MenuItem("Flash引擎", "F") { callbacks?.onOpenFlashSettings() },
            MenuItem("兼容模式", "UA") { callbacks?.onOpenUaMode() },
            MenuItem("提取SWF", "取") { callbacks?.onExtractSwf() },
            MenuItem("视角旋转", "视") { callbacks?.onToggleCameraRotation() }
        ))
    )

    private fun showMenu() {
        // ★ v21: 菜单打开期间不自动隐藏；球体全实方便定位
        autoHideHandler.removeCallbacks(hideRunnable)
        triggerBtn.alpha = 1f
        // ★★ 菜单排列偏好（用户反馈「横屏时悬浮球菜单不要横着放，太长了」）：
        //   auto = 恒竖向紧凑四列（新旧反馈合并后的默认：横屏也不再横向铺开
        //          挡画面 —— 旧 auto 跟随屏幕方向，横屏 2×2 分区并排宽达
        //          ~400dp+，横屏游戏/看图时菜单横贯屏幕）；
        //   portrait = 强制竖排（同 auto，保留兼容）；
        //   landscape = 强制横排（2×2 分区并排，想要宽排的用户手动指定）。
        //   设置入口在 Flash 引擎对话框（SwfPlayerScreen / WebGameScreen）。
        val menuLayoutPref = try {
            com.nesstation.app.flash.data.PrefsManager.floatingMenuLayout
        } catch (_: Throwable) { "auto" }
        val useWideLayout = when (menuLayoutPref) {
            "landscape" -> true
            else -> false // auto / portrait：统一竖向紧凑四列，横屏不再横排
        }
        // ★ 菜单优化版：单元规格沿用 v23 紧凑口径（48dp 单元 / 22dp 徽章 / 10sp 字形 / 8sp 标签）；
        //   ★ v24 竖屏四列布局单元再收一档 44dp（长条 → 紧凑表格的总高度关键）。
        val sections = menuSections()
        val cell = if (useWideLayout) dp(48) else dp(44)

        // ★ v14: 无背景容器 —— 徽章+文字直接悬浮在页面上（用户"不要背景"）
        val menuContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }

        fun rippleForeground(v: View) {
            // 系统涟漪反馈（selectableItemBackground），API 23+ 用前景
            try {
                val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                val d = ta.getDrawable(0)
                ta.recycle()
                if (d != null) v.foreground = d
            } catch (_: Exception) {}
        }

        fun buildCell(item: MenuItem, accentIdx: Int): View {
            val accent = menuAccents[accentIdx % menuAccents.size]
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(3), dp(2), dp(3))
                isClickable = true
                isFocusable = true
                // ★ v23: 去掉 v15 的单元级芯片托底（用户：「每个按钮周围的
                //   背景去掉」）—— 可见性由徽章实色 + 白描边 + 字影保证。
                background = null
                rippleForeground(this)
                layoutParams = LinearLayout.LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(1), 0, dp(1), 0)
                }
                addView(TextView(context).apply {
                    text = item.glyph
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
                    )
                    gravity = Gravity.CENTER
                    background = createBadgeBg(accent)
                    // ★ v15: 字形阴影（浅色页面上白字也有轮廓）
                    setShadowLayer(dp(1).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                        bottomMargin = dp(2)
                    }
                })
                addView(TextView(context).apply {
                    text = item.title
                    // ★ v15: 浅灰蓝 DDE3EE 在白底页面上几乎不可见 → 纯白 + 阴影
                    setTextColor(Color.WHITE)
                    textSize = 8f
                    gravity = Gravity.CENTER
                    setShadowLayer(dp(1).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
                })
                setOnClickListener {
                    item.action()
                    popup?.dismiss()
                }
            }
        }

        // ---- ★ 菜单优化版：节（标签行 + 功能项行） ----
        //   accentStart：该节第一项的全局序号 —— 彩色徽章仍按全局序轮转取色，
        //   与浏览器收藏卡片同一视觉语言；节标签独立小字 + 阴影保证可读。
        //   ★ v24: itemsPerRow 参数 —— 横屏默认全部一项一行（旧版行为不变）；
        //   竖屏传 1 → 功能项竖排成列（配合下方四列并排容器）。
        fun buildSection(section: MenuSection, accentStart: Int, itemsPerRow: Int = section.items.size): View {
            val sectionView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            sectionView.addView(TextView(context).apply {
                text = section.label
                // ★ 菜单优化版修复：android.graphics.Color 无参构造器已废弃且不收参，
                //   setTextColor 需要 Int —— 直接传 0xB8E6ECF5.toInt()（72% 半透明
                //   浅蓝灰，比纯白项目标签弱一档，形成层级；字影保证浅色页面可辨）。
                setTextColor(0xB8E6ECF5.toInt())
                textSize = 7f
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
                )
                setShadowLayer(dp(1).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
                letterSpacing = 0.12f
                setPadding(dp(6), 0, dp(6), dp(1))
            })
            var idx = 0
            section.items.chunked(itemsPerRow.coerceAtLeast(1)).forEach { rowItems ->
                val itemRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                rowItems.forEach { item ->
                    itemRow.addView(buildCell(item, accentStart + idx))
                    idx++
                }
                sectionView.addView(itemRow)
            }
            return sectionView
        }

        // ---- ★ 菜单优化版：布局排布 ----
        //   宽布局（横屏或偏好横排）：2×2 分区并排（[网页|显示] / [输入|引擎]）——
        //          宽屏横向利用空间，总高度 ~2 节（比旧版 3 行网格更矮）；
        //   窄布局（竖屏或偏好竖排）：★ v24 四列并排 —— 每列 = 节标签 +
        //          该节功能项竖排（网页|显示|输入|引擎），总高 ~180dp 紧凑
        //          表格，不再四节堆叠成长条（用户反馈“菜单排列太长了”）。
        if (useWideLayout) {
            fun sectionRow(pair: List<Pair<MenuSection, Int>>): LinearLayout {
                return LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    pair.forEachIndexed { i, (sec, start) ->
                        if (i > 0) {
                            // 分区间窄分隔（纯空隙，无背景，保持"透明悬浮"风格）
                            addView(View(context), LinearLayout.LayoutParams(dp(12), 1).apply {
                                gravity = Gravity.CENTER_VERTICAL
                            })
                        }
                        addView(buildSection(sec, start))
                    }
                }
            }
            var idx = 0
            val starts = mutableListOf<Int>()
            sections.forEach { starts.add(idx); idx += it.items.size }
            val top = sectionRow(listOf(sections[0] to starts[0], sections[1] to starts[1]))
            val bottom = sectionRow(listOf(sections[2] to starts[2], sections[3] to starts[3]))
            top.setPadding(0, 0, 0, dp(4))
            menuContainer.addView(top)
            menuContainer.addView(bottom)
        } else {
            // ★ v24 紧凑四列：竖屏不再四节竖排堆叠（~210dp 长条），改为
            //   网页|显示|输入|引擎 四列并排 —— 每列自带节标签 + 竖排功能项，
            //   总高收至 ~180dp；列间以 8dp 空隙分隔（无背景，透明悬浮不变）。
            val columnsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            var accent = 0
            sections.forEachIndexed { si, sec ->
                val col = buildSection(sec, accent, 1)
                col.setPadding(dp(4), 0, dp(4), 0)
                if (si > 0) {
                    columnsRow.addView(View(context), LinearLayout.LayoutParams(dp(8), 1).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    })
                }
                columnsRow.addView(col)
                accent += sec.items.size
            }
            menuContainer.addView(columnsRow)
        }

        // ---- ★ 菜单优化版：先测量再定位（防出屏） ----
        //   旧版 xoff 按估算列宽硬算、不看实际高度 —— 球被拖到屏幕下半部时
        //   菜单溢出屏底。现在 measure 后按球的实际屏位决定：
        //     横向：菜单右缘对齐球右缘；球在左半屏时改为贴屏幕左缘（不出左屏）；
        //     纵向：下方放得下就下弹；放不下翻到球上方弹出。
        menuContainer.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuW = menuContainer.measuredWidth
        val menuH = menuContainer.measuredHeight
        val loc = IntArray(2)
        triggerBtn.getLocationOnScreen(loc)
        val screenH = resources.displayMetrics.heightPixels
        val ballW = dp(38)
        var xoff = -(menuW - ballW + dp(6))
        if (loc[0] + xoff < dp(4)) xoff = dp(4) - loc[0]
        val spaceBelow = screenH - (loc[1] + ballW)
        val yoff = if (menuH + dp(10) <= spaceBelow) dp(4)
                   else -(menuH + ballW + dp(6))

        popup = PopupWindow(menuContainer, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(6).toFloat()
            setOnDismissListener {
                isMenuOpen = false
                // ★ v21: 菜单关闭后球回落常态半透明，重新开始闲置计时
                triggerBtn.alpha = BALL_ALPHA_NORMAL
                scheduleAutoHide()
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                showAsDropDown(triggerBtn, xoff, yoff, Gravity.END)
            } else {
                @Suppress("DEPRECATION")
                showAsDropDown(triggerBtn, xoff, yoff)
            }
        }
        isMenuOpen = true
    }

    // ============================ 绘制件（零资源依赖） ============================

    /** 悬浮球底：正圆 + 紫罗兰渐变 + 半透明细描边（★ v15 描边 25%→50% 白，浅色页面上轮廓更清） */
    private fun createBallBg() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        colors = intArrayOf(0xFF9C8BFF.toInt(), 0xFF6C5CE7.toInt())
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(dp(1), 0x80FFFFFF.toInt())
    }

    /** 功能徽章底：★ v15 强调色实底（26%→95% alpha）+ 白描边（任意页面底色都清晰） */
    private fun createBadgeBg(accent: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor((accent and 0x00FFFFFF) or 0xF2000000.toInt())
        setStroke(dp(1), 0x99FFFFFF.toInt())
    }

    // ★ v23: createCellChipBg（v15 单元芯片托底）已按用户最新指令移除
    //   （菜单每个按钮周围的背景去掉）—— 徽章实色 + 白描边 + 字影兜底可见性。

    /** 自绘"汉堡"三横线图标（dp 适配 intrinsic，白色圆头短条 —— 比系统图标干净） */
    private class MenuGlyphDrawable(private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val barW = w * 0.58f
            val barH = h * 0.115f
            val left = (w - barW) / 2f
            val gap = h * 0.16f
            val total = barH * 3f + gap * 2f
            var top = (h - total) / 2f
            val radii = barH / 2f
            repeat(3) {
                canvas.drawRoundRect(left, top, left + barW, top + barH, radii, radii, paint)
                top += barH + gap
            }
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

        override fun getIntrinsicWidth(): Int = (18 * density).toInt()
        override fun getIntrinsicHeight(): Int = (18 * density).toInt()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
