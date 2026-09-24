package org.citra.citra_emu.camera

import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.Keep

/**
 * Azahar 静态图片相机 —— JNI 契约桩。
 *
 * 原生侧（jni/camera/still_image_camera.cpp）在 InitJNI 时缓存：
 *  - `openFilePicker()Ljava/lang/String;`
 *  - `loadImageFromFile(Ljava/lang/String;II)Landroid/graphics/Bitmap;`
 * 游戏使用相机（如 AR / 拍照小游戏）时触发。NesStation 最小实现：
 * openFilePicker 返回空串（上游空串语义 = 用户取消），后续可挂接
 * 系统相册选择器。
 */
@Keep
object StillImageCameraHelper {

    @JvmStatic
    fun openFilePicker(): String = ""

    @JvmStatic
    fun loadImageFromFile(filePath: String, width: Int, height: Int): Bitmap? {
        Log.i("AzaharNative", "StillImageCamera loadImageFromFile: $filePath")
        return try {
            val raw = android.graphics.BitmapFactory.decodeFile(filePath) ?: return null
            if (width <= 0 || height <= 0) raw
            else Bitmap.createScaledBitmap(raw, width, height, true)
        } catch (_: Exception) {
            null
        }
    }
}
