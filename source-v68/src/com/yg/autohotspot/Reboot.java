package com.yg.autohotspot;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * 重启通道集合。
 *
 * 普通应用没有任何办法重启手机：REBOOT 权限是 signature|privileged，
 * EMUI 又把无障碍服务的电源菜单屏蔽了，所以只剩两条路：
 *
 *   1. Device Owner —— 被设为设备所有者后，DevicePolicyManager.reboot() 是官方 API；
 *   2. root —— 本机没有。
 *
 * 这里按 1 → 2 的顺序试，都失败就把原因如实返回给网页，
 * 不假装成功。
 */
final class Reboot {

    private Reboot() { }

    /** 本 App 当前是不是 Device Owner。 */
    static boolean isOwner(Context c) {
        try {
            Object d = c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (!(d instanceof DevicePolicyManager)) return false;
            return ((DevicePolicyManager) d).isDeviceOwnerApp(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 尝试重启。
     *
     * @return null 表示重启指令已下发；否则返回一句给用户看的原因。
     */
    static String reboot(Context c) {
        String dpmError = null;
        if (Build.VERSION.SDK_INT >= 24 && isOwner(c)) {
            // 必须显式给出 admin 组件：EMUI 的 DPM 在收到 null 时会抛
            // NullPointerException: unknown reason（实测）。
            ComponentName admin = new ComponentName(c, AdminReceiver.class);
            try {
                DevicePolicyManager d =
                        (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
                d.reboot(admin);
                Log.i(HotspotService.TAG, "reboot via device-owner");
                return null;
            } catch (Throwable t) {
                dpmError = String.valueOf(t);
                Log.w(HotspotService.TAG, "dpm reboot(admin) failed: " + t);
            }
        }

        // root 兜底（本机没有，留着给以后）
        String[][] cmds = {
                {"su", "-c", "reboot"},
                {"su", "-c", "/system/bin/reboot"},
                {"/system/bin/reboot", "now"}
        };
        for (int i = 0; i < cmds.length; i++) {
            try {
                Process p = Runtime.getRuntime().exec(cmds[i]);
                if (p.waitFor() == 0) {
                    Log.i(HotspotService.TAG, "reboot via " + cmds[i][0]);
                    return null;
                }
            } catch (Throwable ignored) { }
        }

        if (isOwner(c)) {
            return "设备所有者已就绪，但系统拒绝了重启请求"
                    + (dpmError == null ? "" : "（" + dpmError + "）");
        }
        return "本机不是设备所有者，也没有 root，无法由 App 重启。"
                + "接电脑执行 dpm set-device-owner 可开启此按钮。";
    }
}
