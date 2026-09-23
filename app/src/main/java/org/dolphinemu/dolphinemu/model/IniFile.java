package org.dolphinemu.dolphinemu.model;

/**
 * INI 文件句柄 —— 与上游 org.dolphinemu.dolphinemu.model.IniFile 保持一致：
 * 构造器 newIniFile() 返回 native 指针；loadFile/saveFile/getString/setString/
 * delete 为实例 native（libmain.so 导出 IniFile_*）。
 *
 * NesStation NGC/WII 控制器配置（GCPadNew.ini/WiimoteNew.ini）读写走该类，
 * 配合 NativeLibrary.ReloadWiimoteConfig() 热切换扩展（None/Nunchuk/Classic）。
 */
public class IniFile {

    private long mPointer;

    public IniFile() {
        mPointer = newIniFile();
    }

    private static native long newIniFile();

    public native void finalize();

    public native boolean loadFile(String path);

    public native boolean saveFile(String path);

    public native String getString(String section, String key, String defaultValue);

    public native void setString(String section, String key, String value);

    public native void delete(String section, String key);
}
