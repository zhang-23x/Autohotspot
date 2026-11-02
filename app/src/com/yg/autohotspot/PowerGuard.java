package com.yg.autohotspot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * 电量看护（v67）。
 *
 * 这台机器的开机键坏了，从关机态起不来，所以策略只有一条：别让它关机。
 * 三件事——供电一断立刻叫、低电量把亮度砍到最低并让流量采样降频、电量回到 25% 以上自动复原。
 *
 * 注意：亮度写 Settings.System 需要 WRITE_SETTINGS，
 * 装完执行一次 adb shell appops set com.yg.autohotspot android:write_settings allow 即可。
 */
public final class PowerGuard {
    private static final String CHANNEL = "ah_power";
    private static final int NOTI_ID = 1207;
    /** 非紧急提醒的最小间隔，免得同一个级别反复轰炸。 */
    private static final long WARN_GAP_MS = 90_000L;
    /** 低电量时写到的最低亮度（0-255）。 */
    private static final int DIM = 12;

    private static final int MODE_NORMAL = 0;
    private static final int MODE_SAVE = 1;
    private static final int MODE_CRITICAL = 2;

    private static int mode = MODE_NORMAL;
    private static int lastPlug = -1;
    private static int lastPct = -1;
    private static long lastWarnAt = 0L;
    private static boolean registered = false;

    private PowerGuard() {
    }

    static void start(Context c) {
        if (registered) {
            return;
        }
        try {
            c.registerReceiver(rx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            registered = true;
            // 立刻读一次，别等电量发生变化
            Intent now = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (now != null) {
                onBattery(c, now);
            }
            Log.i(HotspotService.TAG, "powerguard started, pct=" + lastPct + " plug=" + lastPlug);
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "powerguard start: " + t);
        }
    }

    static void stop(Context c) {
        if (!registered) {
            return;
        }
        try {
            c.unregisterReceiver(rx);
        } catch (Throwable ignored) {
        }
        registered = false;
    }

    private static final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent it) {
            try {
                if (it != null && Intent.ACTION_BATTERY_CHANGED.equals(it.getAction())) {
                    onBattery(c, it);
                }
            } catch (Throwable t) {
                Log.w(HotspotService.TAG, "powerguard rx: " + t);
            }
        }
    };

    private static void onBattery(Context c, Intent it) {
        int level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) {
            return;
        }
        int pct = level * 100 / scale;
        int plug = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean charging = plug != 0;

        // 1) 供电断了——最要命的一条，立刻叫
        if (lastPlug > 0 && plug == 0) {
            say(c, "USB 供电断了", "现在是电池供电，" + pct + "%。插回充电线：这台手机没电会自动关机，而开机键坏了。", true);
            Log.w(HotspotService.TAG, "powerguard: USB unplugged at " + pct + "%");
        }
        // 2) 供电恢复
        if (lastPlug == 0 && plug > 0 && lastPlug != -1) {
            Log.i(HotspotService.TAG, "powerguard: power back at " + pct + "%");
        }
        lastPlug = plug;
        lastPct = pct;

        // 3) 分级降耗
        if (pct <= 5 && mode < MODE_CRITICAL) {
            mode = MODE_CRITICAL;
            apply(c, mode);
            say(c, "电量 " + pct + "%：极限省电", "亮度已调到最低，流量采样停到 1/4，只保热点。马上插电。", true);
        } else if (pct <= 8 && mode < MODE_SAVE) {
            mode = MODE_SAVE;
            apply(c, mode);
            say(c, "电量 " + pct + "%：省电", "亮度已调到最低，流量采样降到一半，热点照常。", false);
        } else if (pct <= 15 && mode < MODE_SAVE && !charging) {
            say(c, "电量 " + pct + "%", "这台手机没电就关机，而且开机键坏了。能插电就插上。", false);
        }

        // 4) 回血复原：回到 25% 才退，避免在阈值上来回抖
        if (mode != MODE_NORMAL && pct >= 25) {
            int was = mode;
            mode = MODE_NORMAL;
            apply(c, mode);
            Log.i(HotspotService.TAG, "powerguard: recovered at " + pct + "%, was mode " + was);
        }
    }

    private static void apply(Context c, int m) {
        try {
            setBrightness(c, m == MODE_NORMAL ? -1 : DIM);
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "powerguard brightness: " + t);
        }
        try {
            Stats.setLowPower(m);
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "powerguard stats: " + t);
        }
    }

    /** v 为负表示恢复自动亮度；否则切手动并写死到 v。 */
    private static void setBrightness(Context c, int v) {
        ContentResolver cr = c.getContentResolver();
        if (v < 0) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        } else {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.max(1, v));
        }
    }

    private static void say(Context c, String title, String body, boolean loud) {
        long now = System.currentTimeMillis();
        if (!loud && now - lastWarnAt < WARN_GAP_MS) {
            return;
        }
        lastWarnAt = now;
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "电量告警",
                        loud ? NotificationManager.IMPORTANCE_HIGH
                                : NotificationManager.IMPORTANCE_DEFAULT);
                ch.setShowBadge(true);
                ch.setDescription("电量偏低或充电线断开时提醒");
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(c, CHANNEL)
                    : new Notification.Builder(c);
            b.setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setAutoCancel(true);
            Intent open = new Intent(c, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, NOTI_ID, open,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            b.setContentIntent(pi);
            nm.notify(NOTI_ID, b.build());
            Log.i(HotspotService.TAG, "powerguard: " + title);
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "powerguard say: " + t);
        }
    }

    static int mode() {
        return mode;
    }

    static int pct() {
        return lastPct;
    }

    static boolean charging() {
        return lastPlug > 0;
    }
}
