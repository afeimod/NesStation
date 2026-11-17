/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2018 Nikita Shakarun
 * Copyright 2023-2024 Arman Jussupgaliyev
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

import androidx.appcompat.app.AlertDialog;

import javax.microedition.lcdui.event.Event;
import javax.microedition.lcdui.event.EventQueue;
import javax.microedition.lcdui.event.RunnableEvent;
import javax.microedition.midlet.MIDlet;
import javax.microedition.shell.J2meHost;
import javax.microedition.util.ContextHolder;

import ru.woesss.j2me.jar.Descriptor;

@SuppressWarnings("unused")
public class Display {
	public static final int LIST_ELEMENT = 1;
	public static final int CHOICE_GROUP_ELEMENT = 2;
	public static final int ALERT = 3;

	public static final int COLOR_BACKGROUND = 0;
	public static final int COLOR_FOREGROUND = 1;
	public static final int COLOR_HIGHLIGHTED_BACKGROUND = 2;
	public static final int COLOR_HIGHLIGHTED_FOREGROUND = 3;
	public static final int COLOR_BORDER = 4;
	public static final int COLOR_HIGHLIGHTED_BORDER = 5;

	private static final int[] COLORS =
			{
					0xFFD0D0D0,
					0xFF000080,
					0xFF000080,
					0xFFFFFFFF,
					0xFFFFFFFF,
					0xFF000080
			};

	private static Display instance;
	static EventQueue queue = new EventQueue();
	private static boolean multiTouchSupported;
	/** GameBox: 宿主已强制指定 multiTouchSupported 时，JAD 推断不再覆写。 */
	private static boolean multiTouchForced;
	private static String pointerNumber;

	static {
		queue.startProcessing();
	}

	private Displayable current;

	public static Display getDisplay(MIDlet midlet) {
		if (instance == null && midlet != null) {
			// GameBox: 宿主（J2meEngine）已强制声明多点触控时不回读
			// JAD —— 否则 MIDlet 初始化晚于强制调用，会把强制值覆写。
			if (!multiTouchForced) {
				String nokiaUiEnhancement = midlet.getAppProperty(Descriptor.NOKIA_UI_ENHANCEMENT);
				if (nokiaUiEnhancement != null) {
					multiTouchSupported = nokiaUiEnhancement.contains("EnableMultiPointTouchEvents");
				}
			}
			instance = new Display();
		}
		return instance;
	}

	private Display() {
	}

	public static void initDisplay() {
		instance = null;
	}

	public static void postEvent(Event event) {
		queue.postEvent(event);
	}

	static EventQueue getEventQueue() {
		return queue;
	}

	public static boolean isMultiTouchSupported() {
		return multiTouchSupported;
	}

	/**
	 * GameBox: 嵌入式宿主强制声明设备支持多点触控。
	 *
	 * multiTouchSupported 原本只从游戏 JAD 的 NOKIA_UI_ENHANCEMENT 属性
	 * ("EnableMultiPointTouchEvents") 推断 —— 绝大多数游戏没有该属性，
	 * 落回 false 后 Canvas.pointerPressed/Dragged/Released(pointer,x,y)
	 * 只派发 pointer==0 的事件。而 GameBox 的虚拟手柄覆盖层总是处于
	 * 多点触控流中：玩家按住虚拟按键（手指 id=0）再用第二根手指点击
	 * 游戏画面（id=1）时，注入事件全部被 pointer==0 门控静默丢弃，
	 * 表现为"触屏失效"。NDS 触摸走 setTouchInputDirect 直接注入、
	 * 不依赖 pointerId，因此不受影响。现代设备均为多点触控屏，嵌入式
	 * 模式下由宿主统一置 true，让任意 pointerId 的触摸都按真机多点
	 * 语义（附带 pointer number）派发。
	 *
	 * 注意：getDisplay(MIDlet) 在 MIDlet 初始化时也会按 JAD 推断该标志，
	 * 可能晚于宿主的强制调用 —— multiTouchForced 保证强制值优先，不被
	 * JAD 推断覆写。
	 *
	 * @param supported true = 任意 pointerId 都派发（附 pointer number）
	 */
	public static void setMultiTouchSupported(boolean supported) {
		multiTouchSupported = supported;
		multiTouchForced = true;
	}

	static void setPointerNumber(int pointerNumber) {
		Display.pointerNumber = String.valueOf(pointerNumber);
	}

	static void resetPointerNumber() {
		pointerNumber = null;
	}

	public static String getPointerNumber() {
		return pointerNumber;
	}

	public void setCurrent(Displayable disp) {
		if (disp == current) {
			return;
		}
		if (current instanceof Canvas) {
			Canvas c = (Canvas) current;
			c.setInvisible();
		}
		if (disp instanceof Alert) {
			Alert alert = (Alert) disp;
			alert.setReturnScreen(current);
			showAlert(alert);
		}
		current = disp;
		showCurrent();
	}

	public void setCurrent(final Alert alert, Displayable disp) {
		if (disp == null) {
			throw new NullPointerException();
		}
		alert.setReturnScreen(disp);
		showAlert(alert);
		current = alert;
		showCurrent();
	}

	private void showAlert(Alert alert) {
		ViewHandler.postEvent(() -> {
			AlertDialog alertDialog = alert.prepareDialog();
			alertDialog.show();
			if (alert.finiteTimeout()) {
				ViewHandler.postDelayed(alert::dismiss, alert.getTimeout());
			}
		});
	}

	private void showCurrent() {
		// setCurrent is a J2meHost method, so any host (standalone
		// MicroActivity or an embedded non-Activity host) can receive it.
		J2meHost host = ContextHolder.getHost();
		if (host != null) {
			host.setCurrent(current);
		}
	}

	public Displayable getCurrent() {
		return current;
	}

	public void callSerially(Runnable r) {
		postEvent(RunnableEvent.getInstance(r));
	}

	public boolean flashBacklight(int duration) {
		return false;
	}

	/**
	 * @since MIDP 2.0
	 */
	public boolean vibrate(int duration) {
		return ContextHolder.vibrate(duration);
	}

	public void setCurrentItem(Item item) {
		if (item.hasOwnerForm()) {
			setCurrent(item.getOwnerForm());
		}
	}

	public int numAlphaLevels() {
		return 256;
	}

	public int numColors() {
		return Integer.MAX_VALUE;
	}

	public int getBestImageHeight(int imageType) {
		return 0;
	}

	public int getBestImageWidth(int imageType) {
		return 0;
	}

	public int getBorderStyle(boolean highlighted) {
		return highlighted ? Graphics.SOLID : Graphics.DOTTED;
	}

	public int getColor(int colorSpecifier) {
		return COLORS[colorSpecifier];
	}

	public boolean isColor() {
		return true;
	}
}
