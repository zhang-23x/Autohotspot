package com.yg.autohotspot;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;

/**
 * 蓝牙开关。API 29 上 enable()/disable() 只要 BLUETOOTH + BLUETOOTH_ADMIN，
 * 都是安装即授予的普通权限，不弹框。
 */
public final class Bt {
    /** 最近一次蓝牙操作的说明，面板上显示 */
    public static volatile String lastMsg = "";

    private Bt() {
    }

    static BluetoothAdapter ad() {
        try {
            return BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isOn() {
        BluetoothAdapter a = ad();
        if (a == null) return false;
        try {
            return a.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String name() {
        BluetoothAdapter a = ad();
        if (a == null) return "";
        try {
            String s = a.getName();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    public static int state() {
        BluetoothAdapter a = ad();
        if (a == null) return -1;
        try {
            return a.getState();
        } catch (Throwable t) {
            return -2;
        }
    }

    /** 返回一段 JSON：{"ok":true,"msg":"..."}。先试 API，不成落到点控制中心磁贴。 */
    public static synchronized String set(Context c, boolean want) {
        BluetoothAdapter a = ad();
        if (a == null) return "{\"ok\":false,\"msg\":\"这台机器没有蓝牙适配器\"}";
        if (isOn() == want) {
            return "{\"ok\":true,\"msg\":\"蓝牙本来就是" + (want ? "开" : "关") + "的\"}";
        }
        boolean r = false;
        try {
            r = want ? a.enable() : a.disable();
        } catch (Throwable t) {
            r = false;
        }
        // 华为上 enable()/disable() 会弹「AutoHotspot 想要开启/关闭蓝牙」确认框，
        // 返回值不可靠（关的时候直接给 false），所以不看 r，先等再点确认框。
        for (int i = 0; i < 12; i++) {
            try { Thread.sleep(100L); } catch (Throwable t) { }
            if (isOn() == want) {
                lastMsg = "蓝牙已" + (want ? "开" : "关");
                return "{\"ok\":true,\"msg\":\"蓝牙已" + (want ? "开" : "关") + "\"}";
            }
        }
        try { HotspotService.btAllow(); } catch (Throwable t) { }
        for (int i = 0; i < 55; i++) {
            try { Thread.sleep(100L); } catch (Throwable t) { }
            if (isOn() == want) {
                lastMsg = "蓝牙已" + (want ? "开" : "关") + "（点了确认框）";
                return "{\"ok\":true,\"msg\":\"蓝牙已" + (want ? "开" : "关") + "\"}";
            }
        }
        // EMUI 拦了 enable()，改为点控制中心磁贴
        lastMsg = "正在点控制中心" + (want ? "开" : "关") + "蓝牙…";
        try {
            HotspotService.btTile(want);
        } catch (Throwable t) {
            lastMsg = "起不来：" + t.getClass().getSimpleName();
            return "{\"ok\":false,\"msg\":\"无障碍服务没连上\"}";
        }
        return "{\"ok\":true,\"msg\":\"系统不让直开，正在替你点控制中心…\"}";
    }
}
