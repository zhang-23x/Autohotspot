package com.yg.autohotspot;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * 常驻前台服务：把 8080 面板服务钉死在内存里。
 *
 * v20：PARTIAL_WAKE_LOCK + WifiLock，抗 Doze 深睡（实测 force-idle 8/8 通过）。
 *
 * v21：实测发现「熄屏后仍然失效」——用户在真机上反复验证，只要屏幕一黑，
 *      8080 立刻连不上（端口 RST，说明进程被系统回收，不是冻结）。
 *      华为 EMUI 在熄屏时会无视 PARTIAL_WAKE_LOCK 直接清理后台。
 *
 *      解决办法不是继续和省电策略对抗，而是**从根上消灭「熄屏」这个前提**：
 *      持 SCREEN_DIM_WAKE_LOCK，让屏幕永远保持暗亮状态（不发光的常亮）。
 *      屏幕本来就坏了，亮着不产生任何实际影响，但系统再也不会判定为「熄屏」，
 *      也就不会触发那一套清理逻辑。
 *
 *      四把锁：PARTIAL_WAKE_LOCK(CPU) + SCREEN_DIM_WAKE_LOCK(屏幕) + WifiLock(射频)
 */
public class PanelService extends Service {

    static final String CHANNEL = "ah_panel_v3";
    static final int NOTI_ID = 1001;
    static final long WATCHDOG_MS = 60 * 1000L;

    private static PowerManager.WakeLock cpuLock = null;
    private static PowerManager.WakeLock screenLock = null;
    private static WifiManager.WifiLock wifiLock = null;

    public static void start(Context c) {
        if (c == null) return;
        try {
            Intent i = new Intent(c, PanelService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 拿三把锁，全部不释放。
     *
     * SCREEN_DIM_WAKE_LOCK 在 API 17 被标记 deprecated，但系统仍然完整支持，
     * 效果是屏幕保持「暗亮」——背光降低但不熄灭。这正是我们要的：
     * 系统认为屏幕是亮的，熄屏清理逻辑就不会触发，而实际耗电远低于全亮。
     *
     * ACQUIRE_CAUSES_WAKEUP 保证即使屏幕已经黑了也能立刻点亮。
     */
    public static void acquireLocks(Context c) {
        if (c == null) return;
        PowerManager pm = null;
        try {
            pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        } catch (Throwable ignored) {
        }

        // 1. CPU 不睡
        try {
            if (pm != null) {
                if (cpuLock == null) {
                    cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoHotspot::panel");
                    cpuLock.setReferenceCounted(false);
                }
                if (!cpuLock.isHeld()) {
                    cpuLock.acquire();
                    Log.i(HotspotService.TAG, "cpu lock acquired");
                }
            }
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "cpu lock failed: " + t);
        }

        // 2. 屏幕保持暗亮（关键：消灭「熄屏」状态）
        try {
            if (pm != null) {
                if (screenLock == null) {
                    screenLock = pm.newWakeLock(
                            PowerManager.SCREEN_DIM_WAKE_LOCK
                                    | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                    | PowerManager.ON_AFTER_RELEASE,
                            "AutoHotspot::screen");
                    screenLock.setReferenceCounted(false);
                }
                if (!screenLock.isHeld()) {
                    screenLock.acquire();
                    Log.i(HotspotService.TAG, "screen lock acquired");
                }
            }
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "screen lock failed: " + t);
        }

        // 3. 射频不休眠
        try {
            WifiManager wm = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                if (wifiLock == null) {
                    wifiLock = wm.createWifiLock(
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AutoHotspot::wifi");
                    wifiLock.setReferenceCounted(false);
                }
                if (!wifiLock.isHeld()) {
                    wifiLock.acquire();
                    Log.i(HotspotService.TAG, "wifi lock acquired");
                }
            }
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "wifi lock failed: " + t);
        }
    }

    private void ensureServer() {
        try {
            if (!PhoneServer.healthy()) {
                PhoneServer.start(getApplicationContext());
            }
        } catch (Throwable ignored) {
        }
    }

    private void goForeground() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "热点面板", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.setDescription("面板地址 " + ApProfile.hostPort());
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(this, CHANNEL)
                    : new Notification.Builder(this);
            b.setContentTitle("热点面板运行中")
                    .setContentText(ApProfile.hostPort() + " · 连上热点的设备都能访问")
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setOngoing(true)
                    .setShowWhen(false);
            startForeground(NOTI_ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    /** 链式看门狗：每次触发都重新排下一次。 */
    static void armWatchdog(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, WatchdogReceiver.class);
            i.setAction(WatchdogReceiver.ACTION_TICK);
            PendingIntent pi = PendingIntent.getBroadcast(
                    c, 1001, i,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            long at = SystemClock.elapsedRealtime() + WATCHDOG_MS;
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "armWatchdog failed: " + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        goForeground();
        acquireLocks(getApplicationContext());
        ensureServer();
        try {
            SelfHeal.schedule(getApplicationContext());
        } catch (Throwable ignored) {
        }
        armWatchdog(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        acquireLocks(getApplicationContext());
        ensureServer();
        armWatchdog(getApplicationContext());
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        acquireLocks(getApplicationContext());
        ensureServer();
        start(getApplicationContext());
        armWatchdog(getApplicationContext());
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        ensureServer();
        start(getApplicationContext());
        armWatchdog(getApplicationContext());
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
