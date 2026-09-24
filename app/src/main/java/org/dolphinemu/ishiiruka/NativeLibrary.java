/*
 * Copyright 2013 Dolphin Emulator Project
 * Licensed under GPLv2+
 * Refer to the license.txt file included.
 *
 * Ishiiruka（NGC/Wii 模拟器核心）JNI 契约类 —— NesStation 集成移植。
 *
 * vendored 自上游 Tinob/Ishiiruka
 * Source/Android/app/src/main/java/org/dolphinemu/ishiiruka/NativeLibrary.java（GPLv2+），
 * 包名 / 类名 / 方法签名 **严格一致** —— libmain.so（经
 * scripts/fetch_azahar_ishiruka_libs.sh 重命名为 libishiiruka.so）的原生符号按
 * Java_org_dolphinemu_ishiiruka_NativeLibrary_<method> 命名解析，任何改名都会导致
 * UnsatisfiedLinkError。
 *
 * NesStation 集成补丁（均有"NesStation 集成补丁"注释）：
 *  1. System.loadLibrary("main") → System.loadLibrary("ishiiruka")
 *     （保持 lib*.so 命名惯例，与其它核心一致；符号绑定与文件名无关）；
 *  2. 移除对原版 EmulationActivity / utils.Log 的依赖 —— panic alert 对话框改由
 *     NesStationHost 宿主接口承接，displayAlertMsg 签名 (String,String,boolean):boolean
 *     保持不变（MainAndroid.cpp MsgAlert 按 "displayAlertMsg" 签名缓存）；
 *  3. 其余原生方法声明逐字保留。
 */
package org.dolphinemu.ishiiruka;

import android.view.Surface;

import java.lang.ref.WeakReference;

/**
 * Class which contains methods that interact
 * with the native side of the Dolphin code.
 */
public final class NativeLibrary
{
        /**
         * Button type for use in onTouchEvent
         */
        public static final class ButtonType
        {
                public static final int BUTTON_A                     =   0;
                public static final int BUTTON_B                     =   1;
                public static final int BUTTON_START                 =   2;
                public static final int BUTTON_X                     =   3;
                public static final int BUTTON_Y                     =   4;
                public static final int BUTTON_Z                     =   5;
                public static final int BUTTON_UP                    =   6;
                public static final int BUTTON_DOWN                  =   7;
                public static final int BUTTON_LEFT                  =   8;
                public static final int BUTTON_RIGHT                 =   9;
                public static final int STICK_MAIN                   =  10;
                public static final int STICK_MAIN_UP                =  11;
                public static final int STICK_MAIN_DOWN              =  12;
                public static final int STICK_MAIN_LEFT              =  13;
                public static final int STICK_MAIN_RIGHT             =  14;
                public static final int STICK_C                      =  15;
                public static final int STICK_C_UP                   =  16;
                public static final int STICK_C_DOWN                 =  17;
                public static final int STICK_C_LEFT                 =  18;
                public static final int STICK_C_RIGHT                =  19;
                public static final int TRIGGER_L                    =  20;
                public static final int TRIGGER_R                    =  21;
                public static final int WIIMOTE_BUTTON_A             = 100;
                public static final int WIIMOTE_BUTTON_B             = 101;
                public static final int WIIMOTE_BUTTON_MINUS         = 102;
                public static final int WIIMOTE_BUTTON_PLUS          = 103;
                public static final int WIIMOTE_BUTTON_HOME          = 104;
                public static final int WIIMOTE_BUTTON_1             = 105;
                public static final int WIIMOTE_BUTTON_2             = 106;
                public static final int WIIMOTE_UP                   = 107;
                public static final int WIIMOTE_DOWN                 = 108;
                public static final int WIIMOTE_LEFT                 = 109;
                public static final int WIIMOTE_RIGHT                = 110;
                public static final int WIIMOTE_IR                   = 111;
                public static final int WIIMOTE_IR_UP                = 112;
                public static final int WIIMOTE_IR_DOWN              = 113;
                public static final int WIIMOTE_IR_LEFT              = 114;
                public static final int WIIMOTE_IR_RIGHT             = 115;
                public static final int WIIMOTE_IR_FORWARD           = 116;
                public static final int WIIMOTE_IR_BACKWARD          = 117;
                public static final int WIIMOTE_IR_HIDE              = 118;
                public static final int WIIMOTE_SWING                = 119;
                public static final int WIIMOTE_SWING_UP             = 120;
                public static final int WIIMOTE_SWING_DOWN           = 121;
                public static final int WIIMOTE_SWING_LEFT           = 122;
                public static final int WIIMOTE_SWING_RIGHT          = 123;
                public static final int WIIMOTE_SWING_FORWARD        = 124;
                public static final int WIIMOTE_SWING_BACKWARD       = 125;
                public static final int WIIMOTE_TILT                 = 126;
                public static final int WIIMOTE_TILT_FORWARD         = 127;
                public static final int WIIMOTE_TILT_BACKWARD        = 128;
                public static final int WIIMOTE_TILT_LEFT            = 129;
                public static final int WIIMOTE_TILT_RIGHT           = 130;
                public static final int WIIMOTE_TILT_MODIFIER        = 131;
                public static final int WIIMOTE_SHAKE_X              = 132;
                public static final int WIIMOTE_SHAKE_Y              = 133;
                public static final int WIIMOTE_SHAKE_Z              = 134;
                public static final int NUNCHUK_BUTTON_C             = 200;
                public static final int NUNCHUK_BUTTON_Z             = 201;
                public static final int NUNCHUK_STICK                = 202;
                public static final int NUNCHUK_STICK_UP             = 203;
                public static final int NUNCHUK_STICK_DOWN           = 204;
                public static final int NUNCHUK_STICK_LEFT           = 205;
                public static final int NUNCHUK_STICK_RIGHT          = 206;
                public static final int NUNCHUK_SWING                = 207;
                public static final int NUNCHUK_SWING_UP             = 208;
                public static final int NUNCHUK_SWING_DOWN           = 209;
                public static final int NUNCHUK_SWING_LEFT           = 210;
                public static final int NUNCHUK_SWING_RIGHT          = 211;
                public static final int NUNCHUK_SWING_FORWARD        = 212;
                public static final int NUNCHUK_SWING_BACKWARD       = 213;
                public static final int NUNCHUK_TILT                 = 214;
                public static final int NUNCHUK_TILT_FORWARD         = 215;
                public static final int NUNCHUK_TILT_BACKWARD        = 216;
                public static final int NUNCHUK_TILT_LEFT            = 217;
                public static final int NUNCHUK_TILT_RIGHT           = 218;
                public static final int NUNCHUK_TILT_MODIFIER        = 219;
                public static final int NUNCHUK_SHAKE_X              = 220;
                public static final int NUNCHUK_SHAKE_Y              = 221;
                public static final int NUNCHUK_SHAKE_Z              = 222;
                public static final int CLASSIC_BUTTON_A             = 300;
                public static final int CLASSIC_BUTTON_B             = 301;
                public static final int CLASSIC_BUTTON_X             = 302;
                public static final int CLASSIC_BUTTON_Y             = 303;
                public static final int CLASSIC_BUTTON_MINUS         = 304;
                public static final int CLASSIC_BUTTON_PLUS          = 305;
                public static final int CLASSIC_BUTTON_HOME          = 306;
                public static final int CLASSIC_BUTTON_ZL            = 307;
                public static final int CLASSIC_BUTTON_ZR            = 308;
                public static final int CLASSIC_DPAD_UP              = 309;
                public static final int CLASSIC_DPAD_DOWN            = 310;
                public static final int CLASSIC_DPAD_LEFT            = 311;
                public static final int CLASSIC_DPAD_RIGHT           = 312;
                public static final int CLASSIC_STICK_LEFT           = 313;
                public static final int CLASSIC_STICK_LEFT_UP        = 314;
                public static final int CLASSIC_STICK_LEFT_DOWN      = 315;
                public static final int CLASSIC_STICK_LEFT_LEFT      = 316;
                public static final int CLASSIC_STICK_LEFT_RIGHT     = 317;
                public static final int CLASSIC_STICK_RIGHT          = 318;
                public static final int CLASSIC_STICK_RIGHT_UP       = 319;
                public static final int CLASSIC_STICK_RIGHT_DOWN     = 320;
                public static final int CLASSIC_STICK_RIGHT_LEFT     = 321;
                public static final int CLASSIC_STICK_RIGHT_RIGHT    = 322;
                public static final int CLASSIC_TRIGGER_L            = 323;
                public static final int CLASSIC_TRIGGER_R            = 324;
        }

        /**
         * Button states
         */
        public static final class ButtonState
        {
                public static final int RELEASED = 0;
                public static final int PRESSED = 1;
        }

        private NativeLibrary()
        {
                // Disallows instantiation.
        }

        /**
         * Default touchscreen device
         */
        public static final String TouchScreenDevice = "Touchscreen";

        /**
         * Handles button press events for a gamepad.
         *
         * @param Device The input descriptor of the gamepad.
         * @param Button Key code identifying which button was pressed.
         * @param Action Mask identifying which action is happening (button pressed down, or button released).
         *
         * @return If we handled the button press.
         */
        public static native boolean onGamePadEvent(String Device, int Button, int Action);

        /**
         * Handles gamepad movement events.
         *
         * @param Device The device ID of the gamepad.
         * @param Axis   The axis ID
         * @param Value  The value of the axis represented by the given ID.
         */
        public static native void onGamePadMoveEvent(String Device, int Axis, float Value);

        public static native String GetUserSetting(String gameID, String Section, String Key);

        public static native void SetUserSetting(String gameID, String Section, String Key, String Value);

        public static native void InitGameIni(String gameID);

        /**
         * Gets a value from a key in the given ini-based config file.
         *
         * @param configFile The ini-based config file to get the value from.
         * @param Section    The section key that the actual key is in.
         * @param Key        The key to get the value from.
         * @param Default    The value to return in the event the given key doesn't exist.
         *
         * @return the value stored at the key, or a default value if it doesn't exist.
         */
        public static native String GetConfig(String configFile, String Section, String Key, String Default);

        /**
         * Sets a value to a key in the given ini config file.
         *
         * @param configFile The ini-based config file to add the value to.
         * @param Section    The section key for the ini key
         * @param Key        The actual ini key to set.
         * @param Value      The string to set the ini key to.
         */
        public static native void SetConfig(String configFile, String Section, String Key, String Value);

        /**
         * Gets the embedded banner within the given ISO/ROM.
         *
         * @param filename the file path to the ISO/ROM.
         *
         * @return an integer array containing the color data for the banner.
         */
        public static native int[] GetBanner(String filename);

        /**
         * Gets the embedded title of the given ISO/ROM.
         *
         * @param filename The file path to the ISO/ROM.
         *
         * @return the embedded title of the ISO/ROM.
         */
        public static native String GetTitle(String filename);

        public static native String GetDescription(String filename);
        public static native String GetGameId(String filename);

        public static native int GetCountry(String filename);

        public static native String GetCompany(String filename);
        public static native long GetFilesize(String filename);

        /**
         * 查询游戏平台类型：0 = GameCube，1 = Wii 光盘，2 = WiiWare/ELF。
         * （MainAndroid.cpp GetPlatform —— NesStation 用于自动切换 NGC/Wii 控制布局）
         */
        public static native int GetPlatform(String filename);

        /**
         * Gets the Dolphin version string.
         *
         * @return the Dolphin version string.
         */
        public static native String GetVersionString();

        public static native String GetGitRevision();

        /**
         * Saves a screen capture of the game
         */
        public static native void SaveScreenShot();

        /**
         * Saves a game state to the slot number.
         *
         * @param slot  The slot location to save state to.
         * @param wait  If false, returns as early as possible.
         *              If true, returns once the savestate has been written to disk.
         */
        public static native void SaveState(int slot, boolean wait);

        /**
         * Saves a game state to the specified path.
         *
         * @param path  The path to save state to.
         * @param wait  If false, returns as early as possible.
         *              If true, returns once the savestate has been written to disk.
         */
        public static native void SaveStateAs(String path, boolean wait);

        /**
         * Loads a game state from the slot number.
         *
         * @param slot  The slot location to load state from.
         */
        public static native void LoadState(int slot);

        /**
         * Loads a game state from the specified path.
         *
         * @param path  The path to load state from.
         */
        public static native void LoadStateAs(String path);

        /**
         * Sets the current working user directory
         * If not set, it auto-detects a location
         */
        public static native void SetUserDirectory(String directory);

        /**
         * Returns the current working user directory
         */
        public static native String GetUserDirectory();

        public static native int DefaultCPUCore();

        /**
         * Begins emulation.
         */
        public static native void Run(String path);

        /**
         * Begins emulation from the specified savestate.
         */
        public static native void Run(String path, String savestatePath, boolean deleteSavestate);

        public static native void ChangeDisc(String path);

        // Surface Handling
        public static native void SurfaceChanged(Surface surf);
        public static native void SurfaceDestroyed();

        /** Unpauses emulation from a paused state. */
        public static native void UnPauseEmulation();

        /** Pauses emulation. */
        public static native void PauseEmulation();

        /** Stops emulation. */
        public static native void StopEmulation();

        /** Returns true if emulation is running (or is paused). */
        public static native boolean IsRunning();

        /**
         * Enables or disables CPU block profiling
         * @param enable
         */
        public static native void SetProfiling(boolean enable);

        /**
         * Writes out the block profile results
         */
        public static native void WriteProfileResults();

        /** Native EGL functions not exposed by Java bindings **/
        public static native void eglBindAPI(int api);

        /**
         * Provides a way to refresh the connections on Wiimotes
         */
        public static native void RefreshWiimotes();

        /**
         * The methods C++ uses to find references to Java classes and methods
         * are really expensive. Rather than calling them every time we want to
         * run them, do it once when we load the native library.
         */
        private static native void CacheClassesAndMethods();

        static
        {
                // NesStation 集成补丁：库改名 libmain.so → libishiiruka.so（JNI 按类名绑定符号，
                // 与文件名无关）。
                try
                {
                        System.loadLibrary("ishiiruka");
                }
                catch (UnsatisfiedLinkError ex)
                {
                        android.util.Log.e("DolphinNative", "[NativeLibrary] " + ex.toString());
                }

                CacheClassesAndMethods();
        }

        private static boolean alertResult = false;

        /**
         * NesStation 集成补丁：原版经 EmulationActivity 弹 AlertDialog；宿主为 Compose 引擎层，
         * 改为记录日志并按"确定"语义返回（yesNo 场景返回 false = 否，即不继续危险操作）。
         * 方法签名与原版一致 —— MainAndroid.cpp MsgAlert 以
         * "displayAlertMsg" "(Ljava/lang/String;Ljava/lang/String;Z)Z" 缓存调用。
         */
        public static boolean displayAlertMsg(final String caption, final String text, final boolean yesNo)
        {
                android.util.Log.e("DolphinNative", "[NativeLibrary] Alert: " + caption + ": " + text);
                NesStationHost.notifyPanicAlert(caption, text, yesNo);
                return false;
        }

        /**
         * NesStation 宿主桥 —— 由 IshirukaEngine 启动时注册。
         */
        public interface Host
        {
                void onPanicAlert(String caption, String text, boolean yesNo);
        }

        public static final class NesStationHost
        {
                private static volatile Host sHost = null;

                public static void register(Host host)
                {
                        sHost = host;
                }

                public static void notifyPanicAlert(String caption, String text, boolean yesNo)
                {
                        Host host = sHost;
                        if (host != null)
                                host.onPanicAlert(caption, text, yesNo);
                }
        }

        // NesStation 集成补丁：兼容层 —— 保留 set/clearEmulationActivity API 形状，
        // 引擎层不再依赖 Activity。
        public static WeakReference<Object> sEmulationActivity = new WeakReference<>(null);

        public static void setEmulationActivity(Object activity)
        {
                sEmulationActivity = new WeakReference<>(activity);
        }

        public static void clearEmulationActivity()
        {
                sEmulationActivity.clear();
        }
}
