/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2022 Nikita Shakarun
 * Copyright 2018-2022 Yriy Kharchenko
 * Copyright 2023 Arman Jussupgaliyev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.lcdui;

import static android.opengl.GLES20.*;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.lcdui.commands.AbstractSoftKeysBar;
import javax.microedition.lcdui.event.CanvasEvent;
import javax.microedition.lcdui.event.Event;
import javax.microedition.lcdui.event.EventFilter;
import javax.microedition.lcdui.event.EventQueue;
import javax.microedition.lcdui.graphics.CanvasView;
import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.lcdui.graphics.GlesView;
import javax.microedition.lcdui.graphics.J2meBitmapFilter;
import javax.microedition.lcdui.graphics.J2meFilterShaders;
import javax.microedition.lcdui.graphics.ShaderProgram;
import javax.microedition.lcdui.keyboard.KeyMapper;
import javax.microedition.lcdui.keyboard.VirtualKeyboard;
import javax.microedition.lcdui.overlay.FpsCounter;
import javax.microedition.lcdui.overlay.Layer;
import javax.microedition.lcdui.overlay.Overlay;
import javax.microedition.lcdui.overlay.OverlayView;
import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import com.nesstation.app.R;
import ru.playsoftware.j2meloader.config.ShaderInfo;

@SuppressWarnings({"WeakerAccess", "unused"})
public abstract class Canvas extends Displayable {
        private static final String TAG = Canvas.class.getName();

        public static final int KEY_POUND = 35;
        public static final int KEY_STAR = 42;
        public static final int KEY_NUM0 = 48;
        public static final int KEY_NUM1 = 49;
        public static final int KEY_NUM2 = 50;
        public static final int KEY_NUM3 = 51;
        public static final int KEY_NUM4 = 52;
        public static final int KEY_NUM5 = 53;
        public static final int KEY_NUM6 = 54;
        public static final int KEY_NUM7 = 55;
        public static final int KEY_NUM8 = 56;
        public static final int KEY_NUM9 = 57;

        public static final int KEY_UP = -1;
        public static final int KEY_DOWN = -2;
        public static final int KEY_LEFT = -3;
        public static final int KEY_RIGHT = -4;
        public static final int KEY_FIRE = -5;
        public static final int KEY_SOFT_LEFT = -6;
        public static final int KEY_SOFT_RIGHT = -7;
        public static final int KEY_CLEAR = -8;
        public static final int KEY_SEND = -10;
        public static final int KEY_END = -11;

        public static final int UP = 1;
        public static final int LEFT = 2;
        public static final int RIGHT = 5;
        public static final int DOWN = 6;
        public static final int FIRE = 8;
        public static final int GAME_A = 9;
        public static final int GAME_B = 10;
        public static final int GAME_C = 11;
        public static final int GAME_D = 12;

        private static final float FULLSCREEN_HEIGHT_RATIO = 0.85f;

        private static boolean filter;
        private static boolean touchInput;
        private static int graphicsMode;
        private static ShaderInfo shaderFilter;
        private static boolean parallelRedraw;
        private static boolean forceFullscreen;
        private static boolean showFps;
        private static int backgroundColor;
        private static int scaleRatio;
        private static int fpsLimit;
        private static boolean screenshotRawMode;
        private static int scaleType;
        private static int screenGravity;
        /** J2ME video filter mode (0=none,1=scanline,2=CRT,3=dot,4=2XBR,5=4XBR,6=2XBR+dot,7=4XBR+dot,8=HQ4x,9=HQ4x+dot) */
        private static volatile int j2meFilterMode = 0;

        private final Object bufferLock = new Object();
        private final Object surfaceLock = new Object();
        private final PaintEvent paintEvent = new PaintEvent();
        private final SoftBar softBar = new SoftBar();
        private final CanvasWrapper canvasWrapper = new CanvasWrapper(filter);
        private final RectF virtualScreen = new RectF();

        protected int width, height;
        protected int maxHeight;
        private LinearLayout layout;
        private SurfaceView innerView;
        private Surface surface;
        private GLRenderer renderer;
        private int displayWidth;
        private int displayHeight;
        private boolean fullscreen;
        private boolean visible;
        private boolean sizeChangedCalled;
        private Image offscreen;
        private Image offscreenCopy;
        private int onX, onY, onWidth, onHeight;
        private long lastFrameTime = System.currentTimeMillis();
        private Handler uiHandler;
        private Overlay overlay;
        private FpsCounter fpsCounter;
        private boolean skipLeftSoft;
        private boolean skipRightSoft;
        private int[][] lastPointerPos = new int[20][2];

        protected Canvas() {
                this(forceFullscreen);
        }

        protected Canvas(boolean fullscreen) {
                this.fullscreen = fullscreen;
                super.softBar = softBar;
                if (graphicsMode == 1) {
                        renderer = new GLRenderer();
                }
                if (parallelRedraw) {
                        uiHandler = new Handler(Looper.getMainLooper(), msg -> repaintScreen());
                }
                displayWidth = ContextHolder.getDisplayWidth();
                displayHeight = ContextHolder.getDisplayHeight();
                updateSize();
        }

        public static void setShaderFilter(ShaderInfo shader) {
                Canvas.shaderFilter = shader;
        }

        /**
         * Sets the J2ME video filter mode. This is called by MicroActivity when the
         * user selects a filter from the floating menu.
         *
         * Filter modes (must match {@link J2meFilterShaders} / {@link J2meBitmapFilter}):
         *   0 = None, 1 = Scanline, 2 = CRT, 3 = Dot,
         *   4 = 2xBR, 5 = 4xBR, 6 = 2xBR+Dot, 7 = 4xBR+Dot,
         *   8 = HQ4x, 9 = HQ4x+Dot
         *
         * <p>All filter modes are handled by the active rendering pipeline:
         * <ul>
         *   <li><b>GL mode (graphicsMode == 1)</b>: {@link GLRenderer#switchProgram}
         *       recompiles the GLSL shader on the GL thread on the next frame.
         *       This is detected via {@code fm != lastFilterMode} in
         *       {@link GLRenderer#onDrawFrame}.</li>
         *   <li><b>Non-GL modes (graphicsMode == 0, 2, 3)</b>: {@link #repaintScreen}
         *       and {@link #onDraw} dispatch to {@link J2meBitmapFilter#drawFiltered}
         *       which implements all filter modes (XBR / HQ4x / scanline / CRT / dot)
         *       on the CPU.</li>
         * </ul>
         *
         * <p><b>NOTE:</b> Previously this method forced a switch to GL mode
         * (graphicsMode=1) when an XBR/HQ4x filter (modes 4-9) was selected.
         * That switch was broken — {@link #setGraphicsMode} only updates the
         * static field, but the {@link Canvas} instance had already been
         * constructed without a {@link GLRenderer} (because the initial
         * graphicsMode was 0), and {@link #getDisplayableView} had already
         * cached a {@link CanvasView} as {@code innerView}. The next repaint
         * would then NPE in {@link #requestFlushToScreen} when calling
         * {@code renderer.requestRender()} on a null renderer, silently
         * breaking xBR rendering.
         *
         * <p>The CPU path ({@link J2meBitmapFilter}) implements the exact same
         * Hyllian 2xBR/4xBR and guest(r) HQ4x algorithms as the GLSL shaders,
         * so removing the GL mode switch restores xBR rendering without
         * requiring a View hierarchy rebuild. Users who want GPU-accelerated
         * xBR should select GL mode (graphicsMode=1) in their profile settings
         * before launching the game.
         *
         * @param mode filter mode (0-9)
         */
        public static void setJ2meFilterMode(int mode) {
                Canvas.j2meFilterMode = mode;
                // No graphicsMode switch — see method javadoc above.
                // The CPU filter path (J2meBitmapFilter) handles all modes in
                // repaintScreen()/onDraw(); the GL path (GLRenderer.switchProgram)
                // handles all modes when graphicsMode is already 1.
        }

        /** Returns the current J2ME filter mode. */
        public static int getJ2meFilterMode() {
                return j2meFilterMode;
        }

        public static void setScale(int screenGravity, int scaleType, int scaleRatio) {
                Canvas.screenGravity = screenGravity;
                Canvas.scaleType = scaleType;
                Canvas.scaleRatio = scaleRatio;
        }

        public static void setBackgroundColor(int color) {
                backgroundColor = color | 0xFF000000;
        }

        public static void setFilterBitmap(boolean filter) {
                Canvas.filter = filter;
        }

        public static void setHasTouchInput(boolean touchInput) {
                Canvas.touchInput = touchInput;
        }

        public static void setGraphicsMode(int mode, boolean parallel) {
                Canvas.graphicsMode = mode;
                Canvas.parallelRedraw = (mode == 0 || mode == 3) && parallel;
        }

        public static void setForceFullscreen(boolean forceFullscreen) {
                Canvas.forceFullscreen = forceFullscreen;
        }

        public static void setShowFps(boolean showFps) {
                Canvas.showFps = showFps;
        }

        public static void setLimitFps(int fpsLimit) {
                if (fpsLimit == 0 && (graphicsMode == 1 || graphicsMode == 2)) {
                        // hack for async redraw
                        fpsLimit = 1000;
                }
                Canvas.fpsLimit = fpsLimit;
        }

        public static void setScreenshotRawMode(boolean enable) {
                screenshotRawMode = enable;
        }

        public int getKeyCode(int gameAction) {
                int res = KeyMapper.getKeyCode(gameAction);
                if (res != Integer.MAX_VALUE) {
                        return res;
                } else {
                        throw new IllegalArgumentException("unknown game action " + gameAction);
                }
        }

        public int getGameAction(int keyCode) {
                return KeyMapper.getGameAction(keyCode);
        }

        public String getKeyName(int keyCode) {
                String res = KeyMapper.getKeyName(keyCode);
                if (res != null) {
                        return res;
                } else {
                        throw new IllegalArgumentException("unknown keycode " + keyCode);
                }
        }

        public void postKeyPressed(int keyCode) {
                if (keyCode == KEY_SOFT_LEFT && softBar.fireLeftSoft()) {
                        skipLeftSoft = true;
                        return;
                } else if (keyCode == KEY_SOFT_RIGHT && softBar.fireRightSoft()) {
                        skipRightSoft = true;
                        return;
                }
                Display.postEvent(CanvasEvent.getInstance(this,
                                CanvasEvent.KEY_PRESSED,
                                KeyMapper.convertKeyCode(keyCode)));
        }

        public void postKeyReleased(int keyCode) {
                if (keyCode == KEY_SOFT_LEFT && skipLeftSoft) {
                        skipLeftSoft = false;
                        return;
                } else if (keyCode == KEY_SOFT_RIGHT && skipRightSoft) {
                        skipRightSoft = false;
                        return;
                }
                Display.postEvent(CanvasEvent.getInstance(this,
                                CanvasEvent.KEY_RELEASED,
                                KeyMapper.convertKeyCode(keyCode)));
        }

        public void postKeyRepeated(int keyCode) {
                if (keyCode == KEY_SOFT_LEFT && skipLeftSoft) {
                        return;
                } else if (keyCode == KEY_SOFT_RIGHT && skipRightSoft) {
                        return;
                }
                Display.postEvent(CanvasEvent.getInstance(this,
                                CanvasEvent.KEY_REPEATED,
                                KeyMapper.convertKeyCode(keyCode)));
        }

        /**
         * GameBox: 嵌入式模式的触屏注入入口。
         *
         * Compose 的虚拟手柄覆盖层是游戏视图（AndroidView 内的
         * GlesView/CanvasView）的高 z 兄弟节点，命中测试不会穿透到手柄下方
         * —— 覆盖层可见时 ViewCallbacks.onTouch 永远收不到游戏区域的触摸。
         * 覆盖层把"未命中任何按键"的触摸转发出来后，宿主经 J2meEngine 调用
         * 本方法，复用与 onTouch 完全相同的坐标换算（视图坐标 → 虚拟画布
         * 坐标）与事件投递路径，让 MIDlet 的 pointerPressed/Dragged/Released
         * 正常触发。
         *
         * @param actionMasked MotionEvent.ACTION_DOWN/POINTER_DOWN/MOVE/UP/POINTER_UP/CANCEL
         * @param pointerId    指针 id（多点触控）
         * @param x            视图局部坐标（innerView 坐标系，即 surface 像素坐标）
         * @param y            视图局部坐标
         */
        public void postTouchAction(int actionMasked, int pointerId, float x, float y) {
                // GameBox: 嵌入式模式的几何自愈（对齐 NDS 直接触摸"按当前几何
                // 映射"的思路，而不是依赖可能过期的布局数据）。虚拟画面矩形
                // virtualScreen 依赖 displayWidth/Height，它们由 innerView 的
                // surfaceChanged 回调驱动；若宿主视图几何晚于 Canvas 构造（如
                // surface 尚未回调）或中途变化（旋转/自由布局拖动后回调尚未
                // 落地），转发触摸时先按 innerView 当前实际尺寸刷新，避免用
                // 过期几何换算出错误坐标（甚至除零）。
                if (innerView != null) {
                        int vw = innerView.getWidth();
                        int vh = innerView.getHeight();
                        if (vw > 0 && vh > 0 && (vw != displayWidth || vh != displayHeight)) {
                                displayWidth = vw;
                                displayHeight = vh;
                                updateSize();
                        }
                }
                // 转发路径的坐标是"宿主 AndroidView 局部"坐标；onTouch 直达
                // 路径是"innerView 局部"坐标。当 LinearLayout 里存在 ticker
                // 跑马灯等位于 innerView 之前的兄弟视图时两者相差 innerView
                // 的偏移 —— 先校正，两条路径才能共用同一套换算。
                if (innerView != null) {
                        x -= innerView.getLeft();
                        y -= innerView.getTop();
                }
                // virtualScreen 兜底：极端情况下（updateSize 尚未以真实尺寸
                // 运行，矩形仍为空）不把触摸整颗丢弃，而是按整个视图映射，
                // 保证注入链路始终可达。
                if (virtualScreen.isEmpty()) {
                        onX = 0;
                        onY = 0;
                        onWidth = Math.max(displayWidth, 1);
                        onHeight = Math.max(displayHeight, 1);
                        virtualScreen.set(onX, onY, onX + onWidth, onY + onHeight);
                }
                if (!touchInput || !touchWithinVirtualScreen(x, y)) {
                        // GameBox 诊断日志：记录被丢弃的触摸，确认转发链路
                        // 是否在这里提前 return（touchInput=false 或坐标出界）。
                        Log.d(TAG, "[postTouchAction] drop action=" + actionMasked
                                + " pid=" + pointerId + " x=" + x + " y=" + y
                                + " touchInput=" + touchInput
                                + " vscreen=" + virtualScreen);
                        return;
                }
                switch (actionMasked) {
                        case android.view.MotionEvent.ACTION_DOWN:
                        case android.view.MotionEvent.ACTION_POINTER_DOWN: {
                                int cX = clampPointer(Math.round(convertPointerX(x)), width);
                                int cY = clampPointer(Math.round(convertPointerY(y)), height);
                                if (pointerId < 20) {
                                        lastPointerPos[pointerId][0] = cX;
                                        lastPointerPos[pointerId][1] = cY;
                                }
                                Log.d(TAG, "[postTouchAction] DOWN pid=" + pointerId
                                        + " in=(" + x + "," + y + ") canvas=(" + cX + "," + cY + ")"
                                        + " immediate=" + EventQueue.isImmediate());
                                deliverPointerEvent(CanvasEvent.POINTER_PRESSED, pointerId, cX, cY);
                                break;
                        }
                        case android.view.MotionEvent.ACTION_MOVE: {
                                int cX = clampPointer(Math.round(convertPointerX(x)), width);
                                int cY = clampPointer(Math.round(convertPointerY(y)), height);
                                if (pointerId < 20) {
                                        int oX = lastPointerPos[pointerId][0];
                                        int oY = lastPointerPos[pointerId][1];
                                        if (oX == cX && oY == cY) {
                                                break;
                                        }
                                        lastPointerPos[pointerId][0] = cX;
                                        lastPointerPos[pointerId][1] = cY;
                                }
                                deliverPointerEvent(CanvasEvent.POINTER_DRAGGED, pointerId, cX, cY);
                                break;
                        }
                        case android.view.MotionEvent.ACTION_UP:
                        case android.view.MotionEvent.ACTION_POINTER_UP: {
                                int cX = clampPointer(Math.round(convertPointerX(x)), width);
                                int cY = clampPointer(Math.round(convertPointerY(y)), height);
                                if (pointerId < 20) {
                                        lastPointerPos[pointerId][0] = cX;
                                        lastPointerPos[pointerId][1] = cY;
                                }
                                Log.d(TAG, "[postTouchAction] UP pid=" + pointerId
                                        + " in=(" + x + "," + y + ") canvas=(" + cX + "," + cY + ")");
                                deliverPointerEvent(CanvasEvent.POINTER_RELEASED, pointerId, cX, cY);
                                break;
                        }
                        case android.view.MotionEvent.ACTION_CANCEL: {
                                // 注入路径的手势被取消（覆盖层重组/协程中断）时
                                // 必须补发释放 —— 否则 MIDlet 永远认为手指仍按在
                                // 画面上（卡触摸）。NDS 转发路径同样以"离开即释放"
                                // 语义处理，这里保持一致。坐标取取消前的最后位置。
                                int cX = clampPointer(Math.round(convertPointerX(x)), width);
                                int cY = clampPointer(Math.round(convertPointerY(y)), height);
                                Log.d(TAG, "[postTouchAction] CANCEL pid=" + pointerId
                                        + " in=(" + x + "," + y + ") canvas=(" + cX + "," + cY + ")");
                                deliverPointerEvent(CanvasEvent.POINTER_RELEASED, pointerId, cX, cY);
                                break;
                        }
                        default:
                                break;
                }
        }

        /**
         * GameBox: 注入路径的指针事件投递 —— 与 onTouch 直达路径一致，统一
         * 经 Display.postEvent 入队，由 MIDletEventQueue 线程串行处理。
         *
         * 早期版本曾在调用线程（Compose 覆盖层的输入协程线程）同步
         * event.run() 绕过队列。但那会让触摸事件与按键/绘制事件（仍由
         * MIDletEventQueue 线程处理）在不同线程交错执行，破坏 J2ME 事件
         * 串行化 —— 游戏的输入状态被并发修改，表现为"触屏总是被关掉"。
         * 队列线程的健壮性（死亡自愈、排队计数复位、单个脏区绘制）已由
         * EventQueue 保证，无需绕过队列；触摸事件会在队列线程上按 FIFO
         * 得到及时处理，与按键/绘制保持同一线程的串行语义。
         */
        private void deliverPointerEvent(int eventType, int pointerId, int canvasX, int canvasY) {
                Display.postEvent(CanvasEvent.getInstance(this, eventType, pointerId, canvasX, canvasY));
        }

        /** GameBox: 虚拟画面命中的带容差判断（转发触摸专用）。 */
        private boolean touchWithinVirtualScreen(float x, float y) {
                return virtualScreen != null &&
                        x >= virtualScreen.left - 2f && x <= virtualScreen.right + 2f &&
                        y >= virtualScreen.top - 2f && y <= virtualScreen.bottom + 2f;
        }

        /** GameBox: 把虚拟坐标钳制到画布有效范围内。 */
        private int clampPointer(int v, int maxExclusive) {
                if (maxExclusive <= 1) {
                        return 0;
                }
                return Math.max(0, Math.min(maxExclusive - 1, v));
        }

        public void doShowNotify() {
                visible = true;
                showNotify();
        }

        public void doHideNotify() {
                hideNotify();
                visible = false;
        }

        public void onDraw(android.graphics.Canvas canvas) {
                if (graphicsMode != 2) return; // Fix for Android Pie
                CanvasWrapper g = canvasWrapper;
                g.bind(canvas);
                g.clear(backgroundColor);
                synchronized (bufferLock) {
                        int fm = j2meFilterMode;
                        if (fm != 0) {
                                // ALL filter modes go through J2meBitmapFilter — no FcFilterView overlay
                                offscreenCopy.getBitmap().prepareToDraw();
                                J2meBitmapFilter.drawFiltered(offscreenCopy.getBitmap(),
                                        offscreenCopy.getWidth(), offscreenCopy.getHeight(),
                                        canvas, virtualScreen, fm);
                        } else {
                                offscreenCopy.getBitmap().prepareToDraw();
                                g.drawImage(offscreenCopy, virtualScreen);
                        }
                }
                if (fpsCounter != null) {
                        fpsCounter.increment();
                }
        }

        public Single<Bitmap> getScreenShot() {
                if (renderer != null && !screenshotRawMode) {
                        return renderer.takeScreenShot();
                }
                return Single.create(emitter -> {
                        Bitmap bitmap;
                        if (screenshotRawMode) {
                                synchronized (bufferLock) {
                                        bitmap = Bitmap.createBitmap(offscreenCopy.getBitmap(), 0, 0,
                                                        offscreenCopy.getWidth(), offscreenCopy.getHeight());
                                }
                        } else {
                                bitmap = Bitmap.createBitmap(onWidth, onHeight, Bitmap.Config.ARGB_8888);
                                canvasWrapper.bind(new android.graphics.Canvas(bitmap));
                                synchronized (bufferLock) {
                                        canvasWrapper.drawImage(offscreenCopy, new RectF(0, 0, onWidth, onHeight));
                                }
                        }
                        emitter.onSuccess(bitmap);
                });
        }

        private boolean checkSizeChanged() {
                int tmpWidth = width;
                int tmpHeight = height;
                updateSize();
                return width != tmpWidth || height != tmpHeight;
        }

        /**
         * Update the size and position of the virtual screen relative to the real one.
         */
        public void updateSize() {
                /*
                 * We turn the sizes of the virtual screen into the sizes of the visible canvas.
                 *
                 * At the same time, we take into account that one or both virtual sizes can be less
                 * than zero, which means auto-selection of this size so that the resulting canvas
                 * has the same aspect ratio as the actual screen of the device.
                 */
                int scaledDisplayHeight;
                VirtualKeyboard vk = ContextHolder.getVk();
                boolean isPhoneSkin = vk != null && vk.isPhone();

                // if phone keyboard layout is active, then scale down the virtual screen
                if (isPhoneSkin) {
                        float vkHeight = vk.getPhoneKeyboardHeight(displayWidth, displayHeight);
                        scaledDisplayHeight = (int) (displayHeight - vkHeight - 1);
                } else {
                        scaledDisplayHeight = displayHeight;
                }
                if (virtualWidth > 0) {
                        if (virtualHeight > 0) {
                                /*
                                 * the width and height of the canvas are strictly set
                                 */
                                width = virtualWidth;
                                height = virtualHeight;
                        } else {
                                /*
                                 * only the canvas width is set
                                 * height is selected by the ratio of the real screen
                                 */
                                width = virtualWidth;
                                height = scaledDisplayHeight * virtualWidth / displayWidth;
                        }
                } else {
                        if (virtualHeight > 0) {
                                /*
                                 * only the canvas height is set
                                 * width is selected by the ratio of the real screen
                                 */
                                width = displayWidth * virtualHeight / scaledDisplayHeight;
                                height = virtualHeight;
                        } else {
                                /*
                                 * nothing is set - screen-sized canvas
                                 */
                                width = displayWidth;
                                height = scaledDisplayHeight;
                        }
                }

                /*
                 * We turn the size of the canvas into the size of the image
                 * that will be displayed on the screen of the device.
                 */
                int scaleRatio = Canvas.scaleRatio;
                switch (scaleType) {
                        case 0:
                                // without scaling
                                onWidth = width;
                                onHeight = height;
                                break;
                        case 1:
                                // try to fit in width
                                onWidth = displayWidth;
                                onHeight = height * displayWidth / width;
                                if (onHeight > scaledDisplayHeight) {
                                        // if height is too big, then fit in height
                                        onHeight = scaledDisplayHeight;
                                        onWidth = width * scaledDisplayHeight / height;
                                }
                                if (scaleRatio > 100) {
                                        scaleRatio = 100;
                                }
                                break;
                        case 2:
                                // scaling without preserving the aspect ratio:
                                // just stretch the picture to full screen
                                onWidth = displayWidth;
                                onHeight = scaledDisplayHeight;
                                if (scaleRatio > 100) {
                                        scaleRatio = 100;
                                }
                                break;
                }

                onWidth = onWidth * scaleRatio / 100;
                onHeight = onHeight * scaleRatio / 100;

                // Make screenGravity orientation-aware:
                // In landscape, "top" gravity should become "center" for a balanced look.
                // In portrait, "top" is correct (leaves room for virtual keyboard at bottom).
                int effectiveGravity = Canvas.screenGravity;
                boolean isLandscape = displayWidth > displayHeight;
                if (isLandscape && effectiveGravity == 1) {
                        effectiveGravity = 2; // landscape: top → center
                }

                switch (effectiveGravity) {
                        case 0: // left
                                onX = 0;
                                onY = (scaledDisplayHeight - onHeight) / 2;
                                break;
                        case 1: // top
                                onX = (displayWidth - onWidth) / 2;
                                onY = 0;
                                break;
                        case 2: // center
                                onX = (displayWidth - onWidth) / 2;
                                onY = (scaledDisplayHeight - onHeight) / 2;
                                break;
                        case 3: // right
                                onX = displayWidth - onWidth;
                                onY = (scaledDisplayHeight - onHeight) / 2;
                                break;
                        case 4: // bottom
                                onX = (displayWidth - onWidth) / 2;
                                onY = scaledDisplayHeight - onHeight;
                                break;
                }


                /*
                 * calculate the maximum height
                 */
                maxHeight = height;

                softBar.resize();
                /*
                 * calculate the current height
                 */
                float softBarHeight = softBar.bounds.height();
                if (softBarHeight > 0) {
                        float scaleY = (float) onHeight / height;
                        height = (int) (height - softBarHeight / scaleY);
                        onHeight -= softBarHeight;
                }

                RectF screen = new RectF(0, 0, displayWidth, displayHeight);
                virtualScreen.set(onX, onY, onX + onWidth, onY + onHeight);

                synchronized (bufferLock) {
                        if (offscreen == null) {
                                offscreen = Image.createImage(width, maxHeight, 0);
                                offscreenCopy = Image.createImage(width, maxHeight, 0);
                        }
                        if (offscreen.getWidth() != width || offscreen.getHeight() != height) {
                                offscreen.setSize(width, height);
                                offscreenCopy.setSize(width, height);
                        }
                }
                if (overlay != null) {
                        overlay.resize(screen, onX, onY, onX + onWidth, onY + onHeight + softBarHeight);
                }

                if (graphicsMode == 1) {
                        float gl = 2.0f * virtualScreen.left / displayWidth - 1.0f;
                        float gt = 1.0f - 2.0f * virtualScreen.top / displayHeight;
                        float gr = 2.0f * virtualScreen.right / displayWidth - 1.0f;
                        float gb = 1.0f - 2.0f * virtualScreen.bottom / displayHeight;
                        float th = (float) offscreen.getHeight() / offscreen.getBitmap().getHeight();
                        float tw = (float) offscreen.getWidth() / offscreen.getBitmap().getWidth();
                        renderer.updateSize(gl, gt, gr, gb, th, tw);
                }
                repaintInternal();
        }

        /**
         * Convert the screen coordinates of the pointer into the virtual ones.
         *
         * @param x the pointer coordinate on the real screen
         * @return the corresponding pointer coordinate on the virtual screen
         */
        private float convertPointerX(float x) {
                return (x - onX) * width / onWidth;
        }

        /**
         * Convert the screen coordinates of the pointer into the virtual ones.
         *
         * @param y the pointer coordinate on the real screen
         * @return the corresponding pointer coordinate on the virtual screen
         */
        private float convertPointerY(float y) {
                return (y - onY) * height / onHeight;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
                        public View getDisplayableView() {
                                if (layout == null) {
                                        layout = (LinearLayout) super.getDisplayableView();
                                        // getContext() falls back to the Application
                                        // context in embedded (non-Activity) mode;
                                        // GlesView/CanvasView only need a Context.
                                        Context activity = ContextHolder.getContext();
                                        if (graphicsMode == 1) {                                GlesView glesView = new GlesView(activity);
                                glesView.setRenderer(renderer);
                                glesView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
                                renderer.setView(glesView);
                                innerView = glesView;
                        } else {
                                CanvasView canvasView = new CanvasView(this, activity);
                                if (graphicsMode == 2) {
                                        canvasView.setWillNotDraw(false);
                                }
                                canvasView.getHolder().setFormat(PixelFormat.RGBA_8888);
                                innerView = canvasView;
                        }
                        ViewCallbacks callback = new ViewCallbacks(innerView);
                        innerView.getHolder().addCallback(callback);
                        innerView.setOnTouchListener(callback);
                        innerView.setOnKeyListener(callback);
                        innerView.setFocusableInTouchMode(true);
                        layout.addView(innerView, new android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT));
                        innerView.requestFocus();
                }
                return layout;
        }

        @Override
        public void clearDisplayableView() {
                super.clearDisplayableView();
                layout = null;
                innerView = null;
        }

        public void setFullScreenMode(boolean flag) {
                if (fullscreen == flag) {
                        return;
                }
                fullscreen = flag;
                updateSize();
                softBar.notifyChanged();
                if (!visible) {
                        return;
                }
                Display.postEvent(CanvasEvent.getInstance(this, CanvasEvent.SIZE_CHANGED, width, height));
                repaintInternal();
        }

        public boolean hasPointerEvents() {
                return touchInput;
        }

        public boolean hasPointerMotionEvents() {
                return touchInput;
        }

        public boolean hasRepeatEvents() {
                return true;
        }

        public boolean isDoubleBuffered() {
                return true;
        }

        @Override
        public int getWidth() {
                return width;
        }

        @Override
        public int getHeight() {
                return height;
        }

        protected abstract void paint(Graphics g);

        public final void repaint() {
                repaint(0, 0, width, height);
        }

        public final void repaint(int x, int y, int width, int height) {
                limitFps();
                boolean post;
                synchronized (paintEvent) {
                        post = paintEvent.invalidateClip(this, x, y, x + width, y + height) && !paintEvent.isPending;
                        if (post) {
                                paintEvent.isPending = true;
                        }
                }
                if (post) {
                        Display.postEvent(paintEvent);
                }
        }

        private void repaintInternal() {
                synchronized (paintEvent) {
                        paintEvent.invalidateClip(this, 0, 0, width, height);
                }
                Display.postEvent(paintEvent);
        }

        // GameCanvas
        public void flushBuffer(Image image, int x, int y, int width, int height) {
                limitFps();
                if (width <= 0 || height <= 0 ||
                                x + width < 0 || y + height < 0 ||
                                x >= this.width || y >= this.height) {
                        return;
                }
                synchronized (bufferLock) {
                        offscreenCopy.getSingleGraphics().flush(image, x, y, width, height);
                }
                requestFlushToScreen();
        }

        // ExtendedImage
        public void flushBuffer(Image image, int x, int y) {
                limitFps();
                synchronized (bufferLock) {
                        image.copyTo(offscreenCopy, x, y);
                }
                requestFlushToScreen();
        }

        private void limitFps() {
                if (fpsLimit <= 0) return;
                try {
                        long millis = (1000 / fpsLimit) - (System.currentTimeMillis() - lastFrameTime);
                        if (millis > 0) Thread.sleep(millis);
                } catch (InterruptedException e) {
                        e.printStackTrace();
                }
                lastFrameTime = System.currentTimeMillis();
        }

        @SuppressLint("NewApi")
        private boolean repaintScreen() {
                Surface surface = this.surface;
                if (surface == null || !surface.isValid()) {
                        return true;
                }
                try {
                        synchronized (surfaceLock) {
                                android.graphics.Canvas canvas = graphicsMode == 3 ?
                                                surface.lockHardwareCanvas() : surface.lockCanvas(null);
                                if (canvas == null) {
                                        return true;
                                }
                                CanvasWrapper g = this.canvasWrapper;
                                g.bind(canvas);
                                g.clear(backgroundColor);
                                synchronized (bufferLock) {
                                int fm = j2meFilterMode;
                                if (fm != 0) {
                                        // ALL filter modes go through J2meBitmapFilter — no FcFilterView overlay
                                        J2meBitmapFilter.drawFiltered(offscreenCopy.getBitmap(),
                                                offscreenCopy.getWidth(), offscreenCopy.getHeight(),
                                                canvas, virtualScreen, fm);
                                } else {
                                        g.drawImage(offscreenCopy, virtualScreen);
                                }
                        }
                                surface.unlockCanvasAndPost(canvas);
                        }
                        if (fpsCounter != null) {
                                fpsCounter.increment();
                        }
                        if (parallelRedraw) uiHandler.removeMessages(0);
                } catch (Exception e) {
                        Log.w(TAG, "repaintScreen: " + e);
                }
                return true;
        }

        /**
         * After calling this method, an immediate redraw is guaranteed to occur,
         * and the calling thread is blocked until it is completed.
         */
        public final void serviceRepaints() {
                Display.getEventQueue().serviceRepaints(paintEvent);
        }

        protected void showNotify() {
        }

        protected void hideNotify() {
        }

        protected void keyPressed(int keyCode) {
        }

        protected void keyRepeated(int keyCode) {
        }

        protected void keyReleased(int keyCode) {
        }

        public void pointerPressed(int pointer, float x, float y) {
                if (Display.isMultiTouchSupported()) {
                        Display.setPointerNumber(pointer);
                        pointerPressed(Math.round(x), Math.round(y));
                        Display.resetPointerNumber();
                } else if (pointer == 0) {
                        pointerPressed(Math.round(x), Math.round(y));
                }
        }

        public void pointerDragged(int pointer, float x, float y) {
                if (Display.isMultiTouchSupported()) {
                        Display.setPointerNumber(pointer);
                        pointerDragged(Math.round(x), Math.round(y));
                        Display.resetPointerNumber();
                } else if (pointer == 0) {
                        pointerDragged(Math.round(x), Math.round(y));
                }
        }

        public void pointerReleased(int pointer, float x, float y) {
                if (Display.isMultiTouchSupported()) {
                        Display.setPointerNumber(pointer);
                        pointerReleased(Math.round(x), Math.round(y));
                        Display.resetPointerNumber();
                } else if (pointer == 0) {
                        pointerReleased(Math.round(x), Math.round(y));
                }
        }

        protected void pointerPressed(int x, int y) {
        }

        protected void pointerDragged(int x, int y) {
        }

        protected void pointerReleased(int x, int y) {
        }

        void setInvisible() {
                this.visible = false;
        }

        public void doKeyPressed(int keyCode) {
                keyPressed(keyCode);
        }

        public void doKeyRepeated(int keyCode) {
                keyRepeated(keyCode);
        }

        public void doKeyReleased(int keyCode) {
                keyReleased(keyCode);
        }

        private class GLRenderer implements GLSurfaceView.Renderer {
                private final FloatBuffer vbo = ByteBuffer.allocateDirect(8 * 2 * 4)
                                .order(ByteOrder.nativeOrder()).asFloatBuffer();
                private GLSurfaceView mView;
                private final int[] bgTextureId = new int[1];
                private ShaderProgram program;
                /** Passthrough shader for unfiltered rendering (mode 0). */
                private ShaderProgram passthroughProgram;
                private boolean isStarted;
                /** Last filter mode compiled into the GLSL shader (to detect mode changes). */
                private int lastFilterMode = -1;
                /** Whether the GL texture has been allocated (for texSubImage2D optimization). */
                private boolean texUploaded = false;

                @Override
                public void onSurfaceCreated(GL10 gl, EGLConfig config) {
                        // Create the passthrough (no-filter) shader program
                        passthroughProgram = new ShaderProgram(J2meFilterShaders.VERTEX_SHADER,
                                        J2meFilterShaders.FRAGMENT_NONE);

                        // Determine initial program based on filter mode.
                        // All modes (0-9) now use GLSL shaders — no CPU pixel-processing path.
                        int fm = j2meFilterMode;
                        if (fm == 0) {
                                // No filter: use custom shader filter if available, otherwise passthrough
                                if (shaderFilter != null) {
                                        program = new ShaderProgram(shaderFilter);
                                } else {
                                        program = passthroughProgram;
                                }
                        } else {
                                // GLSL shader for the selected filter mode (1-9)
                                program = new ShaderProgram(J2meFilterShaders.getVertexShader(fm),
                                                J2meFilterShaders.getFragmentShader(fm));
                        }
                        lastFilterMode = fm;

                        int c = Canvas.backgroundColor;
                        glClearColor((c >> 16 & 0xff) / 255.0f, (c >> 8 & 0xff) / 255.0f, (c & 0xff) / 255.0f, 1.0f);
                        glDisable(GL_BLEND);
                        glDisable(GL_DEPTH_TEST);
                        glDepthMask(false);
                        initTex();
                        Bitmap bitmap = offscreenCopy.getBitmap();
                        program.loadVbo(vbo, bitmap.getWidth(), bitmap.getHeight());
                        if (program.uPixelDelta != -1 && mView != null) {
                                glUniform2f(program.uPixelDelta,
                                                1.0f / mView.getWidth(), 1.0f / mView.getHeight());
                        }
                        if (shaderFilter != null && shaderFilter.values != null && program.uSetting != -1) {
                                glUniform4fv(program.uSetting, 1, shaderFilter.values, 0);
                        }
                        isStarted = true;
                }

                @Override
                public void onSurfaceChanged(GL10 gl, int width, int height) {
                        glViewport(0, 0, width, height);
                        if (program != null) {
                                // u_pixelDelta = 1/screenSize (screen-space uniform)
                                if (program.uPixelDelta != -1) {
                                        glUniform2f(program.uPixelDelta, 1.0f / width, 1.0f / height);
                                }
                                // u_texelDelta = 1/textureSize — re-set here in case the surface
                                // changed without a program switch (loadVbo normally sets it).
                                Bitmap bitmap = offscreenCopy.getBitmap();
                                if (program.uTexelDelta != -1) {
                                        glUniform2f(program.uTexelDelta,
                                                        1.0f / bitmap.getWidth(), 1.0f / bitmap.getHeight());
                                }
                        }
                }

                @Override
                public void onDrawFrame(GL10 gl) {
                        int fm = j2meFilterMode;

                        // Switch shader program if the filter mode changed.
                        // All modes (0-9) are handled by GLSL shaders — no CPU pixel-processing.
                        if (fm != lastFilterMode && isStarted) {
                                switchProgram(fm);
                        }

                        // Ensure the correct shader program is active
                        if (program != null && program.programId != -1) {
                                glUseProgram(program.programId);
                        }

                        // Upload the original game bitmap as the GL texture;
                        // the GLSL shader performs all filtering on the GPU.
                        // Optimization: use glTexSubImage2D after initial allocation
                        // to avoid reallocating GPU memory every frame.
                        synchronized (bufferLock) {
                                Bitmap bmp = offscreenCopy.getBitmap();
                                if (bmp != null) {
                                        // Ensure the game texture is bound — the binding can be
                                        // lost after switchProgram() constructs a new ShaderProgram.
                                        glBindTexture(GL_TEXTURE_2D, bgTextureId[0]);
                                        if (texUploaded) {
                                                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0,
                                                                0, 0, bmp);
                                        } else {
                                                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0,
                                                                bmp, 0);
                                                texUploaded = true;
                                                // Re-apply CLAMP_TO_EDGE after texImage2D — some
                                                // drivers reset wrap mode when the texture image
                                                // is (re)allocated. Without this, XBR's multi-tap
                                                // kernel can wrap around and sample the opposite
                                                // edge of the game scene.
                                                glTexParameteri(GLES20.GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                                                glTexParameteri(GLES20.GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                                        }
                                }
                        }
                        // Clear the framebuffer to the background color so the area
                        // outside the game quad (e.g. the black band below the game
                        // scene on portrait phones) is filled with the correct color
                        // instead of stale frame data.
                        glClear(GL_COLOR_BUFFER_BIT);
                        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                        if (fpsCounter != null) {
                                fpsCounter.increment();
                        }
                }

                /**
                 * Switches the active shader program based on the filter mode.
                 *
                 * Mode 0 (no filter): uses passthrough (or custom shaderFilter if set)
                 *   with LINEAR or NEAREST based on the filter flag.
                 * Modes 1-9: compiles a GLSL vertex+fragment shader pair from
                 *   {@link J2meFilterShaders}. Pixel-processing modes (4-9) use NEAREST
                 *   input filtering so the shader reads exact texel values; mask modes
                 *   (1-3) respect the filter flag.
                 *
                 * After creating the program, {@code loadVbo} is called to re-bind the
                 * vertex buffer and set {@code u_texelDelta}; {@code u_pixelDelta} is
                 * set from {@code mView} dimensions.
                 *
                 * @param filterMode the new filter mode (0-9)
                 */
                private void switchProgram(int filterMode) {
                        texUploaded = false; // texture needs re-allocation after program switch
                        try {
                                // Ensure the game texture is bound before changing its parameters.
                                // The binding from initTex() can be lost on some GPU drivers after
                                // ShaderProgram construction; re-bind defensively here.
                                glBindTexture(GL_TEXTURE_2D, bgTextureId[0]);
                                if (filterMode == 0) {
                                        // No filter: use custom shader filter if available, otherwise passthrough
                                        if (shaderFilter != null) {
                                                program = new ShaderProgram(shaderFilter);
                                        } else {
                                                program = passthroughProgram;
                                        }
                                        int filterParam = filter ? GL_LINEAR : GL_NEAREST;
                                        glTexParameteri(GLES20.GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filterParam);
                                        glTexParameteri(GLES20.GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filterParam);
                                } else {
                                        // GLSL shader for the selected filter mode (1-9)
                                        program = new ShaderProgram(
                                                        J2meFilterShaders.getVertexShader(filterMode),
                                                        J2meFilterShaders.getFragmentShader(filterMode));
                                        // Pixel-processing modes (XBR/HQ4x) require NEAREST input so
                                        // the GLSL shader reads exact texel values.
                                        int filterParam = J2meFilterShaders.usesNearestFiltering(filterMode)
                                                        ? GL_NEAREST
                                                        : (filter ? GL_LINEAR : GL_NEAREST);
                                        glTexParameteri(GLES20.GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filterParam);
                                        glTexParameteri(GLES20.GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filterParam);
                                }
                                // Re-apply CLAMP_TO_EDGE wrap mode defensively. Some GPU drivers
                                // reset wrap mode to REPEAT (the GL default) when texImage2D is
                                // called with a new size; combined with multi-tap shaders (XBR/
                                // HQ4x) that sample beyond the texture border, REPEAT wrap causes
                                // recognizable duplicate scene content to bleed into the area that
                                // should be solid black below the game.
                                glTexParameteri(GLES20.GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                                glTexParameteri(GLES20.GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

                                // Re-bind VBO and uniforms for the new program.
                                // loadVbo sets u_texelDelta = 1/textureWidth, 1/textureHeight.
                                glUseProgram(program.programId);
                                Bitmap bitmap = offscreenCopy.getBitmap();
                                program.loadVbo(vbo, bitmap.getWidth(), bitmap.getHeight());
                                // u_pixelDelta = 1/screenWidth, 1/screenHeight
                                if (program.uPixelDelta != -1 && mView != null) {
                                        glUniform2f(program.uPixelDelta,
                                                        1.0f / mView.getWidth(), 1.0f / mView.getHeight());
                                }
                                if (program.uTextureUnit != -1) {
                                        glUniform1i(program.uTextureUnit, 0);
                                }
                                if (shaderFilter != null && shaderFilter.values != null && program.uSetting != -1) {
                                        glUniform4fv(program.uSetting, 1, shaderFilter.values, 0);
                                }
                                lastFilterMode = filterMode;
                        } catch (Exception e) {
                                Log.w("GLRenderer", "Program switch failed for mode " + filterMode + ": " + e);
                                // Fall back to passthrough
                                program = passthroughProgram;
                                lastFilterMode = filterMode;
                        }
                }

                private void initTex() {
                        glGenTextures(1, bgTextureId, 0);
                        glActiveTexture(GL_TEXTURE0);
                        glBindTexture(GL_TEXTURE_2D, bgTextureId[0]);
                        // Pixel-processing modes (XBR/HQ4x, modes 4-9) require NEAREST input so
                        // the GLSL shader reads exact texel values. Other modes use LINEAR if
                        // the bitmap filter flag is set, otherwise NEAREST.
                        boolean useNearest = !filter || J2meFilterShaders.usesNearestFiltering(j2meFilterMode);
                        int filterParam = useNearest ? GL_NEAREST : GL_LINEAR;
                        glTexParameteri(GLES20.GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filterParam);
                        glTexParameteri(GLES20.GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filterParam);
                        glTexParameteri(GLES20.GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                        glTexParameteri(GLES20.GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

                        // texture unit
                        if (program != null && program.uTextureUnit != -1) {
                                glUniform1i(program.uTextureUnit, 0);
                        }
                }

                public void updateSize(float gl, float gt, float gr, float gb, float th, float tw) {
                        synchronized (vbo) {
                                FloatBuffer vertex_bg = vbo;
                                vertex_bg.rewind();
                                vertex_bg.put(gl).put(gt).put(0.0f).put(0.0f);// lt
                                vertex_bg.put(gl).put(gb).put(0.0f).put(  th);// lb
                                vertex_bg.put(gr).put(gt).put(  tw).put(0.0f);// rt
                                vertex_bg.put(gr).put(gb).put(  tw).put(  th);// rb
                        }
                        if (isStarted) {
                                mView.queueEvent(() -> {
                                        Bitmap bitmap = offscreenCopy.getBitmap();
                                        synchronized (vbo) {
                                                program.loadVbo(vbo, bitmap.getWidth(), bitmap.getHeight());
                                        }
                                });
                        }
                }

                public void requestRender() {
                        mView.requestRender();
                }

                public void setView(GLSurfaceView mView) {
                        this.mView = mView;
                }

                public void stop() {
                        isStarted = false;
                        mView.onPause();
                }

                public void start() {
                        mView.onResume();
                }

                private Single<Bitmap> takeScreenShot() {
                        return Single.<ByteBuffer>create(emitter -> {
                                                ByteBuffer buf = ByteBuffer.allocateDirect(onWidth * onHeight * 4).order(ByteOrder.nativeOrder());
                                                mView.requestRender();
                                                mView.queueEvent(() -> {
                                                        try {
                                                                glReadPixels(displayWidth - onWidth - onX, displayHeight - onHeight - onY, onWidth, onHeight, GL_RGBA, GL_UNSIGNED_BYTE, buf);
                                                                emitter.onSuccess(buf);
                                                        } catch (Throwable e) {
                                                                emitter.onError(e);
                                                        }
                                                });
                                        }).timeout(3, TimeUnit.SECONDS)
                                        .subscribeOn(Schedulers.computation())
                                        .observeOn(Schedulers.computation())
                                        .map(bb -> {
                                                Bitmap rawBitmap = Bitmap.createBitmap(onWidth, onHeight, Bitmap.Config.ARGB_8888);
                                                bb.rewind();
                                                rawBitmap.copyPixelsFromBuffer(bb);
                                                Matrix m = new Matrix();
                                                m.setScale(1.0f, -1.0f);
                                                return Bitmap.createBitmap(rawBitmap, 0, 0, onWidth, onHeight, m, false);
                                        });
                }
        }

        private class PaintEvent extends Event implements EventFilter {
                private int clipLeft;
                private int clipTop;
                private int clipRight;
                private int clipBottom;

                private boolean isPending;

                private int enqueued = 0;

                /**
                 * GameBox: 同步重入守卫。
                 *
                 * 非即时（队列）模式下 paint 事件排在 EventQueue 里，paint()
                 * 内再次 repaint() 只是把新区域并入同一 paint 事件的脏区并
                 * 重新入队，不会重入 process()。但即时模式
                 * （EventQueue.setImmediate(true)）下 postEvent() 在调用线程
                 * 同步执行 —— paint() 里调 repaint() 会直接重新进入 process()，
                 * 若无守卫就变成 paint→repaint→paint 无限递归，直到
                 * StackOverflowError（卡死/ANR/闪退）。此标志阻止同步重入，
                 * 重入的 repaint() 只需把区域并入脏区，交给最外层 process()
                 * 的循环一起绘制，与队列模式的"合并脏区、串行处理"语义一致。
                 */
                private boolean processing;

                @Override
                public void process() {
                        if (!visible) {
                                return;
                        }
                        synchronized (this) {
                                if (processing) {
                                        // 同步重入：新 repaint() 的区域已经并入
                                        // clip / isPending，直接返回，不递归。
                                        return;
                                }
                                processing = true;
                        }
                        try {
                                // 循环消化脏区只保留在即时模式
                                // （EventQueue.isImmediate()）下：那种模式下 repaint()
                                // 在调用线程同步执行，paint() 内再 repaint() 只会并入
                                // 脏区后返回（processing 守卫），必须由本循环把并入
                                // 的区域补绘出来。
                                // 非即时（队列）模式下 process() 每次只消费一个脏区
                                // 就返回：队列线程回到 eventLoop，后续 touch/key 事件
                                // 才能按 FIFO 得到处理。这里若用 while(true)，像植物
                                // 大战僵尸这类"渲染线程每帧都 repaint()"的游戏会让
                                // 队列线程无限停留在本方法内（clip 几乎不为空），
                                // POINTER_PRESSED / POINTER_RELEASED 永远排不上队，
                                // 表现为"触屏点一次就失效，切一次设置才恢复一次"。
                                do {
                                        int l, t, r, b;
                                        synchronized (this) {
                                                isPending = false;
                                                l = clipLeft;
                                                t = clipTop;
                                                r = clipRight;
                                                b = clipBottom;
                                                clipLeft = 0;
                                                clipTop = 0;
                                                clipRight = 0;
                                                clipBottom = 0;
                                        }
                                        if (r - l <= 0 || b - t <= 0) {
                                                return;
                                        }
                                        Graphics g = offscreen.getSingleGraphics();
                                        g.reset(l, t, r, b);
                                        try {
                                                paint(g);
                                        } catch (Throwable e) {
                                                Log.e(TAG, "Error in paint()", e);
                                        }
                                        synchronized (bufferLock) {
                                                offscreen.copyTo(offscreenCopy);
                                        }
                                        if (surface == null || !surface.isValid()) {
                                                return;
                                        }
                                        requestFlushToScreen();
                                } while (EventQueue.isImmediate());
                        } finally {
                                synchronized (this) {
                                        processing = false;
                                }
                        }
                }

                @Override
                public void recycle() {
                }

                @Override
                public void enterQueue() {
                        enqueued++;
                }

                @Override
                public void leaveQueue() {
                        enqueued--;
                }

                /**
                 * The queue should contain no more than two repaint events
                 * <p>
                 * One won't be smooth enough, and if you add more than two,
                 * then how to determine exactly how many of them need to be added?
                 */
                @Override
                public boolean placeableAfter(Event event) {
                        return event != this;
                }

                @Override
                public boolean accept(Event event) {
                        return event == this;
                }

                private boolean invalidateClip(Canvas canvas, int l, int t, int r, int b) {
                        boolean empty = clipRight - clipLeft <= 0 && clipBottom - clipTop <= 0;
                        if (empty) {
                                clipLeft = l;
                                clipTop = t;
                                clipRight = r;
                                clipBottom = b;
                        } else {
                                if (clipLeft > l) clipLeft = l;
                                if (clipTop > t) clipTop = t;
                                if (clipRight < r) clipRight = r;
                                if (clipBottom < b) clipBottom = b;
                        }
                        int w = width;
                        int h = height;

                        if (clipLeft < 0) clipLeft = 0;
                        if (clipTop < 0) clipTop = 0;
                        if (clipRight > w) clipRight = w;
                        if (clipBottom > h) clipBottom = h;

                        return empty;
                }
        }

        private class ViewCallbacks implements View.OnTouchListener, SurfaceHolder.Callback, View.OnKeyListener {
                private final View mView;
                OverlayView overlayView;

                public ViewCallbacks(View view) {
                        mView = view;
                        // getOverlayView() returns null in embedded mode; the
                        // field is only used for soft-key popup positioning.
                        overlayView = ContextHolder.getOverlayView();
                }

                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                        switch (event.getAction()) {
                                case KeyEvent.ACTION_DOWN:
                                        return onKeyDown(keyCode, event);
                                case KeyEvent.ACTION_UP:
                                        return onKeyUp(keyCode, event);
                                case KeyEvent.ACTION_MULTIPLE:
                                        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                                                String characters = event.getCharacters();
                                                for (int i = 0; i < characters.length(); i++) {
                                                        int cp = characters.codePointAt(i);
                                                        postKeyPressed(cp);
                                                        postKeyReleased(cp);
                                                }
                                                return true;
                                        } else {
                                                return onKeyDown(keyCode, event);
                                        }
                        }
                        return false;
                }

                public boolean onKeyDown(int keyCode, KeyEvent event) {
                        keyCode = KeyMapper.convertAndroidKeyCode(keyCode, event);
                        if (keyCode == 0) {
                                return false;
                        }
                        if (event.getRepeatCount() == 0) {
                                if (overlay == null || !overlay.keyPressed(keyCode)) {
                                        postKeyPressed(keyCode);
                                }
                        } else {
                                if (overlay == null || !overlay.keyRepeated(keyCode)) {
                                        postKeyRepeated(keyCode);
                                }
                        }
                        return true;
                }

                public boolean onKeyUp(int keyCode, KeyEvent event) {
                        int midpKeyCode = KeyMapper.convertAndroidKeyCode(keyCode, event);
                        if (midpKeyCode == 0) {
                                return false;
                        }
                        if (overlay == null || !overlay.keyReleased(midpKeyCode)) {
                                postKeyReleased(midpKeyCode);
                        }
                        return true;
                }

                @Override
                @SuppressLint("ClickableViewAccessibility")
                public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getActionMasked()) {
                                case MotionEvent.ACTION_DOWN:
                                        if (overlay != null) {
                                                overlay.show();
                                        }
                                case MotionEvent.ACTION_POINTER_DOWN:
                                        int index = event.getActionIndex();
                                        int id = event.getPointerId(index);
                                        float x = event.getX(index);
                                        float y = event.getY(index);
                                        if (overlay != null) {
                                                overlay.pointerPressed(id, x, y);
                                        }
                                        if (touchInput && virtualScreen.contains(x, y)) {
                                                int cX = Math.round(convertPointerX(x));
                                                int cY = Math.round(convertPointerY(y));
                                                if (id < 20) {
                                                        lastPointerPos[id][0] = cX;
                                                        lastPointerPos[id][1] = cY;
                                                }
                                                Display.postEvent(CanvasEvent.getInstance(Canvas.this,
                                                                CanvasEvent.POINTER_PRESSED,
                                                                id,
                                                                cX,
                                                                cY));
                                        }
                                        break;
                                case MotionEvent.ACTION_MOVE:
                                        int pointerCount = event.getPointerCount();
                                        int historySize = event.getHistorySize();
                                        for (int h = 0; h < historySize; h++) {
                                                for (int p = 0; p < pointerCount; p++) {
                                                        id = event.getPointerId(p);
                                                        x = event.getHistoricalX(p, h);
                                                        y = event.getHistoricalY(p, h);
                                                        if (overlay != null) {
                                                                overlay.pointerDragged(id, x, y);
                                                        }
                                                        if (touchInput && virtualScreen.contains(x, y)) {
                                                                int cX = Math.round(convertPointerX(x));
                                                                int cY = Math.round(convertPointerY(y));
                                                                if (id < 20) {
                                                                        int oX = lastPointerPos[id][0];
                                                                        int oY = lastPointerPos[id][1];
                                                                        if (oX == cX && oY == cY) {
                                                                                continue;
                                                                        }
                                                                        lastPointerPos[id][0] = cX;
                                                                        lastPointerPos[id][1] = cY;
                                                                }
                                                                Display.postEvent(CanvasEvent.getInstance(Canvas.this,
                                                                                CanvasEvent.POINTER_DRAGGED,
                                                                                id,
                                                                                cX,
                                                                                cY));
                                                        }
                                                }
                                        }
                                        for (int p = 0; p < pointerCount; p++) {
                                                id = event.getPointerId(p);
                                                x = event.getX(p);
                                                y = event.getY(p);
                                                if (overlay != null) {
                                                        overlay.pointerDragged(id, x, y);
                                                }
                                                if (touchInput && virtualScreen.contains(x, y)) {
                                                        int cX = Math.round(convertPointerX(x));
                                                        int cY = Math.round(convertPointerY(y));
                                                        if (id < 20) {
                                                                int oX = lastPointerPos[id][0];
                                                                int oY = lastPointerPos[id][1];
                                                                if (oX == cX && oY == cY) {
                                                                        continue;
                                                                }
                                                                lastPointerPos[id][0] = cX;
                                                                lastPointerPos[id][1] = cY;
                                                        }
                                                        Display.postEvent(CanvasEvent.getInstance(Canvas.this,
                                                                        CanvasEvent.POINTER_DRAGGED,
                                                                        id,
                                                                        cX,
                                                                        cY));
                                                }
                                        }
                                        break;
                                case MotionEvent.ACTION_UP:
                                        if (overlay != null) {
                                                overlay.hide();
                                        }
                                case MotionEvent.ACTION_POINTER_UP:
                                        index = event.getActionIndex();
                                        id = event.getPointerId(index);
                                        x = event.getX(index);
                                        y = event.getY(index);
                                        if (overlay != null) {
                                                overlay.pointerReleased(id, x, y);
                                        }
                                        if (touchInput && virtualScreen.contains(x, y)) {
                                                int cX = Math.round(convertPointerX(x));
                                                int cY = Math.round(convertPointerY(y));
                                                lastPointerPos[id][0] = cX;
                                                lastPointerPos[id][1] = cY;
                                                Display.postEvent(CanvasEvent.getInstance(Canvas.this,
                                                                CanvasEvent.POINTER_RELEASED,
                                                                id,
                                                                cX,
                                                                cY));
                                        }
                                        break;
                                case MotionEvent.ACTION_CANCEL:
                                        if (overlay != null) {
                                                overlay.cancel();
                                        }
                                        break;
                                default:
                                        return false;
                        }
                        return true;
                }

                @Override
                public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int newWidth, int newHeight) {
                        if (displayWidth > displayHeight) {
                                if (newWidth < newHeight) {
                                        softBar.closeMenu();
                                }
                        } else if (newWidth > newHeight) {
                                softBar.closeMenu();
                        }
                        displayWidth = newWidth;
                        displayHeight = newHeight;
                        if (checkSizeChanged() || !sizeChangedCalled) {
                                Display.postEvent(CanvasEvent.getInstance(Canvas.this,
                                                CanvasEvent.SIZE_CHANGED,
                                                width,
                                                height));
                                repaintInternal();
                                sizeChangedCalled = true;
                        }
                }

                @Override
                public void surfaceCreated(@NonNull SurfaceHolder holder) {
                        if (renderer != null) {
                                renderer.start();
                        }
                        surface = holder.getSurface();
                        Display.postEvent(CanvasEvent.getInstance(Canvas.this, CanvasEvent.SHOW_NOTIFY));
                        repaintInternal();
                        // 嵌入式模式(J2meEngine 宿主)下没有 OverlayView, 此处为 null。
                        // 只跳过覆盖层 UI 管理, 游戏本身照常运行(软键由虚拟手柄发送)。
                        if (overlayView != null) {
                                if (showFps) {
                                        fpsCounter = new FpsCounter(overlayView);
                                        overlayView.addLayer(fpsCounter);
                                }
                                overlayView.addLayer(softBar, 0);
                                overlayView.setVisibility(true);
                        }
                        overlay = ContextHolder.getVk();
                        if (overlay != null) {
                                overlay.setTarget(Canvas.this);
                        }
                }

                @Override
                public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                        if (renderer != null) {
                                renderer.stop();
                        }
                        synchronized (surfaceLock) {
                                surface = null;
                        }
                        Display.postEvent(CanvasEvent.getInstance(Canvas.this, CanvasEvent.HIDE_NOTIFY));
                        if (fpsCounter != null) {
                                fpsCounter.stop();
                                if (overlayView != null) {
                                        overlayView.removeLayer(fpsCounter);
                                }
                                fpsCounter = null;
                        }
                        softBar.closeMenu();
                        if (overlayView != null) {
                                overlayView.removeLayer(softBar);
                                overlayView.setVisibility(false);
                        }
                        if (overlay != null) {
                                overlay.setTarget(null);
                                overlay.cancel();
                                overlay = null;
                        }
                }

        }

        private void requestFlushToScreen() {
                if (graphicsMode == 1) {
                        // GL mode: only request a render if the GLRenderer was actually
                        // created (i.e. the Canvas was constructed with graphicsMode==1).
                        // If graphicsMode was switched to 1 after construction (e.g. via
                        // setJ2meFilterMode in older code), renderer is null and we must
                        // fall through to the CPU path to avoid a NullPointerException.
                        if (renderer != null && innerView != null) {
                                renderer.requestRender();
                        } else {
                                // Defensive fallback: use the CPU path (repaintScreen)
                                // which handles all filter modes via J2meBitmapFilter.
                                repaintScreen();
                        }
                } else if (graphicsMode == 2) {
                        if (innerView != null) {
                                innerView.postInvalidate();
                        }
                } else if (!parallelRedraw) {
                        repaintScreen();
                } else if (!uiHandler.hasMessages(0)) {
                        uiHandler.sendEmptyMessage(0);
                }
        }

        private class SoftBar extends AbstractSoftKeysBar implements Layer {
                private final OverlayView overlayView;
                private final float padding;
                private final int textColor;
                private final int bgColor;
                private final RectF bounds = new RectF();

                private String leftLabel;
                private String rightLabel;
                private float textScale = 1.0f;

                private SoftBar() {
                        super(Canvas.this, false);
                        // getContext() falls back to the Application context in
                        // embedded mode; getOverlayView() returns null there.
                        Context activity = ContextHolder.getContext();
                        this.overlayView = ContextHolder.getOverlayView();
                        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
                        padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5, metrics);
                        // GameBox: 软键条改用与其他核心游戏内菜单一致的深蓝底 +
                        // 琥珀色文字。原配色是白底(#fafafa) + 红字, 在深色游戏
                        // 画面上格外刺眼。
                        textColor = 0xFFFFD66B;
                        bgColor = 0xDD1E2A3A;
                        notifyChanged();
                }

                private void showPopup() {
                        // 嵌入式模式下 overlayView 为 null, 软键菜单由宿主 UI(虚拟手柄)接管
                        if (overlayView == null) {
                                return;
                        }
                        PopupWindow popup = prepareMenu(fullscreen ? 0 : 1);
                        popup.setWidth(Math.min(displayWidth, displayHeight) / 2);
                        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
                        int x = (int) (displayWidth - bounds.right);
                        int y = (int) (displayHeight - bounds.top);
                        popup.showAtLocation(overlayView, Gravity.RIGHT | Gravity.BOTTOM, x, y);
                }

                @Override
                protected void onCommandsChanged() {
                        super.onCommandsChanged();
                        // 闪退根因修复: 嵌入式模式(J2meEngine 宿主, 非 MicroActivity)下
                        // ContextHolder.getOverlayView() 返回 null, 而 SoftBar 构造函数
                        // 末尾就会调用 notifyChanged(), 导致主线程在此解引用 null 崩溃
                        // (NullPointerException: View.postInvalidate() on a null object
                        // reference)。无 OverlayView 时跳过软键条 UI 更新即可, 命令本身
                        // 仍通过 CommandListener 正常派发。
                        if (overlayView == null) {
                                return;
                        }
                        if (!fullscreen) {
                                int size = commands.size();
                                switch (size) {
                                        case 0:
                                                break;
                                        case 1:
                                                leftLabel = commands.get(0).getAndroidLabel();
                                                rightLabel = null;
                                                break;
                                        case 2:
                                                leftLabel = commands.get(1).getAndroidLabel();
                                                rightLabel = commands.get(0).getAndroidLabel();
                                                break;
                                        default:
                                                leftLabel = overlayView.getResources().getString(R.string.cmd_menu);
                                                rightLabel = commands.get(0).getAndroidLabel();
                                }
                        }
                        overlayView.postInvalidate();
                }

                private boolean fireLeftSoft() {
                        int size = commands.size();
                        if (size == 0) {
                                return false;
                        }
                        if (fullscreen) {
                                if (listener != null) {
                                        showPopup();
                                        return true;
                                }
                                return false;
                        }
                        if (size > 2) {
                                showPopup();
                                return true;
                        }
                        if (listener != null) {
                                fireCommandAction(commands.get(size > 1 ? 1 : 0));
                        }
                        return true;
                }

                private boolean fireRightSoft() {
                        int size = commands.size();
                        if (size == 0) {
                                return false;
                        }
                        if (fullscreen) {
                                if (size == 1) {
                                        return false;
                                }
                                if (listener != null) {
                                        showPopup();
                                        return true;
                                }
                                return false;
                        }
                        if (listener != null) {
                                fireCommandAction(commands.get(0));
                        }
                        return true;
                }

                @Override
                public void paint(CanvasWrapper g) {
                        if (bounds.isEmpty() || commands.size() == 0) {
                                return;
                        }
                        g.setFillColor(bgColor);
                        g.fillRect(bounds);

                        if (leftLabel == null) {
                                return;
                        }
                        g.setTextAlign(Paint.Align.LEFT);
                        g.setTextScale(textScale);
                        g.setTextColor(textColor);
                        float y = bounds.centerY();
                        g.drawString(leftLabel, bounds.left + padding * textScale, y);
                        if (rightLabel != null) {
                                g.setTextAlign(Paint.Align.RIGHT);
                                g.drawString(rightLabel, bounds.right - padding * textScale, y);
                        }

                        g.setTextAlign(Paint.Align.CENTER);
                        g.setTextScale(1.0f);
                }

                public void resize() {
                        float left;
                        float right;
                        float bottom;
                        VirtualKeyboard vk = ContextHolder.getVk();
                        if (vk != null && vk.isPhone()) {
                                float vkTop = displayHeight - vk.getPhoneKeyboardHeight(displayWidth, displayHeight) - 1;
                                if (onWidth < displayWidth / 2.0f || onWidth > displayWidth) {
                                        textScale = 1.0f;
                                        left = 0;
                                        right = displayWidth;
                                        bottom = vkTop;
                                } else {
                                        textScale = (float) onWidth / displayWidth;
                                        canvasWrapper.setTextScale(textScale);
                                        left = onX;
                                        right = onX + onWidth;
                                        bottom = onY + onHeight;
                                        if (bottom > vkTop) {
                                                bottom = vkTop;
                                        }
                                }
                        } else {
                                float width = onWidth;
                                float minSide = Math.min(displayWidth, displayHeight);
                                if (width <= minSide) {
                                        textScale = width / minSide;
                                        canvasWrapper.setTextScale(textScale);
                                        left = onX;
                                } else {
                                        left = (onX + onWidth) / 2.0f - minSide / 2.0f;
                                        if (left + minSide > displayWidth) {
                                                left = displayWidth / 2.0f - minSide / 2.0f;
                                        }
                                        width = minSide;
                                }
                                bottom = onY + onHeight;
                                right = left + width;
                                if (bottom > displayHeight) {
                                        bottom = displayHeight;
                                }
                        }
                        float top = fullscreen ? bottom : bottom - canvasWrapper.getTextHeight();
                        bounds.set(left, top, right, bottom);
                        canvasWrapper.setTextScale(1.0f);
                }
        }
}
