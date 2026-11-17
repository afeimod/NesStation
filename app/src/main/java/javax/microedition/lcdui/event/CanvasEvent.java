/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017 Nikita Shakarun
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
package javax.microedition.lcdui.event;

import android.util.Log;

import javax.microedition.lcdui.Canvas;
import javax.microedition.util.ArrayStack;

public class CanvasEvent extends Event {
	private static final String TAG = CanvasEvent.class.getName();

	private static final ArrayStack<CanvasEvent> recycled = new ArrayStack<>();

	public static final int KEY_PRESSED = 0;
	public static final int KEY_REPEATED = 1;
	public static final int KEY_RELEASED = 2;
	public static final int POINTER_PRESSED = 3;
	public static final int POINTER_DRAGGED = 4;
	public static final int POINTER_RELEASED = 5;
	public static final int SHOW_NOTIFY = 6;
	public static final int HIDE_NOTIFY = 7;
	public static final int SIZE_CHANGED = 8;

	private static final int[] enqueued = new int[9];

	private Canvas canvas;
	private int eventType;

	private int keyCode;

	private int pointer;
	private float x, y;

	private int width;
	private int height;

	public static Event getInstance(Canvas canvas, int eventType) {
		return obtain(canvas, eventType);
	}

	public static Event getInstance(Canvas canvas, int eventType, int keyCode) {
		CanvasEvent instance = obtain(canvas, eventType);
		instance.keyCode = keyCode;
		return instance;
	}

	public static Event getInstance(Canvas canvas, int eventType, int pointer, float x, float y) {
		CanvasEvent instance = obtain(canvas, eventType);
		instance.pointer = pointer;
		instance.x = x;
		instance.y = y;
		return instance;
	}

	public static Event getInstance(Canvas canvas, int eventType, int width, int height) {
		CanvasEvent instance = obtain(canvas, eventType);
		instance.width = width;
		instance.height = height;
		return instance;
	}

	private static CanvasEvent obtain(Canvas canvas, int eventType) {
		CanvasEvent instance = recycled.pop();
		if (instance == null) {
			instance = new CanvasEvent();
		}
		instance.canvas = canvas;
		instance.eventType = eventType;
		return instance;
	}

	/** GameBox: 事件类型（KEY_PRESSED … SIZE_CHANGED），供 EventQueue 判定输入事件。 */
	public int getEventType() {
		return eventType;
	}

	@Override
	public void process() {
		switch (eventType) {
			case KEY_PRESSED:
				try {
					canvas.doKeyPressed(keyCode);
				} catch (Exception e) {
					Log.e(TAG, "keyPressed: ", e);
				}
				break;

			case KEY_REPEATED:
				try {
					canvas.doKeyRepeated(keyCode);
				} catch (Exception e) {
					Log.e(TAG, "keyRepeated: ", e);
				}
				break;

			case KEY_RELEASED:
				try {
					canvas.doKeyReleased(keyCode);
				} catch (Exception e) {
					Log.e(TAG, "keyReleased: ", e);
				}
				break;

			case POINTER_PRESSED:
				try {
					Log.d(TAG, "[CanvasEvent] dispatch POINTER_PRESSED pid=" + pointer
							+ " x=" + x + " y=" + y);
					canvas.pointerPressed(pointer, x, y);
				} catch (Exception e) {
					Log.e(TAG, "pointerPressed: ", e);
				}
				break;

			case POINTER_DRAGGED:
				try {
					Log.d(TAG, "[CanvasEvent] dispatch POINTER_DRAGGED pid=" + pointer
							+ " x=" + x + " y=" + y);
					canvas.pointerDragged(pointer, x, y);
				} catch (Exception e) {
					Log.e(TAG, "pointerDragged: ", e);
				}
				break;

			case POINTER_RELEASED:
				try {
					Log.d(TAG, "[CanvasEvent] dispatch POINTER_RELEASED pid=" + pointer
							+ " x=" + x + " y=" + y);
					canvas.pointerReleased(pointer, x, y);
				} catch (Exception e) {
					Log.e(TAG, "pointerReleased: ", e);
				}
				break;

			case SHOW_NOTIFY:
				try {
					canvas.doShowNotify();
				} catch (Exception e) {
					Log.e(TAG, "showNotify: ", e);
				}
				break;

			case HIDE_NOTIFY:
				try {
					canvas.doHideNotify();
				} catch (Exception e) {
					Log.e(TAG, "hideNotify: ", e);
				}
				break;

			case SIZE_CHANGED:
				try {
					canvas.doSizeChanged(width, height);
				} catch (Exception e) {
					Log.e(TAG, "sizeChanged: ", e);
				}
				break;
		}
	}

	@Override
	public void recycle() {
		canvas = null;
		recycled.push(this);
	}

	@Override
	public void enterQueue() {
		synchronized (enqueued) {
			if (eventType >= 0 && eventType < enqueued.length) {
				enqueued[eventType]++;
			}
		}
	}

	@Override
	public void leaveQueue() {
		synchronized (enqueued) {
			if (eventType >= 0 && eventType < enqueued.length && enqueued[eventType] > 0) {
				enqueued[eventType]--;
			}
		}
	}

	/**
	 * GameBox: 清零排队计数。
	 * <p>
	 * 事件队列线程死亡时，队列里尚未处理的事件不会调用 leaveQueue()，
	 * 它们的计数会永远留在 enqueued[] 里。enqueued[POINTER_DRAGGED] 一旦
	 * 卡在 >= 2，placeableAfter() 会永久返回 false —— 之后每一个
	 * POINTER_DRAGGED（以及 KEY_REPEATED）事件都会被静默丢弃，即使队列
	 * 线程已经重建也恢复不了。线程重建时调用本方法复位。
	 */
	static void resetEnqueued() {
		synchronized (enqueued) {
			for (int i = 0; i < enqueued.length; i++) {
				enqueued[i] = 0;
			}
		}
	}

	@Override
	public boolean placeableAfter(Event event) {
		if (event instanceof CanvasEvent) {
			switch (eventType) {
				case KEY_REPEATED:
				case POINTER_DRAGGED:
					synchronized (enqueued) {
						return enqueued[eventType] < 2;
					}
			}
		}
		return true;
	}
}
