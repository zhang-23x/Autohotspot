package com.yg.autohotspot;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * 自愈：保证本 App 始终挂在无障碍服务列表里。
 *
 * 为什么要这个类：
 * 华为在开机、清缓存、安全清理时会悄悄把 enabled_accessibility_services
 * 里的第三方条目抹掉，导致 HotspotService 不再被系统绑定，
 * 于是「热点掉了自动点开」的能力消失，而热点机屏幕又坏了没法手动授权。
 *
 * 解决路径：给 App 授予 WRITE_SECURE_SETTINGS（adb pm grant 即可，
 * 属 development 级权限，不需要 root），让它开机后自己把授权写回去。
 * 没有该权限时写入会抛 SecurityException，这里静默吞掉，不影响其他功能。
 */
public final class SelfHeal {

    /** 无障碍服务组件的完整扁平名，必须和 AndroidManifest 里的 service 一致。 */
    static final String SVC = "com.yg.autohotspot/com.yg.autohotspot.HotspotService";

    private SelfHeal() {
    }

    /** 读当前系统设置，判断自己是否已被授权为无障碍服务。 */
    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return false;
        try {
            int on = Settings.Secure.getInt(
                    ctx.getContentResolver(), "accessibility_enabled", 0);
            String list = Settings.Secure.getString(
                    ctx.getContentResolver(), "enabled_accessibility_services");
            boolean inList = list != null && list.contains("com.yg.autohotspot");
            return on == 1 && inList;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 尝试把自己写回无障碍列表。
     *
     * @return true 表示当前已启用（原本就启用也算成功）
     */
    public static boolean ensure(Context ctx) {
        if (ctx == null) return false;
        if (isEnabled(ctx)) return true;
        try {
            String list = Settings.Secure.getString(
                    ctx.getContentResolver(), "enabled_accessibility_services");
            String merged;
            if (list == null || list.trim().isEmpty()) {
                merged = SVC;
            } else if (list.contains("com.yg.autohotspot")) {
                merged = list;
            } else {
                // 多服务之间用冒号分隔
                merged = list + ":" + SVC;
            }
            Settings.Secure.putString(
                    ctx.getContentResolver(), "enabled_accessibility_services", merged);
            Settings.Secure.putInt(
                    ctx.getContentResolver(), "accessibility_enabled", 1);
            Log.i(HotspotService.TAG, "self-heal: accessibility re-armed");
            return true;
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "self-heal skipped: " + t);
            return false;
        }
    }

    /**
     * 开机后系统还在忙着初始化，settings 可能被后续流程覆盖。
     * 所以在若干时间点各试一次，成功即止。
     */
    public static void schedule(final Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                int[] delays = {5000, 12000, 25000, 45000, 90000};
                for (int d : delays) {
                    try {
                        Thread.sleep(d == delays[0] ? delays[0] : d - prev(delays, d));
                    } catch (InterruptedException ignored) {
                    }
                    try {
                        if (ensure(app)) {
                            Log.i(HotspotService.TAG, "self-heal ok at ~" + d + "ms");
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "self-heal");
        th.setDaemon(true);
        th.start();
    }

    private static int prev(int[] arr, int cur) {
        int p = 0;
        for (int a : arr) {
            if (a >= cur) break;
            p = a;
        }
        return p;
    }
}
