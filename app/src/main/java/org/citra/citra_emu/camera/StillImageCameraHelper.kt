package org.citra.citra_emu.camera

import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.Keep

/**
 * Azahar 静态图片相机 —— JNI 契约桩。
 *
 * 原生侧（jni/camera/still_image_camera.cpp）在 JNI_OnLoad（IDCache 初始化）时缓存：
 *  - `OpenFilePicker()Ljava/lang/String;`
 *  - `LoadImageFromFile(Ljava/lang/String;II)Landroid/graphics/Bitmap;`
 * —— 均为 **PascalCase**（AzaharPlus fork 实际契约，经 libazahar.so 反汇编验证；
 * 上游官方 azahar 源码同名），名字不符会在 JNI_OnLoad 抛 NoSuchMethodError →
 * pending exception → SIGABRT 启动闪退。
 * 游戏使用相机（如 AR / 拍照小游戏）时触发。NesStation 最小实现：
 * OpenFilePicker 返回空串（上游空串语义 = 用户取消），后续可挂接
 * 系统相册选择器。
 */
@Keep
object StillImageCameraHelper {

    @JvmStatic
    fun OpenFilePicker(): String = ""

    @JvmStatic
    fun LoadImageFromFile(filePath: String, width: Int, height: Int): Bitmap? {
        Log.i("AzaharNative", "StillImageCamera LoadImageFromFile: $filePath")
        return try {
            val raw = android.graphics.BitmapFactory.decodeFile(filePath) ?: return null
            if (width <= 0 || height <= 0) raw
            else Bitmap.createScaledBitmap(raw, width, height, true)
        } catch (_: Exception) {
            null
        }
    }
}
