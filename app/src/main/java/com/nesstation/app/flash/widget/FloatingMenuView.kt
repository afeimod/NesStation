package com.nesstation.app.flash.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast

/**
 * 可拖动的悬浮菜单按钮。
 * 1:1 移植自 3.3-fix2 FloatingMenuView，去掉 R.drawable/R.string 依赖（用代码创建 drawable 与中文字符串）。
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

    var isFullscreen = false
        set(value) {
            field = value
            // WebView 进入全屏(Ruffle requestFullscreen 触发)时,触发器会被 WebView 的
            // customView 盖在 DecorView 上,显示出来也按不到,直接隐藏。
            triggerBtn.visibility = if (value) GONE else VISIBLE
            // 如果当前菜单已打开,关闭它
            if (value && isMenuOpen) {
                popup?.dismiss()
                isMenuOpen = false
            }
            updateTriggerIcon()
        }

    var isLandscape = true
        set(value) { field = value; updateTriggerIcon() }

    init {
        triggerBtn = ImageButton(context).apply {
            background = createCircleBg(0xFF263238.toInt())
            setImageResource(android.R.drawable.ic_menu_sort_by_size)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            alpha = 0.85f
            contentDescription = "菜单"
        }
        addView(triggerBtn, LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.CENTER })
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

    private fun showMenu() {
        val isLandscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) 2 else 1

        val menuContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedBg(0xCC1A1A1A.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val items = listOf(
            MenuItem("全屏切换", android.R.drawable.ic_menu_view) { callbacks?.onToggleFullscreen() },
            MenuItem("横竖屏切换", android.R.drawable.ic_menu_rotate) { callbacks?.onToggleOrientation() },
            MenuItem("手柄开关", android.R.drawable.ic_menu_compass) { callbacks?.onToggleGamepad() },
            MenuItem("鼠标光标", android.R.drawable.ic_menu_camera) { callbacks?.onToggleMouse() },
            MenuItem("按键映射", android.R.drawable.ic_menu_agenda) { callbacks?.onOpenKeyMapping() },
            MenuItem("Flash 引擎", android.R.drawable.ic_menu_preferences) { callbacks?.onOpenFlashSettings() },
            MenuItem("页面缩放", android.R.drawable.ic_menu_zoom) { callbacks?.onOpenPageZoom() },
            MenuItem("画面比例", android.R.drawable.ic_menu_crop) { callbacks?.onOpenAspectRatio() },
            MenuItem("兼容模式", android.R.drawable.ic_menu_help) { callbacks?.onOpenUaMode() },
            MenuItem("视角旋转(3D)", android.R.drawable.ic_menu_rotate) { callbacks?.onToggleCameraRotation() },
            MenuItem("提取 SWF", android.R.drawable.ic_menu_search) { callbacks?.onExtractSwf() },
            MenuItem("刷新", android.R.drawable.ic_menu_revert) { callbacks?.onRefresh() },
            MenuItem("返回", android.R.drawable.ic_menu_revert) { callbacks?.onBack() },
            MenuItem("关闭", android.R.drawable.ic_menu_close_clear_cancel) { callbacks?.onClose() }
        )

        fun buildItemView(item: MenuItem): View {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = createRoundedBg(0xFF263238.toInt())
            }
            val icon = ImageView(context).apply {
                setImageResource(item.iconRes)
                setPadding(0, 0, dp(6), 0)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            }
            val text = TextView(context).apply {
                text = item.title
                setTextColor(Color.WHITE)
                textSize = 13f
            }
            row.addView(icon)
            row.addView(text)
            row.setOnClickListener {
                item.action()
                popup?.dismiss()
            }
            return row
        }

        if (columns == 1) {
            items.forEach { menuContainer.addView(buildItemView(it)) }
        } else {
            var row: LinearLayout? = null
            items.forEachIndexed { index, item ->
                if (index % 2 == 0) {
                    row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 0, 0, 0)
                    }
                    menuContainer.addView(row)
                }
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(4), dp(0), dp(4), dp(0))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                cell.addView(buildItemView(item))
                row?.addView(cell)
            }
        }

        popup = PopupWindow(menuContainer, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setOnDismissListener { isMenuOpen = false }
            // 兼容 Android < 7.0 (API 24)：4 参数 showAsDropDown 需 API 24+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                showAsDropDown(triggerBtn, -dp(160), 0, Gravity.END)
            } else {
                showAsDropDown(triggerBtn, -dp(160), 0)
            }
        }
        isMenuOpen = true
    }

    private fun updateTriggerIcon() {
        triggerBtn.setImageResource(android.R.drawable.ic_menu_sort_by_size)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun createCircleBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun createRoundedBg(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(color)
    }

    private data class MenuItem(
        val title: String,
        val iconRes: Int,
        val action: () -> Unit
    )

    fun dismissMenu() {
        popup?.dismiss()
    }
}
