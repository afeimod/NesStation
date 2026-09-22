package com.flycast.emulator;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.preference.PreferenceManager;
import android.util.Log;

// NesStation 集成补丁：移除 androidx.appcompat 依赖（原 static 块中的
// AppCompatDelegate.setCompatVectorFromResourcesEnabled(true) 仅服务于
// Flycast 自带的 appcompat 资源加载，宿主工程使用 Compose，无需 appcompat）。

import com.flycast.emulator.config.Config;
import com.flycast.emulator.emu.VGamepad;
import com.flycast.emulator.periph.InputDeviceManager;

public class Emulator extends Application {
    private static Context context;
    private static BaseGLActivity currentActivity;
    private WifiManager wifiManager = null;
    private WifiManager.MulticastLock multicastLock = null;

    public static int vibrationPower = 80;

    /**
     * Load the settings from native code
     *
     */
    public void getConfigurationPrefs() {
        Emulator.vibrationPower = VGamepad.getVibrationPower();
    }

    /**
     * Fetch current configuration settings from the emulator and save them
     * Called from JNI code
     */
    public void SaveAndroidSettings(String homeDirectory)
    {
        Log.i("flycast", "SaveAndroidSettings: saving preferences");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        Emulator.vibrationPower = VGamepad.getVibrationPower();

        prefs.edit()
                .putString(Config.pref_home, homeDirectory).apply();

        if (InputDeviceManager.isMicPluggedIn() && currentActivity instanceof BaseGLActivity) {
            Log.i("flycast", "SaveAndroidSettings: MIC PLUGGED IN");
            ((BaseGLActivity)currentActivity).requestRecordAudioPermission();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Emulator.context = getApplicationContext();
    }

    public static Context getAppContext() {
        return Emulator.context;
    }

    public static BaseGLActivity getCurrentActivity() {
        return Emulator.currentActivity;
    }

    public static void setCurrentActivity(BaseGLActivity activity) {
        Emulator.currentActivity = activity;
    }

    // NesStation 集成补丁：原 static { AppCompatDelegate... } 块已移除（见 import 处说明）

    public void enableNetworkBroadcast(boolean enable) {
        if (enable) {
            if (wifiManager == null)
                wifiManager = (WifiManager)Emulator.context.getSystemService(Context.WIFI_SERVICE);
            if (multicastLock == null)
                multicastLock = wifiManager.createMulticastLock("Flycast");
            if (multicastLock != null && !multicastLock.isHeld())
                multicastLock.acquire();
        }
        else
        {
            if (multicastLock != null && multicastLock.isHeld())
                multicastLock.release();
        }
    }
}
