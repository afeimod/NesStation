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

import javax.microedition.util.ArrayStack;

public class RunnableEvent extends Event {
	private static final ArrayStack<RunnableEvent> recycled = new ArrayStack<>();
	private static int queued;

	private Runnable runnable;

	/**
	 * GameBox: 重新启用即时模式时清零排队计数。
	 * 该计数现在只做诊断用途（见 enterQueue / leaveQueue），不再驱动任何
	 * 自动降级行为 —— 旧版本在 queued > 50 时会静默调用
	 * EventQueue.setImmediate(false)，把用户刚打开的即时模式立刻关掉，
	 * 这正是"改一次即时绘制模式触屏只多生效一次就又失效"的直接原因。
	 */
	static void resetQueued() {
		synchronized (recycled) {
			queued = 0;
		}
	}

	public static Event getInstance(Runnable runnable) {
		RunnableEvent instance = recycled.pop();

		if (instance == null) {
			instance = new RunnableEvent();
		}

		instance.runnable = runnable;

		return instance;
	}

	@Override
	public void process() {
		runnable.run();
	}

	@Override
	public void recycle() {
		runnable = null;
		recycled.push(this);
	}

	@Override
	public void enterQueue() {
		synchronized (recycled) {
			queued++;
		}
	}

	@Override
	public void leaveQueue() {
		synchronized (recycled) {
			if (queued > 0) {
				queued--;
			}
		}
	}

	@Override
	public boolean placeableAfter(Event event) {
		return true;
	}
}