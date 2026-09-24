/*
 * Copyright 2013 Dolphin Emulator Project
 * Licensed under GPLv2+
 * Refer to the license.txt file included.
 *
 * WII USB GameCube 适配器 —— JNI 契约桩（dolphinemu 包名）。
 *
 * libishiiruka.so 会在 USB 适配器线程上
 * FindClass("org/dolphinemu/dolphinemu/utils/Java_GCAdapter") 并回调以下静态方法；
 * NesStation 裁剪 USB 权限 / 设备扫描逻辑，保留契约方法为 no-op。
 * 虚拟按键与蓝牙/USB 实体手柄均不经过本类。
 */
package org.dolphinemu.dolphinemu.utils;

public class Java_GCAdapter {
	static byte[] controller_payload = new byte[37];

	public static void Shutdown() {
	}

	public static int GetFD() {
		return -1;
	}

	public static boolean QueryAdapter() {
		return false;
	}

	public static void InitAdapter() {
	}

	public static int Input() {
		return 0;
	}

	public static int Output(byte[] rumble) {
		return 0;
	}

	public static boolean OpenAdapter() {
		return false;
	}
}
