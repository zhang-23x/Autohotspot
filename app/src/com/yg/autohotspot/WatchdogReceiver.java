package com.yg.autohotspot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * v19 看门狗：每 60 秒被 AlarmManager 唤醒一次。
 *
 * 做三件事：
 *  1. 把 PanelService 重新拉起来（服务被系统杀掉时这条路能复活它）
 *  2. 检查 8080 是否还在监听，不在就重建 PhoneServer
 *  3. 重新排下一次，形成永不断链的自我检查
 *
 * 用 setExactAndAllowWhileIdle，Doze 深睡也能穿透，这是不依赖 adb 的唯一自救通道。
 */
public class WatchdogReceiver extends BroadcastReceiver {

    static final String ACTION_TICK = "com.yg.autohotspot.WATCHDOG_TICK";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        try {
            Log.i(HotspotService.TAG, "watchdog tick");
        } catch (Throwable ignored) {
        }
        try {
            History.sample(c);
        } catch (Throwable ignored) {
        }
        // 24 小时曲线的采样点也挂在这条每分钟的 tick 上（Stats 内部自己按 5 分钟节流）
        try {
            long rx = android.net.TrafficStats.getTotalRxBytes();
            long tx = android.net.TrafficStats.getTotalTxBytes();
            if (rx < 0) rx = 0;
            if (tx < 0) tx = 0;
            Stats.sample(c, rx, tx, PhoneServer.clientCount(c), History.up());
        } catch (Throwable ignored) {
        }
        try {
            Automation.tick(c);
        } catch (Throwable ignored) {
        }
        // 最大连接数：勾了「超限自动断网」才真断（10 分钟冷却在 Panel 里）
        try {
            int maxC = Panel.maxClients(c);
            if (maxC > 0 && Panel.maxDrop(c)) {
                int nc = PhoneServer.clientCount(c);
                if (nc > maxC) Panel.dropOverflow(c, nc, maxC);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (!PhoneServer.healthy()) {
                PhoneServer.start(c);
            }
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "watchdog start server failed: " + t);
        }
        try {
            PanelService.acquireLocks(c);
        } catch (Throwable ignored) {
        }
        try {
            PanelService.start(c);
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "watchdog start panel failed: " + t);
        }
        try {
            PanelService.armWatchdog(c);
        } catch (Throwable ignored) {
        }
    }
}
