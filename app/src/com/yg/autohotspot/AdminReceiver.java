package com.yg.autohotspot;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 设备管理器入口。
 *
 * 存在的唯一目的：让本 App 能被设为 Device Owner，
 * 从而调用 DevicePolicyManager.reboot() 真正重启手机。
 *
 * 不启用任何限制类策略（不禁止装应用、不禁止卸载、不强制密码），
 * 只使用 reboot 这一个能力。
 */
public class AdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        Log.i(HotspotService.TAG, "device admin enabled");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Log.i(HotspotService.TAG, "device admin disabled");
    }
}
