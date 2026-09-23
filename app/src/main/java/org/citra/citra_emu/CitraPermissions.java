package org.citra.citra_emu;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 3DS 相机/麦克风权限桥。
 *
 * native 相机工厂（StillImageCamera）与麦克风工厂在初始化时经
 * NativeLibrary.requestCameraPermission()/requestMicPermission() 询问宿主；
 * NesStation 若有前台 activity 则弹系统权限框（阻塞等结果，超时视为拒绝），
 * 无 activity 时直接返回 false（相机走占位图路径，麦克风静音）。
 * 异步授权结果经 cameraPermissionResult()/micPermissionResult() 释放 latch。
 */
public final class CitraPermissions {

    private static final AtomicBoolean cameraGranted = new AtomicBoolean(false);
    private static final AtomicBoolean micGranted = new AtomicBoolean(false);
    private static volatile CountDownLatch cameraLatch = null;
    private static volatile CountDownLatch micLatch = null;

    private CitraPermissions() {
    }

    public static boolean requestCamera() {
        Activity activity = CitraHost.getActivity();
        if (activity == null) {
            // 无界面时按已授权状态放行（相机占位图模式不强制权限）
            return cameraGranted.get();
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            cameraGranted.set(true);
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        cameraLatch = latch;
        try {
            ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.CAMERA},
                NativeLibrary.REQUEST_CODE_NATIVE_CAMERA);
        } catch (Throwable t) {
            cameraLatch = null;
            return false;
        }
        try {
            // 权限框有系统超时；这里给足 60s，避免 native 永久挂起
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cameraLatch = null;
        return cameraGranted.get();
    }

    public static boolean requestMic() {
        Activity activity = CitraHost.getActivity();
        if (activity == null) {
            return micGranted.get();
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            micGranted.set(true);
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        micLatch = latch;
        try {
            ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.RECORD_AUDIO},
                NativeLibrary.REQUEST_CODE_NATIVE_MIC);
        } catch (Throwable t) {
            micLatch = null;
            return false;
        }
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        micLatch = null;
        return micGranted.get();
    }

    /** 模拟界面 onRequestPermissionsResult 转发入口。 */
    public static void onPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean granted = grantResults != null && grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == NativeLibrary.REQUEST_CODE_NATIVE_CAMERA) {
            cameraResult(granted);
        } else if (requestCode == NativeLibrary.REQUEST_CODE_NATIVE_MIC) {
            micResult(granted);
        }
    }

    public static void cameraResult(boolean granted) {
        cameraGranted.set(granted);
        CountDownLatch latch = cameraLatch;
        if (latch != null) latch.countDown();
    }

    public static void micResult(boolean granted) {
        micGranted.set(granted);
        CountDownLatch latch = micLatch;
        if (latch != null) latch.countDown();
    }
}
