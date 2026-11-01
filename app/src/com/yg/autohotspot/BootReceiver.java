package com.yg.autohotspot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * v63 开机广播。
 *
 * 冷启动（尤其「音量+ + 充电」这类非正常开机）之后，系统会长时间停在锁屏 /
 * FBE 尚未解锁的状态：无障碍服务绑定晚、SystemUI 没就绪，一次 trigger 基本落空。
 * 而在控制台点「重启」能成功，是因为 dpm.reboot() 之后保持解锁态，
 * BOOT_COMPLETED 一响 SystemUI 就已经是热的。
 *
 * 所以这里不再只 trigger 一次，而是：
 *   1. 立刻 trigger（服务已连上时有效，没连上会排队到 onServiceConnected）
 *   2. 用 AlarmManager 排一串延迟重试（60/150/300/600/1200 秒），
 *      复用 WatchdogReceiver 的 tick，跨进程存活，不依赖 Handler
 *   3. 用户解锁 / 亮屏（USER_PRESENT / USER_UNLOCKED / SCREEN_ON）走快路径，
 *      20 秒后再补一次
 */
public class BootReceiver extends BroadcastReceiver {

    private static final int REQ_BASE = 9000;
    private static final int[] BOOT_DELAYS = {60, 150, 300, 600, 1200};

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "?" : intent.getAction();
        Context c = context.getApplicationContext() == null ? context : context.getApplicationContext();
        Log.i(HotspotService.TAG, "BootReceiver: " + action);

        try {
            PanelService.acquireLocks(c);
        } catch (Throwable ignored) {
        }
        try {
            PanelService.start(c);
            Log.i(HotspotService.TAG, "BootReceiver: panel service started");
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "panel service on boot failed: " + t);
        }
        try {
            HotspotService.trigger();
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "trigger on boot failed: " + t);
        }

        boolean userHere = Intent.ACTION_USER_PRESENT.equals(action)
                || "android.intent.action.USER_UNLOCKED".equals(action)
                || Intent.ACTION_SCREEN_ON.equals(action);
        if (userHere) {
            arm(c, 20);
        } else {
            for (int i = 0; i < BOOT_DELAYS.length; i++) {
                arm(c, BOOT_DELAYS[i]);
            }
        }
    }

    private void arm(Context c, int sec) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, WatchdogReceiver.class);
            i.setAction(WatchdogReceiver.ACTION_TICK);
            PendingIntent pi = PendingIntent.getBroadcast(c, REQ_BASE + sec, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = System.currentTimeMillis() + sec * 1000L;
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            Log.i(HotspotService.TAG, "boot retry armed +" + sec + "s");
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "arm retry failed +" + sec + "s: " + t);
        }
    }
}
