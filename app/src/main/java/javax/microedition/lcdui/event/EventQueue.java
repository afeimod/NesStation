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

import javax.microedition.util.LinkedEntry;
import javax.microedition.util.LinkedList;

/**
 * The event queue. A really complicated thing.
 */
public class EventQueue implements Runnable {
	private static final String TAG = "EventQueue";
	private static boolean immediate;

	private final LinkedList<Event> queue = new LinkedList<>();
	private final Object waiter = new Object();
	// Thread lifecycle only. Never held across the event loop — the old
	// `interlock` monitor was held for the whole lifetime of run(), so any
	// synchronized(interlock) from another thread deadlocked as soon as the
	// loop was busy or stuck inside an event callback.
	private final Object threadLock = new Object();
	private final Object callbackLock = new Object();

	private volatile boolean enabled;
	private volatile Thread thread;
	private volatile boolean running;
	private boolean continuerun;

	/**
	 * Enable immediate processing mode.
	 * <p>
	 * In this mode event are processed as soon as they arrive,
	 * without queue (violates serialization principle).
	 * <p>
	 * You can try to turn on this mode if every frame counts,
	 * but the midlet behavior will be unpredictable.
	 *
	 * @param value true if the immediate processing mode shoud be enabled
	 */
	public static void setImmediate(boolean value) {
		immediate = value;
		// GameBox: 切换即时模式时把两个"排队中"诊断计数一并复位（双向）。
		// 残留的 enqueued[POINTER_DRAGGED] = 2 会让 placeableAfter() 对之后
		// 每一个 POINTER_DRAGGED / KEY_REPEATED 都返回 false —— 事件被静默
		// 丢弃，表现为"触屏点一次就失效，切一次即时模式才恢复"。切换是
		// 玩家最常见的"手动恢复"手段，必须让每次切换都从干净状态开始。
		RunnableEvent.resetQueued();
		CanvasEvent.resetEnqueued();
	}

	public static boolean isImmediate() {
		return immediate;
	}

	/**
	 * Add event to the queue.
	 * <p>
	 * If the immediate processing mode is enabled,
	 * the event is processed here,
	 * in this case there is no queue at all
	 * <p>
	 * If an event has been added to the queue,
	 * its enterQueue() method is called.
	 *
	 * @param event the added event
	 */
	public void postEvent(Event event) {

		if (immediate) { // the immediate processing mode is enabled
			event.enterQueue();
			synchronized (callbackLock) {
				event.run(); // process event on the spot
			}
			return;      // and nothing to do here
		}

		// GameBox: 队列线程自愈。该线程是非即时模式下 touch / key / paint 的
		// 唯一消费者；一旦它退出（Error 逃逸、OOM 等），后续所有事件会永远
		// 堆积在队列里不被派发 —— 表现为"Java 游戏触屏点一次就失效，且无法
		// 恢复"。投递前先确认线程存活，死了就重建。
		ensureThreadAlive();

		boolean empty;

		synchronized (queue) {   // all operations with the queue must be synchronized (on itself)
			empty = queue.isEmpty();

			if (empty) {
				/*
				 * If the queue itself is empty, then this already implies that either
				 * exactly one event remains and it is now being processed,
				 * or there is not a single event left at all.
				 *
				 * In both cases, a new event should be added to the queue,
				 * regardless of event.placeableAfter() value.
				 */
				queue.addLast(event);
				event.enterQueue();
			} else if (isInputEvent(event) && insertAheadOfPaint(event)) {
				// GameBox: 输入事件（按键 / 触摸）优先于排队中的绘制事件。
				//
				// 非即时模式下绘制事件与输入事件共用同一个队列线程，而绘制
				// 事件是持续高频的（游戏每帧 repaint）。若输入事件按 FIFO
				// 排在绘制事件后面，会被不断产生的重绘积压拖住，触摸表现为
				// "点了没反应 / 延迟严重"。这里把输入事件插到最后一个输入
				// 事件之后、绘制事件之前 —— 输入事件之间保持 FIFO，触摸/
				// 按键不会被重绘阻塞。事件仍由同一个队列线程串行处理，
				// 不破坏 J2ME 事件串行化。
				event.enterQueue();
			} else if (event.placeableAfter(queue.getLast())) {
				queue.addLast(event);
				event.enterQueue();
			} else {
				// it is more correct, but additional checks are required
				// queue.setLast(event).recycle(); // remove the previous event and add the new one.
				// GameBox 诊断日志：placeableAfter() 拒绝入队会被静默回收，
				// 这正是"触摸点一次就失效"最可疑的环节之一 —— 只限流
				// POINTER_DRAGGED / KEY_REPEATED，但日志能确认是否真的发生。
				Log.w(TAG, "postEvent recycled " + event.getClass().getSimpleName()
						+ " (placeableAfter=false) tail=" + queue.getLast().getClass().getSimpleName());
				event.recycle(); // more reliable // leave the previous event, recycle the new one.
			}
		}

		if (empty) {
			/*
			 * on the other hand, if the queue was non-empty,
			 * there is at least one more iteration for the events,
			 * and this is not necessary
			 */

			synchronized (waiter) {
				if (running) {
					continuerun = true;
				} else {
					waiter.notifyAll();
				}
			}
		}
	}

	/**
	 * GameBox: 判断事件是否属于"输入事件"（按键 / 触摸）。
	 * <p>
	 * 输入事件在队列里应获得比绘制事件更高的优先级；其它事件
	 * （SHOW_NOTIFY / SIZE_CHANGED / 绘制 / Runnable 等）保持原 FIFO。
	 */
	private static boolean isInputEvent(Event event) {
		if (!(event instanceof CanvasEvent)) {
			return false;
		}
		int type = ((CanvasEvent) event).getEventType();
		return type >= CanvasEvent.KEY_PRESSED && type <= CanvasEvent.POINTER_RELEASED;
	}

	/**
	 * GameBox: 把输入事件插入到"最后一个输入事件之后、绘制事件之前"。
	 * <p>
	 * 调用方必须已持有 queue 监视器。返回 false 表示该事件受
	 * placeableAfter() 限流（POINTER_DRAGGED / KEY_REPEATED 排队数已达上限），
	 * 调用方应回退到普通尾部入队逻辑（同样会被 placeableAfter 拒绝并回收）。
	 */
	private boolean insertAheadOfPaint(Event event) {
		LinkedEntry<Event> entry = queue.lastEntry();
		LinkedEntry<Event> lastInput = null;
		while (entry != null) {
			if (isInputEvent(entry.getElement())) {
				lastInput = entry;
				break;
			}
			entry = entry.prevEntry();
		}
		Event prev = (lastInput != null) ? lastInput.getElement() : null;
		if (prev == null || event.placeableAfter(prev)) {
			LinkedEntry<Event> next = (lastInput != null) ? lastInput.nextEntry() : queue.firstEntry();
			queue.getEntryInstance(event).insertBefore(next);
			return true;
		}
		return false;
	}

	/**
	 * GameBox: 事件队列线程自愈。
	 * <p>
	 * 该线程是全进程唯一的事件消费者：非即时模式下触屏(pointerPressed)、
	 * 按键、重绘全部依赖它。线程一旦退出，所有后续事件会永远堆积在队列里
	 * 不被派发 —— 表现为"Java 游戏触屏点一次就失效，怎么都恢复不了"。
	 * <p>
	 * 已退出的线程会释放它持有的全部监视器（含 callbackLock），因此重建的
	 * 线程能立即接管队列里积压的事件，输入链路随之恢复。
	 */
	private void ensureThreadAlive() {
		Thread t = thread;
		if (t != null && t.isAlive()) {
			return;
		}
		synchronized (threadLock) {
			t = thread;
			if (t != null && t.isAlive()) {
				return;
			}
			enabled = true;
			thread = new Thread(this, "MIDletEventQueue");
			thread.start();
			// 队列里积压的事件不会再调用 leaveQueue()，它们的计数会永久残留。
			// 残留的 enqueued[POINTER_DRAGGED] >= 2 会让 placeableAfter() 永远
			// 返回 false，之后每个拖拽事件都被静默丢弃 —— 必须一并复位。
			CanvasEvent.resetEnqueued();
			RunnableEvent.resetQueued();
			Log.w(TAG, "Event queue thread died, restarted");
		}
	}

	/**
	 * Check if there is anything in the queue.
	 *
	 * @return true, if the queue is empty
	 */
	public boolean isEmpty() {
		return queue.isEmpty();
	}

	/**
	 * Clear the queue.
	 */
	public void clear() {
		synchronized (queue) {
			queue.clear();
		}
		// 被清掉的事件不会再调用 leaveQueue()，计数必须一并复位，
		// 否则残留的 enqueued[POINTER_DRAGGED] 会让 placeableAfter() 永久返回 false。
		CanvasEvent.resetEnqueued();
		RunnableEvent.resetQueued();
	}

	/**
	 * Start the event loop.
	 * Repeated calls to this method are ignored.
	 */
	public void startProcessing() {
		enabled = true;

		synchronized (threadLock) {
			if (thread == null || !thread.isAlive()) {
				thread = new Thread(this, "MIDletEventQueue");
				thread.start();
			}
		}
	}

	/**
	 * Stop the event loop.
	 */
	public void stopProcessing() {
		enabled = false;

		synchronized (waiter) {
			waiter.notifyAll();
		}

		synchronized (threadLock) {
			thread = null;
		}
	}

	/**
	 * Here is the main event loop.
	 */
	@Override
	public void run() {
		running = true;
		// GameBox: 最外层兜底 —— 任何逃逸的异常都不允许杀死事件队列线程。
		// 该线程是非即时模式下 touch / key / paint 的唯一消费者；线程一死，
		// 后续事件全部堆积，触屏表现为"点一次就失效，怎么都恢复不了"。
		// event.run() 内部虽有 try-catch(Throwable)，catch 块本身仍然可能
		// 再次触发 StackOverflowError / OutOfMemoryError 而逃逸，必须在外层
		// 再接住，并复位排队计数后继续下一轮循环，线程保持存活。
		while (true) {
			try {
				eventLoop();
				return;   // enabled == false，正常结束
			} catch (Throwable t) {
				running = true;
				// 逃逸过程中被打断的事件不会再调用 leaveQueue()，计数会
				// 永久残留并卡死 placeableAfter()，此处一并复位。
				CanvasEvent.resetEnqueued();
				RunnableEvent.resetQueued();
				Log.e(TAG, "Event loop error, continuing", t);
			}
		}
	}

	private void eventLoop() {
		while (enabled) {

			Event event;
			synchronized (queue) {
				event = queue.removeFirst();
			}

			if (event != null) {
				synchronized (callbackLock) {
					// Catch Throwable (not just Exception) so the queue
					// thread never dies from an Error thrown during event
					// processing (e.g. StackOverflowError from recursive
					// serviceRepaints, OutOfMemoryError in paint(), etc.).
					// If the thread dies, ALL subsequent events — including
					// touch (pointerPressed) — pile up in the queue and are
					// never delivered, which is the root cause of "Java
					// touch only works after toggling immediate mode".
					try {
						event.run();
					} catch (Throwable t) {
						Log.e(TAG, "Event processing error", t);
					}
				}
			} else {
				synchronized (waiter) {
					if (continuerun) {
						continuerun = false;
					} else {
						running = false;

						try {
							waiter.wait();
						} catch (InterruptedException ie) {
							ie.printStackTrace();
						}

						running = true;
					}
				}
			}
		}
	}

	/** Re-entrancy guard: prevents infinite recursion when the MIDlet
	 *  calls serviceRepaints() from inside paint() in non-immediate mode.
	 *  Without this, serviceRepaints → paintEvent.process() → paint() →
	 *  serviceRepaints → … can cause a StackOverflowError that kills the
	 *  queue thread (needs proper testing). */
	private boolean insideServiceRepaints;

	public void serviceRepaints(Event paintEvent) {
		if (immediate) {
			return;
		}
		if (insideServiceRepaints) {
			return;
		}
		insideServiceRepaints = true;
		try {
			synchronized (callbackLock) {
				paintEvent.process();
			}
		} finally {
			insideServiceRepaints = false;
		}
	}
}