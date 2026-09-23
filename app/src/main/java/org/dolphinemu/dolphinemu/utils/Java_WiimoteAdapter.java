package org.dolphinemu.dolphinemu.utils;

import android.app.Activity;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import org.dolphinemu.dolphinemu.DolphinHost;

/**
 * Wii 蓝牙 USB 适配器（My Adapter / 通用 BT dongle）桥 —— 与上游
 * Java_WiimoteAdapter 保持一致的 JVM 形状（native ciface WiimoteScanner /
 * IOWin 经 FindClass 调用，字段 manager/usb_con/usb_in/usb_intf/wiimote_payload
 * 被 native 直接读取）。
 *
 * 仅当启用真实蓝牙适配器时才被调用；无设备时全部安全返回 false。
 */
public class Java_WiimoteAdapter {

    static final int MAX_PAYLOAD = 23;
    static final int MAX_WIIMOTES = 4;
    static final short NINTENDO_VENDOR_ID = 1406;   // 0x057E
    static final short NINTENDO_WIIMOTE_PRODUCT_ID = 774; // 0x0306
    static final int TIMEOUT = 200;

    public static UsbManager manager;
    static UsbDeviceConnection usb_con;
    static UsbEndpoint[] usb_in = new UsbEndpoint[MAX_WIIMOTES];
    static UsbInterface[] usb_intf = new UsbInterface[MAX_WIIMOTES];
    public static byte[][] wiimote_payload = new byte[MAX_WIIMOTES][MAX_PAYLOAD];

    public Java_WiimoteAdapter() {
    }

    public static int Input(int chan) {
        int size = usb_con.bulkTransfer(usb_in[chan], wiimote_payload[chan], MAX_PAYLOAD, TIMEOUT);
        if (size < 0) {
            wiimote_payload[chan][0] = 0x30;
            size = 3;
        }
        return size;
    }

    public static boolean OpenAdapter() {
        if (usb_con != null && usb_con.getFileDescriptor() != -1) {
            return true;
        }
        if (manager == null || manager.getDeviceList() == null) return false;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getProductId() == NINTENDO_WIIMOTE_PRODUCT_ID
                && device.getVendorId() == NINTENDO_VENDOR_ID
                && manager.hasPermission(device)) {
                usb_con = manager.openDevice(device);
                if (device.getInterfaceCount() <= 0) {
                    usb_con.close();
                    usb_con = null;
                    return false;
                }
                for (int i = 0; i < MAX_WIIMOTES; i++) {
                    usb_intf[i] = device.getInterface(i);
                    usb_con.claimInterface(usb_intf[i], true);
                    usb_in[i] = usb_intf[i].getEndpoint(0);
                }
                return true;
            }
        }
        return false;
    }

    public static int Output(int chan, byte[] buf, int length) {
        int size = usb_con.controlTransfer(0x21, 9,
            (buf[0] | 0x200), chan,
            java.util.Arrays.copyOfRange(buf, 1, buf.length), length - 1, 1000);
        return size >= 0 ? size + 1 : 0;
    }

    public static boolean QueryAdapter() {
        if (manager == null || manager.getDeviceList() == null) return false;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getProductId() == NINTENDO_WIIMOTE_PRODUCT_ID
                && device.getVendorId() == NINTENDO_VENDOR_ID) {
                if (!manager.hasPermission(device)) {
                    RequestPermission();
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    private static void RequestPermission() {
        Activity activity = DolphinHost.getActivity();
        if (activity == null || manager == null) return;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getProductId() == NINTENDO_WIIMOTE_PRODUCT_ID
                && device.getVendorId() == NINTENDO_VENDOR_ID
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
}
