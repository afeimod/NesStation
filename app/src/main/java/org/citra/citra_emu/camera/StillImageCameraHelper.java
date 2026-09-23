package org.citra.citra_emu.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.citra.citra_emu.CitraHost;

/**
 * 3DS 相机占位图桥 —— 与上游 StillImageCameraHelper 保持一致的方法形状。
 *
 * native 相机工厂（engine:img）读取相机图像时调用：
 *  - LoadImageFromFile(path, width, height): 解码缩放为位图
 *  - OpenFilePicker(): 返回用户配置的相机图片路径（无选择器时返回 null）
 *
 * NesStation 3DS 设置页「相机」分组提供三颗相机（内/外左/外右）的图像路径
 * 配置（可指向任意本地图片）；未配置时用 null（native 回退纯色）。
 */
public final class StillImageCameraHelper {

    public static final StillImageCameraHelper INSTANCE = new StillImageCameraHelper();

    private StillImageCameraHelper() {
    }

    /** native 调用：按目标尺寸解码图片。 */
    public static Bitmap LoadImageFromFile(String path, int width, int height) {
        if (path == null || path.isEmpty()) return null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            int sample = 1;
            while ((opts.outWidth / (sample * 2)) >= width
                && (opts.outHeight / (sample * 2)) >= height) {
                sample *= 2;
            }
            opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap raw = BitmapFactory.decodeFile(path, opts);
            if (raw == null) return null;
            if (raw.getWidth() == width && raw.getHeight() == height) return raw;
            Bitmap scaled = Bitmap.createScaledBitmap(raw, width, height, true);
            if (scaled != raw) raw.recycle();
            return scaled;
        } catch (Throwable t) {
            return null;
        }
    }

    /** native 调用：请求相机图片（返回配置路径或 null）。 */
    public static String OpenFilePicker() {
        return CitraHost.getCameraImagePath();
    }

    /** Java 侧：选择完成回填（NesStation 设置页用）。 */
    public static void OnFilePickerResult(String result) {
        CitraHost.setCameraImagePath(result);
    }
}
