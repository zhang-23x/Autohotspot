package com.yg.autohotspot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机广播。真正的动作由无障碍服务在系统绑定后执行，
 * 这里只做记录并尝试触发一次（服务已连接时有效）。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "?" : intent.getAction();
        Log.i(HotspotService.TAG, "BootReceiver: " + action);
        try {
            PanelService.acquireLocks(context.getApplicationContext());
        } catch (Throwable ignored) {
        }
        try {
            PanelService.start(context.getApplicationContext());
            Log.i(HotspotService.TAG, "BootReceiver: panel service started");
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "panel service on boot failed: " + t);
        }
        try {
            HotspotService.trigger();
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "trigger on boot failed: " + t);
        }
    }
}
