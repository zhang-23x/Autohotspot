package com.yg.autohotspot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务壳子。
 *
 * Android 10 起 MediaProjection 必须挂在一个 foregroundServiceType=mediaProjection
 * 的前台服务上，否则 createVirtualDisplay 直接抛 SecurityException。
 */
public class MirrorService extends Service {

    public static final String CH = "ah_mirror";
    public static final int NID = 1401;
    public static final String EX_CODE = "code";
    public static final String EX_DATA = "data";

    public static void start(Context c, int code, Intent data) {
        try {
            Intent i = new Intent(c, MirrorService.class);
            i.putExtra(EX_CODE, code);
            i.putExtra(EX_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            ScreenCap.note("svc-start: " + t.getClass().getSimpleName());
        }
    }

    public static void stop(Context c) {
        try { c.stopService(new Intent(c, MirrorService.class)); } catch (Throwable t) { }
    }

    @Override
    public int onStartCommand(Intent it, int flags, int sid) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        CH, "屏幕镜像", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH)
                    : new Notification.Builder(this);
            b.setContentTitle("屏幕镜像运行中")
                    .setContentText("面板「屏幕」页可以看实时画面")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setOngoing(true);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, b.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NID, b.build());
            }
            if (it != null) {
                int code = it.getIntExtra(EX_CODE, 0);
                Intent data = (Intent) it.getParcelableExtra(EX_DATA);
                if (data != null) ScreenCap.start(this, code, data);
                else ScreenCap.note("svc: 没有收到授权数据");
            }
        } catch (Throwable t) {
            ScreenCap.note("svc: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onDestroy() {
        try { stopForeground(true); } catch (Throwable t) { }
        try { ScreenCap.stop(); } catch (Throwable t) { }
        super.onDestroy();
    }
}
