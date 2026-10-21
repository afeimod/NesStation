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
 * ★ v12 全面翻新（用户反馈："悬浮球改成圆形好看点、菜单现代化一点"）：
 * - 球体：54dp 正圆 + 紫罗兰渐变（TL→BR）+ 细描边 + 自绘三横线"汉堡"图标，
 *   替换 v11 的 44dp 灰底 + android 系统旧图标；
 * - 菜单：横屏 4 列 / 竖屏 3 列的网格卡片（圆角 20dp 容器 + 彩色圆形徽章 +
 *   汉字图标 + 11sp 标签 + 系统涟漪反馈），替换 v11 的两列老式系统图标行。
 * 拖动/边缘停靠/回调接口与 v11 完全兼容，宿主（WebGameScreen）零改动。
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
    }

    private val triggerBtn: ImageButton
    private var popup: PopupWindow? = null
    private var callbacks: Callbacks? = null

    private var isMenuOpen = false

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
        }

    var isLandscape = true
        set(value) { field = value }

    init {
        triggerBtn = ImageButton(context).apply {
            background = createBallBg()
            setImageDrawable(MenuGlyphDrawable(resources.displayMetrics.density))
            setPadding(0, 0, 0, 0)
            elevation = dp(6).toFloat()
            alpha = 0.96f
            contentDescription = "菜单"
        }
        addView(triggerBtn, LayoutParams(dp(54), dp(54)).apply { gravity = Gravity.CENTER })
        setupDrag()
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
                        if (!moved && System.currentTimeMillis() - downTime < 300) {
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

    // ============================ ★ v12 现代菜单 ============================

    private class MenuItem(
        val title: String,
        /** 圆形徽章里的单字/短字图标（汉字为主，语义直达；换资源图标为 0 依赖） */
        val glyph: String,
        val action: () -> Unit
    )

    private fun menuItems(): List<MenuItem> = listOf(
        MenuItem("全屏切换", "全") { callbacks?.onToggleFullscreen() },
        MenuItem("横竖屏", "转") { callbacks?.onToggleOrientation() },
        MenuItem("手柄", "柄") { callbacks?.onToggleGamepad() },
        MenuItem("鼠标", "鼠") { callbacks?.onToggleMouse() },
        MenuItem("按键映射", "键") { callbacks?.onOpenKeyMapping() },
        MenuItem("Flash引擎", "F") { callbacks?.onOpenFlashSettings() },
        MenuItem("页面缩放", "缩") { callbacks?.onOpenPageZoom() },
        MenuItem("画面比例", "比") { callbacks?.onOpenAspectRatio() },
        MenuItem("兼容模式", "UA") { callbacks?.onOpenUaMode() },
        MenuItem("视角旋转", "视") { callbacks?.onToggleCameraRotation() },
        MenuItem("提取SWF", "取") { callbacks?.onExtractSwf() },
        MenuItem("刷新", "刷") { callbacks?.onRefresh() },
        MenuItem("返回", "退") { callbacks?.onBack() },
        MenuItem("关闭", "关") { callbacks?.onClose() }
    )

    private fun showMenu() {
        val isLandscapeRes = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscapeRes) 4 else 3
        val items = menuItems()
        val cell = dp(78)
        val menuWidth = columns * cell + dp(16)

        val menuContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedBg(0xF21C1F2B.toInt(), 20)
            setPadding(dp(8), dp(8), dp(8), dp(8))
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
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isClickable = true
                isFocusable = true
                background = createRoundedBg(0x14FFFFFF, 12)
                rippleForeground(this)
                layoutParams = LinearLayout.LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                addView(TextView(context).apply {
                    text = item.glyph
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
                    )
                    gravity = Gravity.CENTER
                    background = createBadgeBg(accent)
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                        bottomMargin = dp(6)
                    }
                })
                addView(TextView(context).apply {
                    text = item.title
                    setTextColor(0xFFDDE3EE.toInt())
                    textSize = 11f
                    gravity = Gravity.CENTER
                })
                setOnClickListener {
                    item.action()
                    popup?.dismiss()
                }
            }
        }

        var row: LinearLayout? = null
        items.forEachIndexed { index, item ->
            if (index % columns == 0) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                menuContainer.addView(row)
            }
            row?.addView(buildCell(item, index))
        }

        // 网格行之间的窄间距
        for (i in 0 until menuContainer.childCount) {
            val r = menuContainer.getChildAt(i) as? LinearLayout ?: continue
            val lp = r.layoutParams as? LinearLayout.LayoutParams
            lp?.bottomMargin = dp(6)
            r.layoutParams = lp
        }

        popup = PopupWindow(menuContainer, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(12).toFloat()
            setOnDismissListener { isMenuOpen = false }
            // 让菜单右边对齐到球的右缘（球贴右缘时菜单不出屏）
            val xoff = -(menuWidth - dp(54) + dp(10))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                showAsDropDown(triggerBtn, xoff, dp(6), Gravity.END)
            } else {
                @Suppress("DEPRECATION")
                showAsDropDown(triggerBtn, xoff, dp(6))
            }
        }
        isMenuOpen = true
    }

    // ============================ 绘制件（零资源依赖） ============================

    /** 悬浮球底：正圆 + 紫罗兰渐变 + 半透明细描边 */
    private fun createBallBg() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        colors = intArrayOf(0xFF9C8BFF.toInt(), 0xFF6C5CE7.toInt())
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(dp(1), 0x40FFFFFF)
    }

    /** 功能徽章底：强调色 26% 透明度圆底 */
    private fun createBadgeBg(accent: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor((accent and 0x00FFFFFF) or 0x42000000)
        setStroke(dp(1), (accent and 0x00FFFFFF) or 0x66000000)
    }

    /** 现代圆角容器 */
    private fun createRoundedBg(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

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

        override fun getIntrinsicWidth(): Int = (24 * density).toInt()
        override fun getIntrinsicHeight(): Int = (24 * density).toInt()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
