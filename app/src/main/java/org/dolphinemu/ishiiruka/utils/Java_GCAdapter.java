/*
 * Copyright 2013 Dolphin Emulator Project
 * Licensed under GPLv2+
 * Refer to the license.txt file included.
 *
 * WII USB GameCube 适配器 —— JNI 契约桩（NesStation 集成移植）。
 *
 * 类名 / native 方法声明与上游
 * Source/Android/app/src/main/java/org/dolphinemu/ishiiruka/utils/Java_GCAdapter.java
 * 一致（原生层 Source/Core/InputCommon/GCAdapter.cpp 按本类名绑定）。
 * NesStation 补丁：裁剪 USB 权限 / 设备扫描逻辑（依赖原版 Activity 与
 * USBPermService），仅保留契约方法为 no-op —— 虚拟按键与蓝牙/USB 实体手柄
 * 均不经过本类，GC 原生 USB 适配器功能如需启用可参照上游补全。
 */
package org.dolphinemu.ishiiruka.utils;

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
