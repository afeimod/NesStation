/*
 * Copyright 2024 Yury Kharchenko
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

import static javax.microedition.lcdui.event.CanvasEvent.POINTER_DRAGGED;
import static javax.microedition.lcdui.event.CanvasEvent.POINTER_PRESSED;
import static javax.microedition.lcdui.event.CanvasEvent.POINTER_RELEASED;

import android.graphics.Point;
import android.util.SparseArray;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;

public class PointerEvent {
	private static final SparseArray<Point> POINTERS = new SparseArray<>();

	private PointerEvent() {}

	public static void sendPressed(Canvas canvas, int pointer, int x, int y) {
		Display.postEvent(CanvasEvent.getInstance(canvas, POINTER_PRESSED, pointer, x, y));
		getPoint(pointer).set(x, y);
	}

	public static void sendDragged(Canvas canvas, int pointer, int x, int y) {
		Point point = getPoint(pointer);
		if (point.equals(x, y)) {
			return;
		}
		int width = canvas.getWidth();
		int height = canvas.getHeight();
		boolean inArea = x >= 0 && x < width && y >= 0 && y < height;
		if (point.x >= 0 && point.x < width && point.y >= 0 && point.y < height) {
			if (inArea) {
				Display.postEvent(CanvasEvent.getInstance(canvas, POINTER_DRAGGED, pointer, x, y));
				point.x = x;
				point.y = y;
			} else {
				if (x < 0) {
					x = 0;
				} else if (x >= width) {
					x = width - 1;
				}
				if (y < 0) {
					y = 0;
				} else if (y >= height) {
					y = height - 1;
				}
				sendReleased(canvas, pointer, x, y);
			}
		} else if (inArea) {
			sendPressed(canvas, pointer, x, y);
		}
	}

	public static void sendReleased(Canvas canvas, int pointer, int x, int y) {
		Point point = getPoint(pointer);
		if (point.equals(-1, -1)) {
			// already released
			return;
		}
		Display.postEvent(CanvasEvent.getInstance(canvas, POINTER_RELEASED, pointer, x, y));
		point.set(-1, -1);
	}

	private static Point getPoint(int pointer) {
		Point point = POINTERS.get(pointer);
		if (point == null) {
			point = new Point(-1, -1);
			POINTERS.put(pointer, point);
		}
		return point;
	}
}
