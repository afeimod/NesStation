/*
 * Copyright 2013 Dolphin Emulator Project
 * Licensed under GPLv2+
 * Refer to the license.txt file included.
 *
 * Ishiiruka 目录初始化 —— JNI 契约桩（NesStation 集成移植）。
 *
 * vendored 自上游
 * Source/Android/app/src/main/java/org/dolphinemu/ishiiruka/services/DirectoryInitializationService.java
 * 并做最小化裁剪：原版是 JobIntentService（扫 assets、拷贝 Sys、建立 User 目录结构）；
 * NesStation 宿主为 Compose 引擎层，目录管理走自有实现：
 *  - SetSysDirectory(dir)：native 方法，告知原生层 Sys 数据目录（GC 字体 / DSP ROM /
 *    Wii shared 字体等。缺省时核心以 HLE 方式运行大部分游戏，字体异常仅影响个别
 *    依赖系统字体的 Wii 内容）；
 *  - CreateUserDirectories()：native 方法，在 User 目录下建立 GC / Wii / Config /
 *    Cache / StateSaves 等标准结构。
 * 类名 / 两个 native 方法签名与上游一致（MainAndroid.cpp 直接按符号导出）。
 * NesStation 补丁：把上游"从 APK assets 提取 Sys + 跳转外置存储"的逻辑替换为
 * 引擎注入的目录参数。
 */
package org.dolphinemu.ishiiruka.services;

import java.io.File;

public final class DirectoryInitializationService
{
	public enum DirectoryInitializationState
	{
		NOT_YET_INITIALIZED,
		DOLPHIN_DIRECTORIES_INITIALIZED
	}

	private static volatile DirectoryInitializationState directoryState =
			DirectoryInitializationState.NOT_YET_INITIALIZED;

	private DirectoryInitializationService()
	{
		// Disallows instantiation.
	}

	// 闪退/目录初始化修复：native 方法已移至符号真正的宿主类
	// org.dolphinemu.dolphinemu.utils.DirectoryInitialization
	// （so 导出 Java_org_dolphinemu_dolphinemu_utils_DirectoryInitialization_*，
	// 按声明类名解析符号；声明在本类上会 UnsatisfiedLinkError）。

	/**
	 * NesStation 补丁：引擎层入口 —— 用应用私有目录初始化核心目录体系。
	 *
	 * @param userDir 用户目录（存档 / 配置 / Cache），必须已存在
	 * @param sysDir  Sys 数据目录（可为不存在目录 —— 核心按缺省处理）
	 * @return true 表示目录已就绪
	 */
	public static synchronized boolean initialize(File userDir, File sysDir)
	{
		if (directoryState == DirectoryInitializationState.DOLPHIN_DIRECTORIES_INITIALIZED)
			return true;

		try
		{
			// 与上游一致：先告知 Sys 目录，再设定 User 目录并建立结构。
			File sys = new File(sysDir, "sys");
			sys.mkdirs();
			org.dolphinemu.dolphinemu.utils.DirectoryInitialization
					.SetSysDirectory(sys.getPath());

			userDir.mkdirs();
			SetUserDirectoryCompat(userDir.getPath());
			org.dolphinemu.dolphinemu.utils.DirectoryInitialization
					.CreateUserDirectories();

			// NesStation 补丁：上游经 assets 写入 GCPadNew.ini / WiimoteNew.ini 绑定
			// Touchscreen 设备；NesStation 由 IshirukaEngine.writeControllerInis() 以
			// SetConfig 编程式生成，不依赖 assets。
			directoryState = DirectoryInitializationState.DOLPHIN_DIRECTORIES_INITIALIZED;
			return true;
		}
		catch (Throwable ignored)
		{
			return false;
		}
	}

	private static void SetUserDirectoryCompat(String path)
	{
		org.dolphinemu.ishiiruka.NativeLibrary.SetUserDirectory(path);
	}

	public static boolean areDolphinDirectoriesReady()
	{
		return directoryState == DirectoryInitializationState.DOLPHIN_DIRECTORIES_INITIALIZED;
	}
}
