package com.nesstation.app.core.engine

import android.os.Handler
import android.os.Looper
import java.io.File
import javax.microedition.lcdui.Canvas
import javax.microedition.lcdui.Displayable
import javax.microedition.shell.J2meHost
import javax.microedition.shell.MicroLoader
import javax.microedition.shell.MidletThread
import javax.microedition.util.ContextHolder

/**
 * EmulatorEngine implementation for J2ME (MIDP) games.
 *
 * Unlike the libretro-based engines (NES/SNES/GBA/...), J2ME is event-driven
 * rather than frame-pumped: the MIDlet runs on its own HandlerThread
 * ([MidletThread]) and redraws by calling [Canvas.repaint]. There is no
 * native core, no continuous emulation loop, and no direct frame buffer —
 * the Canvas renders to its own internal SurfaceView.
 *
 * This engine therefore:
 *  - boots the MIDlet via [MicroLoader] with [MidletThread.embedded] = true
 *    (so exit does NOT kill the app process)
 *  - acts as the [J2meHost] that the J2ME runtime calls back into
 *    (setCurrent / getCurrent / isVisible / requestExit)
 *  - translates EmulatorScreen's gamepad bit-masks into J2ME key events
 *    ([Canvas.postKeyPressed] / [Canvas.postKeyReleased])
 *  - exposes the current [Displayable]'s Android [android.view.View] so
 *    EmulatorScreen can host it inside the Compose hierarchy
 *
 * Lifecycle (mirrors [NesEngine]):
 *  - [loadRom] boots the MIDlet and sets this engine as the J2meHost.
 *  - [setPaused] forwards to [MidletThread.pauseApp] / [resumeApp].
 *  - [unload] destroys the MIDlet WITHOUT killing the process (embedded
 *    mode). Re-entry is guarded by [isLoaded].
 *  - All other EmulatorEngine methods are no-ops or return safe defaults.
 */
class J2meEngine private constructor() : EmulatorEngine, J2meHost {

        override val frameBuffer = IntArray(0) // J2ME renders to its own SurfaceView

        @Volatile
        override var isLoaded = false
                private set

        @Volatile
        private var _paused = false
        @Volatile
        private var _midletStarted = false

        private var microLoader: MicroLoader? = null
        @Volatile
        private var currentDisplayable: Displayable? = null
        @Volatile
        private var displayableView: android.view.View? = null
        private var previousPadBits = 0

        // EmulatorScreen button-bit → J2ME key-code mapping.
        // Bits come from EmulatorScreen.OnScreenController (BTN_* constants).
        // J2ME keys use the Canvas.KEY_* constants (negative values for
        // game-pad keys, ASCII values for numeric keys).
        private val keyMap: Map<Int, Int> = mapOf(
                0x0010 to Canvas.KEY_UP,       // BTN_UP
                0x0020 to Canvas.KEY_DOWN,     // BTN_DOWN
                0x0040 to Canvas.KEY_LEFT,     // BTN_LEFT
                0x0080 to Canvas.KEY_RIGHT,    // BTN_RIGHT
                0x0001 to Canvas.KEY_FIRE,     // BTN_A  → primary fire
                0x0002 to Canvas.KEY_SOFT_LEFT,// BTN_B  → secondary action
                0x0100 to Canvas.KEY_SOFT_RIGHT,// BTN_X → tertiary action
                0x0200 to Canvas.KEY_STAR,     // BTN_Y  → * key
                0x0004 to Canvas.KEY_POUND,    // BTN_SELECT → # key
                0x0008 to Canvas.KEY_END,      // BTN_START → menu/exit
                // Numeric keys (0-9) — used by the 12-key phone overlay layout.
                // Bits 16-25 are free (generic BTN_* constants top out at bit 15).
                0x010000 to '0'.code,          // BTN_NUM_0
                0x020000 to '1'.code,          // BTN_NUM_1
                0x040000 to '2'.code,          // BTN_NUM_2
                0x080000 to '3'.code,          // BTN_NUM_3
                0x100000 to '4'.code,          // BTN_NUM_4
                0x200000 to '5'.code,          // BTN_NUM_5
                0x400000 to '6'.code,          // BTN_NUM_6
                0x800000 to '7'.code,          // BTN_NUM_7
                0x1000000 to '8'.code,         // BTN_NUM_8
                0x2000000 to '9'.code,         // BTN_NUM_9
        )

        // 数字键兼作方向键的双派发表：真实手机上 2/4/6/8 既是数字键也是
        // 方向键，5 是确认键。游戏可能用 getGameAction() 译码（数字键自带
        // 游戏动作，KeyMapper 已映射），也可能直接比较 keyCode == KEY_UP
        // 等原始常量。只发数字键会让后者完全无法操作菜单。keyMap 已发送
        // 数字键本身，这里为同一个 bit 追加发送对应的游戏动作键，与真机
        // 行为一致。
        // 注意：bit 必须与 J2meOverlay 的 J2ME_BTN_NUM_* 严格对应 ——
        // 旧版 8→KEY_DOWN 写成了 0x8000000（bit 27，不对应任何按键），
        // 导致手机键盘模式按 8 永远不会补发方向键，已修正为 0x1000000。
        private val dualKeyMap: Map<Int, Int> = mapOf(
                0x040000 to Canvas.KEY_UP,      // 2 → 上
                0x100000 to Canvas.KEY_LEFT,    // 4 → 左
                0x400000 to Canvas.KEY_RIGHT,   // 6 → 右
                0x1000000 to Canvas.KEY_DOWN,   // 8 → 下
                0x200000 to Canvas.KEY_FIRE,    // 5 → 确认
        )

        /**
         * 数字键兼作方向键（原“手机键盘模式双派发”）：开启后虚拟键盘上的
         * 2/4/6/8/5 在发送数字键的同时追加发送 上/左/右/下/确认 键。
         * 由 UI 层根据 padLayout.javaNumDualDispatch 设置（默认开启），
         * 手柄模式的 "123" 数字小键盘与手机键盘模式同样生效。
         */
        @Volatile
        var phoneDualDispatch: Boolean = true

        /**
         * 用户自定义的 虚拟按键 bit → MIDP 键码 映射（来自设置里的
         * “按键映射”，仅包含被修改过的键位）。setPad1 时优先于内置 keyMap。
         */
        @Volatile
        private var customKeyMap: Map<Int, Int> = emptyMap()

        /**
         * 应用设置里的“按键映射”（PadLayout.javaButtonKeyMap 原始串）。
         * 传空串表示全部使用内置默认映射。
         */
        fun setCustomKeyMap(raw: String) {
                val parsed = mutableMapOf<Int, Int>()
                for (entry in raw.split(',')) {
                        val parts = entry.split('=')
                        if (parts.size != 2) continue
                        val bit = CUSTOM_BUTTON_BITS[parts[0].trim()] ?: continue
                        val code = parts[1].trim().toIntOrNull() ?: continue
                        parsed[bit] = code
                }
                customKeyMap = parsed
        }

        override fun ensureLoaded(): Boolean = true

        override fun loadRom(
                rom: File,
                systemDir: String,
                saveDir: String,
                onFrame: () -> Unit
        ): Boolean {
                // Embedded mode MUST be set before MidletThread.create() so that
                // notifyDestroyed() does not kill the app process on exit.
                MidletThread.embedded = true

                val context = ContextHolder.getAppContext()
                        ?: run { lastErr = "Application context not available"; return false }

                val loader = try {
                        MicroLoader(context, rom.absolutePath)
                } catch (e: Exception) {
                        lastErr = "MicroLoader construction failed: ${e.message}"
                        return false
                }

                if (loader.init() != true) {
                        lastErr = "MicroLoader.init() returned false"
                        return false
                }
                loader.applyConfiguration()

                // GameBox: 强制黑色屏幕底色, 与其他模拟核心(NES/SFC/GBA/...)一致。
                // 原默认 ProfileModel.screenBackgroundColor = 0xD0D0D0 浅灰色, 在
                // 游戏画面宽高比与屏幕不一致时四周出现白色边框。旧存档里的
                // 浅色配置也会被这里覆盖。
                Canvas.setBackgroundColor(0xFF000000.toInt())

                // GameBox: 嵌入式模式禁用 J2ME-Loader 的旧版虚拟键盘。
                // applyConfiguration() 会按 config.json 的 showKeyboard(默认 true)
                // 创建旧版 VirtualKeyboard：它不可见(没有 OverlayView 承载)却会
                // 1) 在 ViewCallbacks 里优先拦截物理按键；2) updateSize() 按
                // 手机布局高度压缩游戏画面。GameBox 有自己的虚拟手柄 overlay，
                // 旧版自带键盘一律不再使用。
                ContextHolder.setVk(null)

                // GameBox: 强制开启 MIDlet 触屏支持（hasPointerEvents() 返回 true）。
                // applyConfiguration() 会用 config.json 的 TouchInput 覆写
                // Canvas.touchInput —— 老版本生成的 config.json 没有该键，
                // Gson 反序列化时 boolean 落回 false。若此时（MIDlet 启动瞬间）
                // hasPointerEvents() 返回 false，很多游戏会在初始化时缓存这个
                // 标志，之后 UI 层再把开关切回 true 也收不到任何触摸事件。
                // 这里在 MidletThread.create()（把 INIT 投递到 MIDlet 线程）之前
                // 强制置 true，保证游戏从第一行 MIDlet 代码起就能查询到触屏支持。
                // 若用户在某游戏专属设置里关闭了 javaTouchInput，EmulatorScreen
                // 在 loadRom 完成后重放的 applyCoreOptions() 会再置回 false。
                Canvas.setHasTouchInput(true)

                // GameBox: 强制声明设备支持多点触控（修复 Java 触屏失效）。
                // Canvas.pointerPressed/Dragged/Released(pointer,x,y) 在
                // Display.multiTouchSupported==false 时只派发 pointer==0 的
                // 事件；该标志原本仅由游戏 JAD 的 NOKIA_UI_ENHANCEMENT 属性
                // 推断，绝大多数游戏没有 → false。于是虚拟手柄覆盖层的转发
                // 链路虽然把"游戏画面触摸"（含 pointerId）完整送到了 Canvas，
                // 但真实玩法中玩家按住虚拟按键（手指 id=0）再用第二根手指
                // 点击游戏画面（id=1..N）时，事件全部被 pointer==0 门控丢弃
                // —— 表现为"触屏没反应"。NDS 触屏走 setTouchInputDirect
                // 直接注入、不依赖 pointerId，所以正常。这里对齐 NDS 的
                // 架构语义：现代设备均为多点触控屏，嵌入式模式下任意
                // pointerId 的触摸都按真机多点行为派发（附 pointer number）。
                // 与 CanvasEvent → pointerPressed(pointer,x,y) 的完整分发
                // 路径（转发注入 + ViewCallbacks.onTouch 直达路径）同时生效。
                javax.microedition.lcdui.Display.setMultiTouchSupported(true)

                // Register this engine as the J2ME host BEFORE loading the MIDlet,
                // so Display.setCurrent() / MidletThread.resumeApp() find us.
                ContextHolder.setCurrentActivity(this)

                val midlets = try {
                        loader.loadMIDletList()
                } catch (e: Exception) {
                        lastErr = "loadMIDletList failed: ${e.message}"
                        return false
                }

                if (midlets.isEmpty()) {
                        lastErr = "No MIDlets found in ${rom.name}"
                        return false
                }

                val mainClass = midlets.keys.first()
                MidletThread.create(loader, mainClass)
                microLoader = loader
                isLoaded = true
                _midletStarted = false
                // MidletThread.create() posts INIT to the MIDlet's HandlerThread;
                // resumeApp() sends START which requires state==PAUSED. Delay to
                // guarantee INIT has completed before START is queued.
                Handler(Looper.getMainLooper()).postDelayed({
                        MidletThread.resumeApp()
                }, 300)

                return true
        }

        override fun setSurface(surface: android.view.Surface?) {
                // J2ME renders to its own SurfaceView inside the Canvas; the
                // EmulatorScreen Surface is not used.
        }

        override fun setSaveName(name: String) {}
        override fun setCoreOption(key: String, value: String) {}

        override fun videoWidth(): Int =
                (currentDisplayable as? Canvas)?.width ?: 0

        override fun videoHeight(): Int =
                (currentDisplayable as? Canvas)?.height ?: 0

        override fun setVideoFilter(filter: Int) = Canvas.setJ2meFilterMode(filter)
        override fun setHighQualityScaling(enabled: Boolean) {}
        override fun setFastForward(speed: Int) {}

        override fun setPaused(paused: Boolean) {
                _paused = paused
                if (paused) {
                        MidletThread.pauseApp()
                } else if (_midletStarted) {
                        // Subsequent resume after an explicit pause — call directly.
                        // The very first resume is handled by loadRom()'s delayed
                        // postDelayed(resumeApp) to guarantee MIDlet INIT has completed.
                        MidletThread.resumeApp()
                }
                // Track that the MIDlet has been started at least once.
                if (!_paused) _midletStarted = true
        }

        override fun reset(hard: Boolean) {
                // Restart the MIDlet by unloading and reloading.
                val loader = microLoader ?: return
                val romPath = loader.javaClass.declaredFields
                        .find { it.name == "appDir" }
                        ?.apply { isAccessible = true }
                        ?.get(loader) as? File
                        ?: return
                unload()
                // Re-use loadRom with the same path.
                loadRom(romPath, "", "") {}
        }

        override fun unload() {
                // Guard against re-entry: MidletThread.notifyDestroyed() calls
                // host.requestExit() → this.unload() after the MIDlet has already
                // been destroyed. The isLoaded flag prevents the double-unload.
                if (!isLoaded) return
                isLoaded = false

                currentDisplayable = null
                displayableView = null
                previousPadBits = 0
                _midletStarted = false

                // Ask the MIDlet to destroy itself. This posts a DESTROY message
                // to MidletThread; when it completes, notifyDestroyed() will call
                // requestExit() → this.unload() again, which returns immediately
                // because isLoaded is already false.
                // embedded stays true so notifyDestroyed() does NOT kill the
                // process — the app returns to the game library instead.
                MidletThread.destroyApp()
                microLoader = null
        }

        override fun shutdown() = unload()

        /**
         * Translate EmulatorScreen gamepad bit-mask into J2ME key events.
         *
         * The EmulatorScreen overlay pushes a snapshot of all button states
         * via setPad1(bits). We diff against the previous snapshot and post
         * keyPressed / keyReleased for each button whose state changed, so
         * the MIDlet sees proper press/release pairs rather than a stream of
         * repeated presses.
         */
        override fun setPad1(bits: Int) {
                val canvas = currentDisplayable as? Canvas ?: return
                val prev = previousPadBits
                previousPadBits = bits
                val custom = customKeyMap

                for ((bit, defaultKey) in keyMap) {
                        val keyCode = custom[bit] ?: defaultKey
                        val wasPressed = (prev and bit) != 0
                        val isPressed = (bits and bit) != 0
                        if (wasPressed != isPressed) {
                                if (isPressed) {
                                        canvas.postKeyPressed(keyCode)
                                } else {
                                        canvas.postKeyReleased(keyCode)
                                }
                        }
                }

                // 数字键兼作方向键：2/4/6/8/5 追加派发方向/确认键（真机行为），
                // 由 javaNumDualDispatch 设置控制，两种输入模式都生效。
                if (phoneDualDispatch) {
                        for ((bit, keyCode) in dualKeyMap) {
                                val wasPressed = (prev and bit) != 0
                                val isPressed = (bits and bit) != 0
                                if (wasPressed != isPressed) {
                                        if (isPressed) {
                                                canvas.postKeyPressed(keyCode)
                                        } else {
                                                canvas.postKeyReleased(keyCode)
                                        }
                                }
                        }
                }
        }

        override fun setPad2(bits: Int) {} // J2ME is single-player

        /**
         * GameBox: 触屏注入 —— 手柄覆盖层把"未命中任何按键"的游戏区域触摸
         * 转发到这里（坐标已换算为游戏视图局部 px）。转发给当前 Canvas，
         * 由 postTouchAction 完成虚拟坐标换算并投递 MIDlet 触屏事件。
         * 没有这条链路时，覆盖层可见期间 Compose 命中测试不会穿透到下层
         * AndroidView，J2ME 游戏的触摸输入（触屏版游戏）完全失效。
         */
        fun postTouch(actionMasked: Int, pointerId: Int, x: Float, y: Float) {
                val canvas = currentDisplayable as? Canvas
                if (canvas == null) {
                        android.util.Log.w("J2meEngine", "[postTouch] currentDisplayable null action=$actionMasked pid=$pointerId")
                        return
                }
                try {
                        canvas.postTouchAction(actionMasked, pointerId, x, y)
                } catch (t: Throwable) {
                        // 触屏注入失败不应影响模拟（旧游戏/异常坐标等场景）
                        android.util.Log.e("J2meEngine", "[postTouch] failed action=$actionMasked pid=$pointerId x=$x y=$y", t)
                }
        }

        override var frameHook: NetplayHook? = null
        override fun setRegion(region: Int) {}
        override fun setSampleRate(rate: Int) {}
        override fun saveState(slot: Int, dst: File): Boolean = false
        override fun loadState(slot: Int, src: File): Boolean = false
        override fun captureFrame(): FrameCapture? = null
        override fun lastError(): String = lastErr

        // ─── J2meHost implementation ────────────────────────────────────────

        /**
         * Called by [javax.microedition.lcdui.Display.setCurrent] whenever the
         * MIDlet switches its active screen (Canvas → Form → Alert → …).
         *
         * The Displayable's Android [android.view.View] is created lazily by
         * [Displayable.getDisplayableView] and MUST be built on the UI thread
         * (it constructs LinearLayout / SurfaceView / …). We post the view
         * creation to the main looper and store the result for EmulatorScreen
         * to host.
         */
        override fun setCurrent(displayable: Displayable) {
                currentDisplayable = displayable
                Handler(Looper.getMainLooper()).post {
                        try {
                                displayableView = displayable.getDisplayableView()
                        } catch (e: Exception) {
                                lastErr = "getDisplayableView failed: ${e.message}"
                                displayableView = null
                        }
                }
        }

        override fun getCurrent(): Displayable? = currentDisplayable

        override fun isVisible(): Boolean = isLoaded && !_paused

        /**
         * Called by [MidletThread.notifyDestroyed] when the MIDlet exits.
         * In embedded mode this unloads the engine and returns control to the
         * game library WITHOUT killing the process.
         */
        override fun requestExit() = unload()

        /**
         * @return the Android [android.view.View] for the currently displayed
         * [Displayable], or null. EmulatorScreen hosts this view inside its
         * Compose hierarchy via AndroidView.
         */
        fun getDisplayableView(): android.view.View? = displayableView

        /**
         * 应用 GameBox 设置的虚拟分辨率（游戏内 Canvas 的逻辑分辨率）。
         * javaResolution == "default" 时不干预（保留每游戏 config.json 的值）；
         * "auto" 表示跟随设备屏幕（setVirtualSize(-1, -1)）；
         * 其他值形如 "240x320"，强制指定逻辑分辨率。
         * 调用后立即刷新当前 Canvas，让设置即时生效。
         *
         * @return true 表示应用了非默认分辨率
         */
        fun applyVirtualResolution(resolution: String): Boolean {
                when (resolution) {
                        "default", "" -> return false
                        "auto" -> Displayable.setVirtualSize(-1, -1)
                        else -> {
                                val parts = resolution.lowercase().split('x')
                                val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return false
                                val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return false
                                if (w <= 0 || h <= 0) return false
                                Displayable.setVirtualSize(w, h)
                        }
                }
                // 已显示的 Canvas 立即重算尺寸，无需等待游戏切换屏幕。
                (currentDisplayable as? Canvas)?.updateSize()
                return true
        }

        /**
         * ★ v4.1 让缩放/重力等显示设置对"正在运行"的游戏立即生效。
         *
         * Canvas.setScale / setGraphicsMode 等只更新 Canvas 的静态字段，不会
         * 触发已显示 Canvas 重算虚拟屏幕几何 —— 旧版在设置面板改「画面缩放」
         * 后画面毫无变化，必须退出重进游戏（用户反馈"自定义布局/缩放没效果"
         * 的组成部分）。这里主动调用当前 Canvas.updateSize()：按新 scaleType /
         * scaleRatio / gravity 重算 onX/onY/onWidth/onHeight 并 repaint。
         * updateSize() 内部自带 bufferLock 同步，在任意线程调用安全。
         */
        fun refreshCanvasLayout() {
                val displayable = currentDisplayable
                if (displayable is javax.microedition.lcdui.Canvas) {
                        try {
                                displayable.updateSize()
                        } catch (e: Throwable) {
                                // Canvas 尚未完成首帧布局（offscreen 未建）等边界
                                // 情况 —— updateSize 内部对未初始化状态已有兜底，
                                // 这里再挡一层，设置重放绝不能把游戏线程打崩。
                        }
                }
        }

        private var lastErr: String = ""

        companion object {
                @Volatile
                private var instance: J2meEngine? = null

                /** 设置里的按键映射 id → EmulatorScreen 按钮 bit。 */
                internal val CUSTOM_BUTTON_BITS: Map<String, Int> = mapOf(
                        "up" to 0x0010, "down" to 0x0020,
                        "left" to 0x0040, "right" to 0x0080,
                        "a" to 0x0001, "b" to 0x0002,
                        "x" to 0x0100, "y" to 0x0200,
                        "start" to 0x0008, "select" to 0x0004,
                )

                fun get(): J2meEngine = instance ?: synchronized(this) {
                        instance ?: J2meEngine().also { instance = it }
                }
        }
}
