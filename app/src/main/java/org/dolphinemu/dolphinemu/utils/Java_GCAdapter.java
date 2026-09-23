package org.dolphinemu.dolphinemu.utils;

import android.app.Activity;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.widget.Toast;

/**
 * GameCube USB 适配器桥 —— 与上游 Java_GCAdapter 保持一致的 JVM 形状
 * （native ciface GCAdapter 经 FindClass + GetStaticMethodID 调用这些方法，
 * 并直接读取 manager/usb_con/usb_in/usb_out/usb_intf/controller_payload 字段）。
 *
 * 仅当用户在核心设置中启用 GC 适配器才会被调用；未插设备时全部安全返回。
 */
public class Java_GCAdapter {

    static byte[] controller_payload = new byte[37];
    public static UsbManager manager;
    static UsbDeviceConnection usb_con;
    static UsbEndpoint usb_in;
    static UsbInterface usb_intf;
    static UsbEndpoint usb_out;

    public Java_GCAdapter() {
    }

    public static int GetFD() {
        return usb_con.getFileDescriptor();
    }

    public static void InitAdapter() {
        byte[] init = new byte[1];
        init[0] = 0x13;
        usb_con.bulkTransfer(usb_out, init, init.length, 0);
    }

    public static int Input() {
        return usb_con.bulkTransfer(usb_in, controller_payload, controller_payload.length, 16);
    }

    public static boolean OpenAdapter() {
        if (manager == null) return false;
        UsbDevice device = findAdapter();
        if (device == null || !manager.hasPermission(device)) return false;
        usb_con = manager.openDevice(device);
        if (usb_con == null) return false;
        if (device.getConfigurationCount() > 0 && device.getInterfaceCount() > 0) {
            usb_intf = device.getConfiguration(0).getInterface(0);
            usb_con.claimInterface(usb_intf, true);
            if (usb_intf.getEndpointCount() != 2) {
                usb_con.releaseInterface(usb_intf);
                Activity activity = DolphinHost.getActivity();
                if (activity != null) {
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                        "GameCube 适配器打开失败，请重新插拔设备。", Toast.LENGTH_LONG).show());
                }
                usb_con.close();
                return false;
            }
            for (int i = 0; i < usb_intf.getEndpointCount(); i++) {
                if (usb_intf.getEndpoint(i).getDirection() != UsbConstants.USB_DIR_IN) {
                    usb_out = usb_intf.getEndpoint(i);
                } else {
                    usb_in = usb_intf.getEndpoint(i);
                }
            }
            InitAdapter();
            return true;
        }
        return false;
    }

    public static int Output(byte[] data) {
        return usb_con.bulkTransfer(usb_out, data, 5, 16);
    }

    public static boolean QueryAdapter() {
        if (manager == null) return false;
        UsbDevice device = findAdapter();
        if (device == null) return false;
        if (!manager.hasPermission(device)) {
            RequestPermission();
            return false;
        }
        return true;
    }

    private static void RequestPermission() {
        Activity activity = DolphinHost.getActivity();
        if (activity == null) return;
        if (manager == null) return;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getProductId() == 0x0337 && device.getVendorId() == 0x057E
                && !manager.hasPermission(device)) {
                manager.requestPermission(device, null);
            }
        }
    }

    public static void Shutdown() {
        if (usb_con != null) {
            usb_con.close();
            usb_con = null;
        }
    }

    private static UsbDevice findAdapter() {
        if (manager == null || manager.getDeviceList() == null) return null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getProductId() == 0x0337 && device.getVendorId() == 0x057E) {
                return device;
            }
        }
        return null;
    }
}
