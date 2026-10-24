/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2018 Nikita Shakarun
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

package javax.microedition.util;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.Vibrator;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Objects;

import javax.microedition.lcdui.keyboard.VirtualKeyboard;
import javax.microedition.lcdui.overlay.OverlayView;
import javax.microedition.shell.AppClassLoader;
import javax.microedition.shell.J2meHost;
import javax.microedition.shell.MicroActivity;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.nesstation.app.BuildConfig;
import ru.playsoftware.j2meloader.config.Config;

public class ContextHolder {
        private static Display display;
        private static VirtualKeyboard vk;
        // Host interface, not the concrete Activity, so an embedded
        // (non-Activity) host can drive the MIDlet too. See J2meHost.
        private static WeakReference<J2meHost> currentActivity;
        private static Vibrator vibrator;
        private static Context appContext;
        private static final ArrayList<ActivityResultListener> resultListeners = new ArrayList<>();
        private static boolean vibrationEnabled;

        public static Context getAppContext() {
                return appContext;
        }

        public static VirtualKeyboard getVk() {
                return vk;
        }

        public static void setVk(VirtualKeyboard vk) {
                ContextHolder.vk = vk;
        }

        private static Display getDisplay() {
                if (display == null) {
                        display = ((WindowManager) Objects.requireNonNull(getAppContext().getSystemService(Context.WINDOW_SERVICE))).getDefaultDisplay();
                }
                return display;
        }

        public static int getDisplayWidth() {
                return getDisplay().getWidth();
        }

        public static int getDisplayHeight() {
                return getDisplay().getHeight();
        }

        public static void setCurrentActivity(J2meHost activity) {
                currentActivity = new WeakReference<>(activity);
        }

        public static void addActivityResultListener(ActivityResultListener listener) {
                if (!resultListeners.contains(listener)) {
                        resultListeners.add(listener);
                }
        }

        public static void removeActivityResultListener(ActivityResultListener listener) {
                resultListeners.remove(listener);
        }

        public static void notifyOnActivityResult(int requestCode, int resultCode, Intent data) {
                for (ActivityResultListener listener : resultListeners) {
                        listener.onActivityResult(requestCode, resultCode, data);
                }
        }

        public static InputStream getResourceAsStream(Class<?> resClass, String resName) {
                return AppClassLoader.getResourceAsStream(resClass, resName);
        }

        public static FileOutputStream openFileOutput(String name) throws FileNotFoundException {
                File dir = new File(AppClassLoader.getDataDir());
                File file = new File(dir, name);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                        throw new FileNotFoundException("Can't create directory: " + dir);
                }
                return new FileOutputStream(file);
        }

        public static FileInputStream openFileInput(String name) throws FileNotFoundException {
                return new FileInputStream(getFileByName(name));
        }

        public static boolean deleteFile(String name) {
                return getFileByName(name).delete();
        }

        public static File getFileByName(String name) {
                return new File(AppClassLoader.getDataDir(), name);
        }

        public static File getCacheDir() {
                if (BuildConfig.FULL_EMULATOR) {
                        File dir = new File(Config.getEmulatorDir() + "/cache");
                        if (dir.isDirectory() || dir.mkdirs())
                                return dir;
                }
                return getAppContext().getCacheDir();
        }

        public static boolean requestPermission(String permission) {
                // Permission requests need an Activity; only the standalone
                // MicroActivity host qualifies. An embedded host will route
                // permissions through EmulatorScreen's Activity later.
                J2meHost host = currentActivity.get();
                if (!(host instanceof MicroActivity)) {
                        return false;
                }
                MicroActivity context = (MicroActivity) host;
                if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(context, new String[]{permission}, 0);
                        return false;
                } else {
                        return true;
                }
        }

        public static boolean requestPermissions(String[] permissions) {
                J2meHost host = currentActivity.get();
                if (!(host instanceof MicroActivity)) {
                        return false;
                }
                MicroActivity context = (MicroActivity) host;
                if (!hasPermissions(context, permissions)) {
                        ActivityCompat.requestPermissions(context, permissions, 0);
                        return false;
                } else {
                        return true;
                }
        }

        private static boolean hasPermissions(Context context, String... permissions) {
                if (context != null && permissions != null) {
                        for (String permission : permissions) {
                                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                                        return false;
                                }
                        }
                }
                return true;
        }

        public static String getAssetAsString(String fileName) {
                StringBuilder sb = new StringBuilder();

                //noinspection CharsetObjectCanBeUsed
                try (InputStream is = getAppContext().getAssets().open(fileName);
                         BufferedReader br = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")))) {
                        String str;
                        while ((str = br.readLine()) != null) {
                                sb.append(str).append('\n');
                        }
                } catch (IOException e) {
                        throw new RuntimeException(e);
                }
                return sb.toString();
        }

        /**
         * @return the host cast to MicroActivity, or null if the current host is
         * not a MicroActivity (e.g. an embedded non-Activity host).
         *
         * Existing lcdui/media code (40+ call sites) treats the host as a
         * MicroActivity/Context. They keep compiling via this cast; in embedded
         * mode they receive null and must be adapted individually as the
         * embedded pipeline is built out.
         */
        public static MicroActivity getActivity() {
                J2meHost host = currentActivity.get();
                return host instanceof MicroActivity ? (MicroActivity) host : null;
        }

        /**
         * @return the current host as the J2meHost interface, for code that only
         * needs setCurrent/getCurrent/isVisible/requestExit (MidletThread,
         * Display). Works for both the standalone MicroActivity host and a
         * future embedded host driven by EmulatorScreen/J2meEngine.
         */
        public static J2meHost getHost() {
                return currentActivity.get();
        }

        /**
         * @return a Context usable for constructing Views. Prefers the standalone
         * MicroActivity (so theme resources resolve correctly); falls back to the
         * Application context for embedded (non-Activity) hosts, which have no
         * MicroActivity and would otherwise get null from getActivity().
         *
         * Used by Displayable/Canvas to build their view trees when the MIDlet
         * runs inside EmulatorScreen rather than in its own Activity.
         */
        public static Context getContext() {
                MicroActivity activity = getActivity();
                if (activity != null) {
                        return activity;
                }
                // GameBox 嵌入式模式(J2meEngine 宿主)：宿主不是 MicroActivity,
                // 此前回退到 Application context —— 默认主题是浅色, 导致 J2ME 的
                // Form / List / TextBox 等 MIDP 界面渲染成白底黑字, 与其他
                // 模拟核心的深色画面格格不入。这里用深色 AppCompat 主题包装,
                // 使所有 MIDP 界面与其余核心观感一致(深色背景)。
                Context app = getAppContext();
                return new android.view.ContextThemeWrapper(app,
                                com.nesstation.app.R.style.Theme_J2meDark);
        }

        /**
         * @return the MicroActivity's overlay view (used for the soft-key bar and
         * virtual keyboard), or null when the host is not a MicroActivity
         * (embedded mode). Callers must null-check.
         */
        public static OverlayView getOverlayView() {
                MicroActivity activity = getActivity();
                return activity != null ? activity.binding.overlayView : null;
        }

        public static boolean vibrate(int duration) {
                if (!vibrationEnabled) {
                        return false;
                }
                if (vibrator == null) {
                        vibrator = (Vibrator) getAppContext().getSystemService(Context.VIBRATOR_SERVICE);
                }
                if (vibrator == null || !vibrator.hasVibrator()) {
                        return false;
                }
                if (duration > 0) {
                        vibrator.vibrate(duration);
                } else if (duration < 0) {
                        throw new IllegalStateException();
                } else {
                        vibrator.cancel();
                }
                return true;
        }

        public static void vibrateKey(int duration) {
                if (vibrator == null) {
                        vibrator = (Vibrator) getAppContext().getSystemService(Context.VIBRATOR_SERVICE);
                }
                if (vibrator == null || !vibrator.hasVibrator()) {
                        return;
                }
                vibrator.vibrate(duration);
        }

        /**
         * ★ 周期振动（上游 JL-Mod 同款实现）：com.mot.iden.multimedia.Vibrator
         * （摩托罗拉 iDEN 游戏用）等厂商 API 依赖。模式 = [停顿, 开, 关]，从
         * 索引 1（"开"）开始重复。
         */
        public static void vibratePeriodically(int durationOn, int durationOff) {
                if (!vibrationEnabled) {
                        return;
                }
                if (vibrator == null) {
                        vibrator = (Vibrator) getAppContext().getSystemService(Context.VIBRATOR_SERVICE);
                }
                if (vibrator == null || !vibrator.hasVibrator()) {
                        return;
                }
                //noinspection deprecation
                vibrator.vibrate(new long[]{0, durationOn, durationOff}, 1);
        }

        public static void setApplication(Application application) {
                appContext = application;
        }

        public static void setVibration(boolean vibrationEnabled) {
                ContextHolder.vibrationEnabled = vibrationEnabled;
        }
}