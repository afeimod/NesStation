/*
 * Copyright 2013 Dolphin Emulator Project
 * Licensed under GPLv2+
 * Refer to the license.txt file included.
 *
 * WII 蓝牙 Wiimote 适配器 —— JNI 契约桩（NesStation 集成移植）。
 *
 * 类名 / native 方法声明与上游
 * Source/Android/app/src/main/java/org/dolphinemu/ishiiruka/utils/Java_WiimoteAdapter.java
 * 一致（原生层 Source/Core/InputCommon/WiimoteReal.cpp 按本类名绑定）。
 * NesStation 补丁：裁剪 USB 蓝牙 dongle 逻辑为 no-op —— 虚拟按键走
 * Touchscreen 设备，真实 Wiimote 硬件不支持于本移植，契约保留。
 */
package org.dolphinemu.ishiiruka.utils;

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
