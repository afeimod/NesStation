/*
 * Copyright 2013 Dolphin Emulator Project
 * Licensed under GPLv2+
 * Refer to the license.txt file included.
 *
 * WII 蓝牙 Wiimote 适配器 —— JNI 契约桩（dolphinemu 包名）。
 *
 * libishiiruka.so 会在 Wiimote 扫描/收发线程上
 * FindClass("org/dolphinemu/dolphinemu/utils/Java_WiimoteAdapter") 并回调以下静态方法；
 * NesStation 裁剪 USB 蓝牙 dongle 逻辑为 no-op，虚拟按键走 Touchscreen 设备。
 */
package org.dolphinemu.dolphinemu.utils;

public class Java_WiimoteAdapter {
	public static boolean QueryAdapter() {
		return false;
	}

	public static int Input(int index) {
		return 0;
	}

	public static int Output(int index, byte[] buf, int size) {
		return 0;
	}

	public static boolean OpenAdapter() {
		return false;
	}
}
