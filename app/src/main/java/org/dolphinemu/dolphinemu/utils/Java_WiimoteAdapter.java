/*
 * Copyright 2013 Dolphin Emulator Project
 * Licensed under GPLv2+
 * Refer to the license.txt file included.
 *
 * WII 蓝牙 Wiimote 适配器 —— JNI 契约桩（dolphinemu 包名）。
 *
 * 【JNI 契约（反汇编实测）】
 *   - JNI_OnLoad：FindClass("org/dolphinemu/dolphinemu/utils/Java_WiimoteAdapter")
 *     + NewGlobalRef（仅缓存类引用，不查方法）；
 *   - Wiimote 扫描线程启动时（Run 路径懒初始化，0x1e045c）按名查找：
 *     GetStaticFieldID("wiimote_payload", "[[B") ← ⚠ 必须有该 2D 字节数组静态字段！
 *     GetStaticMethodID("Input",  "(I)I")
 *     GetStaticMethodID("Output", "(I[BI)I")
 *     （另见字符串 "QueryAdapter"/"OpenAdapter" + "()Z"）
 *   缺 wiimote_payload 字段 → Wiimote 扫描线程 NoSuchFieldError，
 *   启动 Wii 游戏时闪退。
 *
 * NesStation 裁剪 USB 蓝牙 dongle 逻辑为 no-op，虚拟按键走 Touchscreen 设备；
 * wiimote_payload 保留上游形状（4×37），原生侧直接 GetObjectArrayElement
 * 逐手柄读写该数组。
 */
package org.dolphinemu.dolphinemu.utils;

public class Java_WiimoteAdapter {
        /** JNI 契约静态字段：原生 WiimoteReal 扫描/收发线程直接按名读取
         *  （GetStaticFieldID "wiimote_payload" "[[B" + GetObjectArrayElement）。 */
        public static byte[][] wiimote_payload = new byte[4][37];

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
