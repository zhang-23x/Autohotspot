package com.yg.autohotspot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * 开机后自动开启华为/荣耀「个人热点」。
 *
 * 为什么必须模拟点击：华为把 Settings$WifiApSettingsActivity / HwTetherSettings /
 * Settings$WifiBridgeSettingsActivity 用 huawei.android.permission.HW_SIGNATURE_OR_SYSTEM
 * 保护，普通 App 与 adb shell(uid 2000) 都无法跳转；shell 也缺 TETHER_PRIVILEGED。
 * 唯一免 root 入口是 SystemUI 控制中心里的「个人热点」磁贴。
 *
 * v4 关键修复（针对 v3 实测问题）：
 *  1. 绝不盲目发 BACK。v3 在面板没打开时也发 GLOBAL_ACTION_BACK，结果把前台的
 *     设置页一层层关掉（表现为「页面自己关闭」）。现在只在 panelOpened==true 时收起。
 *  2. 单一作业串行化。v3 的 finish() 会派发重试链，而 onServiceConnected 又重置
 *     jobRunning，导致多条 job 并行（日志里 round 8/2/10/9 交织）。v4 用 running
 *     作为唯一闸门，step() 也检查 running，finish 后所有在途回调自动作废。
 *  3. 优先用手势下滑拉面板（实测 GLOBAL_ACTION_NOTIFICATIONS 在本机常返回 false）。
 */
public class HotspotService extends AccessibilityService {

    static final String TAG = "AutoHotspot";
    private static final int MAX_ROUNDS = 8;

    static volatile HotspotService INSTANCE = null;

    private Handler h;
    private volatile boolean running = false;
    private int round = 0;
    private boolean panelOpened = false;
    private boolean expandTried = false;
    private PowerManager.WakeLock wl;
    /** 作业代号：finish()/runJob() 都会自增，旧作业派生的回调一律作废 */
    private int gen = 0;
    /** 同一时刻只允许一个手势在飞，否则 dispatchGesture 直接返回 false */
    private volatile boolean gesturing = false;

    /* ---- 看门狗：热点掉了就自己补回来，做到「关也关不掉」 ---- */
    private Handler hw = null;
    private long lastWdAttempt = 0;
    private long wdBackoff = 60000L;
    private int wdFails = 0;
    private boolean wdJob = false;
    /** 看门狗发现「磁贴显示已开、但网卡其实没起来」时，强制关一次再开一次 */
    private boolean restartDone = false;
    /** v49：面板里没有热点磁贴时，只允许回落到设置页一次 */
    private boolean settingsTried = false;
    /** v49：设置页里最多往下钻几层，防止在菜单里打转 */
    private int settingsHop = 0;
    /** v49：设置页可能还在转场动画，给两次重试机会 */
    private int settingsMiss = 0;

    /* ---- 改热点名称 / 密码（只能走无障碍点设置页） ---- */
    // apBusy / apMsg 用 static：改完密码系统会重启热点，本服务偶尔被牵连重启，
    // 那时实例字段归零，面板就会永远卡在「正在…」。static 能保住最后结果。
    private static volatile boolean apBusy = false;
    private static volatile long apStartTs = 0;
    private int apStage = 0;
    private String apSsid = "";
    private String apPwd = "";
    private static volatile String apMsg = "空闲";

    // v56 切双卡
    private int simTarget = 0;
    private int simHop = 0;
    private boolean apMoreTried = false;
    private int apRetry = 0;
    private boolean apPwdDone = false;
    private boolean apSsidDone = false;
    private int apRenav = 0;
    /** 当前作业是不是「把热点关掉」（定时关热点用），决定面板里要不要反向点磁贴 */
    private boolean offMode = false;

    static void trigger() {
        final HotspotService s = INSTANCE;
        if (s != null && s.h != null) {
            s.h.post(new Runnable() {
                @Override public void run() { s.runJob("manual"); }
            });
        } else {
            Log.w(TAG, "trigger ignored: service not connected");
        }
    }

    /** 定时/手动关热点：走控制中心磁贴点一下。成功后看门狗不再自动拉回（见 manualOff 锁） */
    /** v56 切默认数据卡。返回 null 表示已下发，否则是失败原因。 */
    public static String switchDataSim(int slot1) {
        final HotspotService s = INSTANCE;
        if (s == null || s.h == null) return "热控服务没在运行，先到设置里打开无障碍";
        if (s.running) return "正有别的操作在跑，等它做完再试";
        int cur = SimInfo.currentSlot(s);
        if (cur == slot1) return "已经在用卡" + slot1 + "了";
        final int subId = SimInfo.subIdOfSlot(s, slot1);
        if (subId <= 0) return "卡" + slot1 + " 没有插卡";

        // 首选：直接写 Settings.Global.multi_sim_data_call（要 WRITE_SECURE_SETTINGS）
        boolean direct = false;
        try {
            direct = Settings.Global.putInt(s.getContentResolver(), "multi_sim_data_call", subId);
        } catch (Throwable t) {
            Log.w(TAG, "sim: 直写 multi_sim_data_call 失败 " + t);
        }
        final boolean useDirect = direct;
        Log.i(TAG, "=== sim job start target=卡" + slot1 + " subId=" + subId + " direct=" + useDirect + " ===");
        s.h.post(new Runnable() {
            @Override public void run() { s.runSimJob(slot1, useDirect); }
        });
        return null;
    }

    static void turnOff() {
        final HotspotService s = INSTANCE;
        if (s != null && s.h != null) {
            s.h.post(new Runnable() {
                @Override public void run() { s.runJob("off"); }
            });
        } else {
            Log.w(TAG, "turnOff ignored: service not connected");
        }
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        Log.i(TAG, "service connected");
        try { PhoneServer.start(this); } catch (Throwable e) { Log.e(TAG, "phone server start failed: " + e); }
        if (h == null) h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() { runJob("boot/connect"); }
        }, 6000L);

        // 看门狗用独立 Handler：finish() 清 job 的回调不会把它一起清掉
        if (hw == null) {
            hw = new Handler(Looper.getMainLooper());
            hw.postDelayed(wdTick, 45000L);
            Log.i(TAG, "watchdog armed (45s first tick, then every 60s)");
        }
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        running = false;
        INSTANCE = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        INSTANCE = null;
        releaseWake();
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    // ---------------------------------------------------------------- job

    private void runSimJob(final int slot1, final boolean direct) {
        if (running) {
            Log.i(TAG, "sim job skipped: another job running");
            return;
        }
        running = true;
        gen++;
        simTarget = slot1;
        simHop = 0;
        if (direct) {
            step(2500L, new Runnable() { public void run() { verifySimDirect(slot1, 0); } });
        } else {
            wakeScreen();
            step(400L, new Runnable() { public void run() { openSimSettings(); } });
        }
    }

    /** 直写 settings 之后轮询确认；20 秒还没变就转无障碍。 */
    private void verifySimDirect(final int slot1, final int tries) {
        if (SimInfo.currentSlot(this) == slot1) {
            Log.i(TAG, "sim: 已切到卡" + slot1 + "（直写生效）");
            finish(true);
            return;
        }
        if (tries < 10) {
            step(2000L, new Runnable() { public void run() { verifySimDirect(slot1, tries + 1); } });
            return;
        }
        Log.w(TAG, "sim: 直写没生效，改走无障碍");
        wakeScreen();
        openSimSettings();
    }

    private void openSimSettings() {
        String[] acts = {"android.settings.DUAL_SIM_SETTINGS", "android.settings.NETWORK_OPERATOR_SETTINGS"};
        for (int i = 0; i < acts.length; i++) {
            try {
                Intent it = new Intent(acts[i]);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                if (getPackageManager().resolveActivity(it, 0) != null) {
                    startActivity(it);
                    Log.i(TAG, "sim: 直达 " + acts[i]);
                    step(2400L, new Runnable() { public void run() { pickSimRow(); } });
                    return;
                }
            } catch (Throwable ignored) { }
        }
        try {
            Intent it = new Intent(Settings.ACTION_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(it);
        } catch (Throwable t) {
            simFail("打不开系统设置");
            return;
        }
        step(2400L, new Runnable() { public void run() { navSimEntry(); } });
    }

    private void navSimEntry() {
        String[] words = {"双卡管理", "双卡与移动网络", "SIM 卡管理", "SIM卡管理", "移动网络", "网络和互联网", "连接与共享"};
        AccessibilityNodeInfo n = findAny(words);
        if (n == null) {
            if (simHop < 2) {
                simHop++;
                Log.i(TAG, "sim: 等入口出现 x" + simHop);
                step(1600L, new Runnable() { public void run() { navSimEntry(); } });
                return;
            }
            simFail("设置里没找到双卡或移动网络入口");
            return;
        }
        Log.i(TAG, "sim: 点入口 " + textOfSafe(n));
        click(n);
        step(2200L, new Runnable() { public void run() { pickSimRow(); } });
    }

    private void pickSimRow() {
        String[] words = simTarget == 1
                ? new String[]{"卡 1", "卡1", "SIM 1", "SIM1", "主卡"}
                : new String[]{"卡 2", "卡2", "SIM 2", "SIM2", "副卡"};
        AccessibilityNodeInfo n = findAny(words);
        if (n != null && click(n)) {
            Log.i(TAG, "sim: 点选 " + textOfSafe(n));
            step(2500L, new Runnable() { public void run() { verifySim(); } });
            return;
        }
        if (simHop < 3) {
            AccessibilityNodeInfo e = findAny(new String[]{"默认移动数据", "默认数据卡", "默认上网卡", "SIM 卡管理", "SIM卡管理", "双卡管理", "数据卡", "移动数据"});
            if (e != null && click(e)) {
                simHop++;
                Log.i(TAG, "sim: 进一层 " + textOfSafe(e));
                step(2000L, new Runnable() { public void run() { pickSimRow(); } });
                return;
            }
        }
        simFail("没找到卡" + simTarget + " 的选择项");
    }

    private void verifySim() {
        int cur = SimInfo.currentSlot(this);
        if (cur == simTarget) {
            Log.i(TAG, "sim: 已切到卡" + simTarget);
            finish(true);
            return;
        }
        if (simHop < 3) {
            AccessibilityNodeInfo ok = findAny(new String[]{"确定", "确认", "切换"});
            if (ok != null && click(ok)) {
                simHop++;
                Log.i(TAG, "sim: 点确认 " + textOfSafe(ok));
                step(2500L, new Runnable() { public void run() { verifySim(); } });
                return;
            }
        }
        simFail("点完了但当前还是卡" + (cur > 0 ? cur : "未知"));
    }

    private void simFail(String why) {
        Log.w(TAG, "sim: " + why);
        finish(false);
    }

    private String textOfSafe(AccessibilityNodeInfo n) {
        if (n == null) return "";
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString();
    }

    private void runJob(String why) {
        if (running) {
            Log.i(TAG, "job already running, skip: " + why);
            return;
        }
        running = true;
        gen++;
        gesturing = false;
        wdJob = "watchdog".equals(why);
        offMode = "off".equals(why);
        restartDone = false;
        settingsTried = false;
        settingsHop = 0;
        settingsMiss = 0;
        round = 0;
        panelOpened = false;
        expandTried = false;
        Log.i(TAG, "=== job start (" + why + ") ===");
        wakeScreen();
        step(1500L, new Runnable() {
            @Override public void run() { openPanel(); }
        });
    }

    private void wakeScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (pm.isInteractive()) {
                Log.i(TAG, "screen already on");
                return;
            }
            if (wl == null) {
                wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE, TAG);
                wl.setReferenceCounted(false);
            }
            wl.acquire(60000L);
            Log.i(TAG, "screen off -> wake lock acquired");
        } catch (Throwable t) {
            Log.e(TAG, "wakeScreen failed: " + t);
        }
    }

    private void releaseWake() {
        try {
            if (wl != null && wl.isHeld()) wl.release();
        } catch (Throwable ignored) { }
    }

    /** 唯一的延迟执行通道：running 为 false 时自动作废 */
    private void step(long ms, final Runnable r) {
        if (h == null || !running) return;
        final int g = gen;
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (!running || g != gen) return;
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.e(TAG, "step error: " + t);
                    finish(false);
                }
            }
        }, ms);
    }

    private void openPanel() {
        round++;
        if (round > MAX_ROUNDS) {
            Log.w(TAG, "give up after " + MAX_ROUNDS + " rounds");
            finish(false);
            return;
        }
        // 已经起来了就别再开面板扰民（开机时系统常常自己就把热点恢复了）
        // 开机任务看到网卡还起着就收工；但「关热点」任务必须照样去点磁贴
        if (!offMode && isTetherUp()) {
            Log.i(TAG, "hotspot already up (iface) -> skip panel");
            finish(true);
            return;
        }
        Log.i(TAG, "round " + round + ": swipe down");
        swipeDown();
    }

    /** 从屏幕顶部下拉拉出控制中心（本机实测可靠） */
    private void swipeDown() {
        if (gesturing) {
            Log.i(TAG, "another gesture in flight, wait");
            step(1200L, new Runnable() {
                @Override public void run() { swipeDown(); }
            });
            return;
        }
        boolean ok = false;
        try {
            Path p = new Path();
            p.moveTo(1000f, 8f);
            p.lineTo(1000f, 1780f);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 400))
                    .build();
            gesturing = true;
            ok = dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) {
                    gesturing = false;
                    step(900L, new Runnable() {
                        @Override public void run() { scan(); }
                    });
                }
                @Override public void onCancelled(GestureDescription d) {
                    gesturing = false;
                    step(900L, new Runnable() {
                        @Override public void run() { scan(); }
                    });
                }
            }, null);
            if (!ok) gesturing = false;
        } catch (Throwable t) {
            gesturing = false;
            Log.e(TAG, "dispatchGesture failed: " + t);
        }
        if (!ok) {
            Log.w(TAG, "gesture rejected, try GLOBAL_ACTION_NOTIFICATIONS");
            try { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); } catch (Throwable ignored) { }
            step(2500L, new Runnable() {
                @Override public void run() { scan(); }
            });
        }
    }

    private void scan() {
        AccessibilityNodeInfo tile = findSmart(UiFinder.T_TILE, ApProfile.TILE_WORDS);
        if (tile != null) {
            panelOpened = true;
            String d = describe(tile);
            if (d.contains("已开启")) {
                if (offMode) {
                    Log.i(TAG, "off job: tile is ON -> tap to turn OFF");
                    if (!click(tile)) {
                        tapTile();
                        return;
                    }
                    step(2600L, new Runnable() {
                        @Override public void run() { collapseQuietly(); finish(true); }
                    });
                    return;
                }
                // 磁贴说开着，但看门狗是因为「网卡没起来」才发起的 → 强制关一次再开
                if (wdJob && !restartDone) {
                    restartDone = true;
                    Log.w(TAG, "watchdog: tile says ON but tether is DOWN -> force restart (off)");
                    click(tile);
                    step(2500L, new Runnable() {
                        @Override public void run() { scan(); }
                    });
                    return;
                }
                Log.i(TAG, "hotspot already ON (" + d + ")");
                collapseQuietly();
                finish(true);
                return;
            }
            if (offMode) {
                Log.i(TAG, "off job: tile is already OFF -> done");
                collapseQuietly();
                finish(true);
                return;
            }

            Log.i(TAG, "tap hotspot tile (" + d + ")");
            if (!click(tile)) {
                Log.w(TAG, "node click failed, coordinate tap");
                tapTile();
                return;
            }
            verifyLater();
            return;
        }

        AccessibilityNodeInfo expand = findAny(ApProfile.EXPAND_WORDS);
        if (expand != null) {
            panelOpened = true;
            if (!expandTried) {
                expandTried = true;
                Log.i(TAG, "expand quick settings");
                click(expand);
                step(1200L, new Runnable() {
                    @Override public void run() { scan(); }
                });
                return;
            }
        }

        // v49：快捷设置里压根没有热点磁贴的机型（不少定制 ROM 就这样）
        // -> 不再死磕面板，改走「设置 -> 热点页」，用 Intent 直达 + 自动搜索找开关
        if (!settingsTried && round >= 2) {
            settingsTried = true;
            Log.i(TAG, "panel has no hotspot tile -> fallback to settings page");
            collapseQuietly();
            boolean opened = openSettingsApPage() || openSettingsHome();
            if (opened) {
                step(4000L, new Runnable() {
                    @Override public void run() { clickSwitchOnPage(); }
                });
                return;
            }
            Log.w(TAG, "settings fallback also failed, keep retrying panel");
        }
        Log.w(TAG, "panel not ready, retry");
        collapseQuietly();
        step(2200L, new Runnable() {
            @Override public void run() { openPanel(); }
        });
    }

    /** 坐标兜底：1080x2400 展开态「个人热点」磁贴中心 */
    private void tapTile() {
        if (gesturing) {
            step(1200L, new Runnable() {
                @Override public void run() { tapTile(); }
            });
            return;
        }
        try {
            Path p = new Path();
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics(); p.moveTo(dm.widthPixels * 0.6056f, dm.heightPixels * 0.6492f);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 120))
                    .build();
            gesturing = true;
            boolean ok = dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) {
                    gesturing = false;
                    verifyLater();
                }
                @Override public void onCancelled(GestureDescription d) {
                    gesturing = false;
                    verifyLater();
                }
            }, null);
            if (!ok) {
                gesturing = false;
                Log.w(TAG, "tapTile gesture rejected");
                verifyLater();
            }
        } catch (Throwable t) {
            gesturing = false;
            Log.e(TAG, "tapTile failed: " + t);
            finish(false);
        }
    }

    private void verifyLater() {
        step(2500L, new Runnable() {
            @Override public void run() {
                AccessibilityNodeInfo tile = findSmart(UiFinder.T_TILE, ApProfile.TILE_WORDS);
                String d = tile == null ? "" : describe(tile);
                boolean byTile = d.contains("已开启");
                boolean byIface = isTetherUp();
                boolean on = byIface || byTile;
                Log.i(TAG, "verify: tile=" + (d.length() == 0 ? "<gone>" : d)
                        + " ifaceUp=" + byIface + " -> " + on);
                collapseQuietly();
                finish(on);
            }
        });
    }

    /**
     * 只有确实打开过面板才发 BACK。
     * v3 无条件发 BACK，把用户正在用的设置页一层层关掉，是「页面自己关闭」的真因。
     */
    private void collapseQuietly() {
        if (!panelOpened) return;
        try { performGlobalAction(GLOBAL_ACTION_BACK); } catch (Throwable ignored) { }
        panelOpened = false;
    }

    private void finish(boolean success) {
        running = false;
        offMode = false;
        gen++;
        gesturing = false;
        if (h != null) h.removeCallbacksAndMessages(null);
        releaseWake();
        // v49：如果这次是从设置页走的，收工后回桌面，别把用户丢在设置里
        if (settingsTried) {
            try { performGlobalAction(GLOBAL_ACTION_HOME); } catch (Throwable ignored) { }
        }
        // v56 切卡流程收工也回桌面
        if (simTarget > 0) {
            simTarget = 0;
            simHop = 0;
            try { performGlobalAction(GLOBAL_ACTION_HOME); } catch (Throwable ignored) { }
        }

        if (wdJob) {
            if (success) {
                wdFails = 0;
                wdBackoff = 60000L;
                Log.i(TAG, "watchdog: recovery OK, backoff reset");
            } else {
                wdFails++;
                wdBackoff = Math.min(wdBackoff * 2L, 600000L);
                Log.w(TAG, "watchdog: recovery failed x" + wdFails + ", backoff " + wdBackoff + "ms");
            }
        }
        Log.i(TAG, "=== job finished, success=" + success + " ===");
    }

    // ------------------------------------------------------------ watchdog

    /** 每 60 秒看一次热点还在不在；掉了就自己补回来（这就是「关也关不掉」） */
    private final Runnable wdTick = new Runnable() {
        @Override public void run() {
            try {
                boolean up = isTetherUp();
                long now = System.currentTimeMillis();
                if (up) {
                    if (wdFails != 0) Log.i(TAG, "watchdog: tether UP again");
                    wdFails = 0;
                    wdBackoff = 60000L;
                } else if (Panel.manualOff(HotspotService.this)) {
                    // 定时关热点之后停在「手动关」状态，别再自己拉回来
                    Log.i(TAG, "watchdog: manual-off lock held -> skip");
                } else if (hasWifiSta()) {
                    // WiFi 占了 wlan0，热点根本起不来；这时开面板纯属扰民，直接跳过
                    Log.i(TAG, "watchdog: tether DOWN but WiFi STA holds wlan0 -> skip");
                } else if (!running && now - lastWdAttempt >= wdBackoff) {
                    lastWdAttempt = now;
                    Log.w(TAG, "watchdog: tether DOWN (" + lastProbeError + ") -> re-run job (backoff=" + wdBackoff + "ms)");
                    runJob("watchdog");
                } else {
                    Log.i(TAG, "watchdog: tether DOWN, waiting (cooldown)");
                }
            } catch (Throwable t) {
                Log.e(TAG, "watchdog error: " + t);
            }
            if (hw != null) hw.postDelayed(this, 60000L);
        }
    };

    /** 网卡状态位 */
    static final int IF_NONE = 0, IF_AP = 1, IF_STA = 2;
    /** 探针失败的最近一次原因，只用于日志诊断 */
    static volatile String lastProbeError = "";

    /**
     * 扫一遍 wlan* 网卡的 IPv4：
     *  - 出现 192.168.43.1 → 华为热点自己拿的地址，AP 起来了
     *  - 出现别的 IPv4    → WiFi 客户端（STA）连着
     * 实测：本 App 自己就能看到 192.168.43.1（v9 的 hasWifiSta 因此误报），
     * 既不需要权限，也不需要 exec（SELinux 会挡 app 调 sh/ip，实测输出为空）。
     */
    private static int ifaceState() {
        int st = IF_NONE;
        try {
            java.util.Enumeration<java.net.NetworkInterface> e =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                java.net.NetworkInterface ni = e.nextElement();
                String n = ni.getName();
                if (n == null) continue;
                // v43：热点不一定挂在 wlan* 上。vivo / 多数厂商是 ap0（如 192.168.242.138），
                // 老机型（华为 / 小米）才是 wlan0 直接拿 192.168.43.1。
                boolean apName = ApProfile.isApIface(n);
                boolean wlanName = n.startsWith("wlan");
                if (!apName && !wlanName) continue;
                if (!ni.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (!(a instanceof java.net.Inet4Address)) continue;
                    if (apName || ApProfile.apIp().equals(a.getHostAddress())) st |= IF_AP;
                    else st |= IF_STA;
                }
            }
        } catch (Throwable t) {
            lastProbeError = "nif: " + t.getClass().getSimpleName();
        }
        return st;
    }

    /** 热点在不在：网卡地址优先，套接字探针兜底 */
    private static boolean isTetherUp() {
        if ((ifaceState() & IF_AP) != 0) return true;
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress(ApProfile.apIp(), 53), 1200);
            return true;
        } catch (java.net.ConnectException ce) {
            String m = ce.getMessage() == null ? "" : ce.getMessage().toLowerCase();
            return m.contains("refused");
        } catch (Throwable t) {
            lastProbeError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (Throwable ignored) { }
        }
    }

    /** WiFi 占着 wlan*（有非热点网段的 IPv4）→ 热点起不来，这时别开面板扰民 */
    private static boolean hasWifiSta() {
        int st = ifaceState();
        return (st & IF_STA) != 0 && (st & IF_AP) == 0;
    }

    // ------------------------------------------------------------- nodes

    private String describe(AccessibilityNodeInfo n) {
        CharSequence d = n.getContentDescription();
        CharSequence t = n.getText();
        String a = d == null ? "" : d.toString();
        String b = t == null ? "" : t.toString();
        return a.length() > 0 ? a : b;
    }

    /** v42 按候选词表依次找：词表第一个是华为原本的写法，老机型行为不变 */
    private AccessibilityNodeInfo findAny(String[] words) {
        if (words == null) return null;
        for (int i = 0; i < words.length; i++) {
            AccessibilityNodeInfo n = find(words[i]);
            if (n != null) return n;
        }
        return null;
    }

    /* ================= v49 自学习搜索 =================
     * 三层：这台机器学到的词 -> 内置词表 -> 全页打分。
     * 打分命中后把文本记住，下次走第一层，所以换机不用再补词表。
     */

    private AccessibilityNodeInfo findSmart(int type, String[] builtin) {
        String[] words = UiFinder.merge(this, type, builtin);
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w == null || w.length() == 0) continue;
            AccessibilityNodeInfo n = find(w);
            if (n != null) {
                UiFinder.learn(this, type, w);
                return n;
            }
        }
        AccessibilityNodeInfo best = bestInAllWindows(type, 55);
        if (best != null) {
            String t = UiFinder.textOf(best);
            Log.i(TAG, "smart[" + type + "]: 词表没命中，打分选中 [" + t + "]");
            if (t.length() > 0 && t.length() <= 20) UiFinder.learn(this, type, t);
            return best;
        }
        return null;
    }

    /** 在所有窗口里找该类型的最高分节点 */
    private AccessibilityNodeInfo bestInAllWindows(int type, int threshold) {
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            for (int i = 0; wins != null && i < wins.size(); i++) {
                AccessibilityWindowInfo w = wins.get(i);
                if (w == null) continue;
                AccessibilityNodeInfo b = UiFinder.best(w.getRoot(), type, threshold);
                if (b != null) return b;
            }
        } catch (Throwable ignored) { }
        try {
            return UiFinder.best(getRootInActiveWindow(), type, threshold);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean safeChecked(AccessibilityNodeInfo n) {
        try { return n != null && n.isChecked(); } catch (Throwable t) { return false; }
    }

    /**
     * v49 在设置页里找热点开关并点它。
     *   A. 已经在热点页 -> 直接有「个人热点 + 开关」这一行
     *   B. 还停在设置首页 -> 只有「移动网络 / 更多连接」这种入口，先点进去再找
     */
    private void clickSwitchOnPage() {
        if (!running) return;
        if (++settingsHop > 8) {
            Log.w(TAG, "settings: 钻了 " + settingsHop + " 层还没找到，放弃");
            finish(false);
            return;
        }
        AccessibilityNodeInfo row = bestInAllWindows(UiFinder.T_TILE, 60);
        if (row != null) {
            String t = UiFinder.textOf(row);
            AccessibilityNodeInfo sw = UiFinder.firstCheckable(row, 0);
            AccessibilityNodeInfo target = sw != null ? sw : row;
            String d = describe(target);
            Log.i(TAG, "settings: 热点开关 [" + t + "] desc=[" + d + "] hop=" + settingsHop);
            UiFinder.learn(this, UiFinder.T_TILE, t);
            boolean want = !offMode;
            boolean isOn = d.contains("已开启") || d.contains("开启")
                    || d.contains("已打开") || safeChecked(sw);
            if (isOn == want) {
                Log.i(TAG, "settings: 状态已经是 want=" + want + "，不用点");
                finish(true);
                return;
            }
            if (click(target)) {
                step(3500L, new Runnable() {
                    @Override public void run() { verifyLater(); }
                });
                return;
            }
        }
        AccessibilityNodeInfo entry = bestInAllWindows(UiFinder.T_HOME, 55);
        if (entry != null) {
            String t = UiFinder.textOf(entry);
            Log.i(TAG, "settings: 点入口 [" + t + "] hop=" + settingsHop);
            UiFinder.learn(this, UiFinder.T_HOME, t);
            if (click(entry)) {
                step(2600L, new Runnable() {
                    @Override public void run() { clickSwitchOnPage(); }
                });
                return;
            }
        }
        // 页面可能还在转场动画里，给两次机会再判死
        if (settingsMiss < 2) {
            settingsMiss++;
            settingsHop--;
            Log.i(TAG, "settings: 这一页还没有目标，等 1.6s 再试（第 " + settingsMiss + " 次）");
            step(1600L, new Runnable() {
                @Override public void run() { clickSwitchOnPage(); }
            });
            return;
        }
        Log.w(TAG, "settings: 重试 " + settingsMiss + " 次仍没找到开关或入口，放弃");
        finish(false);
    }
    private AccessibilityNodeInfo find(String needle) {
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            for (int i = 0; i < wins.size(); i++) {
                AccessibilityWindowInfo w = wins.get(i);
                AccessibilityNodeInfo root = w.getRoot();
                AccessibilityNodeInfo r = search(root, needle, 0);
                if (r != null) return r;
            }
        } catch (Throwable ignored) { }
        try {
            return search(getRootInActiveWindow(), needle, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private AccessibilityNodeInfo search(AccessibilityNodeInfo n, String needle, int depth) {
        if (n == null || depth > 32) return null;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        try {
            if ((t != null && t.toString().contains(needle))
                    || (d != null && d.toString().contains(needle))) {
                return n;
            }
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) {
                AccessibilityNodeInfo r = search(n.getChild(i), needle, depth + 1);
                if (r != null) return r;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 8 && cur != null; i++) {
            try {
                if (cur.isClickable()
                        && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
                cur = cur.getParent();
            } catch (Throwable ignored) { }
        }
        try { return n.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
        catch (Throwable t) { return false; }
    }
    /**
     * 弹电源菜单并点「重启」。
     *
     * 无 root 的普通应用拿不到 REBOOT 权限，但无障碍服务可以走 UI 这条路：
     * 先 performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) 弹出电源菜单，
     * 再按文字找「重启」节点点掉。全程异步，返回是否成功弹出菜单。
     */
    public boolean rebootViaPowerMenu() {
        boolean shown;
        try {
            shown = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
        } catch (Throwable t) {
            Log.e(TAG, "power dialog failed: " + t);
            return false;
        }
        if (!shown) return false;

        Thread th = new Thread(new Runnable() {
            public void run() {
                String[] words = {"重启", "重新启动", "Restart", "Reboot"};
                for (int attempt = 0; attempt < 6; attempt++) {
                    try { Thread.sleep(700); } catch (Throwable ignored) { }
                    for (int i = 0; i < words.length; i++) {
                        AccessibilityNodeInfo node = find(words[i]);
                        if (node != null && click(node)) {
                            Log.i(TAG, "reboot tapped: " + words[i]);
                            return;
                        }
                    }
                }
                Log.w(TAG, "reboot word not found in power dialog");
            }
        }, "reboot-via-menu");
        th.setDaemon(true);
        th.start();
        return true;
    }

    /* ================= 热点改名 / 改密码 =================
     * EMUI 把 Settings$WifiApSettingsActivity、HwTetherSettings 这类页面用
     * huawei.android.permission.HW_SIGNATURE_OR_SYSTEM 锁住：普通 App 跳不进去，
     * adb shell(uid 2000) 也跳不进去；WifiManager.setWifiApConfiguration 一样要
     * 系统签名，Device Owner 也给不了这个权限。
     * 所以免 root 唯一的门是：进「设置」主页（这个 Activity 是公开导出的）→
     * 无障碍一路点到个人热点 → 改输入框 → 保存。全程走节点操作，不认坐标，
     * EMUI 改版时只要调下面几个关键词。
     */

    /** 给 HTTP 层调用。返回非 null 表示没开始，串里是原因。 */
    static String changeAp(String ssid, String pwd) {
        HotspotService s = INSTANCE;
        if (s == null) return "无障碍服务没在运行，改不了热点";
        String n = ssid == null ? "" : ssid.trim();
        String p = pwd == null ? "" : pwd.trim();
        if (n.length() == 0 && p.length() == 0) return "名称和密码不能都空着";
        if (n.length() > 32) return "热点名称最长 32 个字符";
        if (p.length() > 0) {
            if (p.length() < 8) return "WPA2 密码至少 8 位";
            if (p.length() > 63) return "密码最长 63 位";
        }
        if (apBusy) return "上一次还在走，等几秒再试";
        apBusy = true;
        apMsg = "正在打开设置…";
        apStartTs = SystemClock.elapsedRealtime();
        s.runApJob(n, p);
        return null;
    }

    static String apState() {
        stale();
        return apMsg;
    }

    static boolean apBusyNow() {
        stale();
        return apBusy;
    }

    /** 服务被系统重启过时，上一次流程已经断了，别让面板卡在「正在…」。 */
    private static boolean stale() {
        if (apBusy && apStartTs > 0
                && SystemClock.elapsedRealtime() - apStartTs > 75000L) {
            apBusy = false;
            apMsg = "上一次流程中途断了，去设置页看一眼密码改没改";
            return true;
        }
        return false;
    }

    /** 直接打开热点设置页（标准 action）。本机不响应时返回 false。 */
    private boolean openSettingsApPage() {
        try {
            Intent i = new Intent(ApProfile.INTENT_TETHER);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (getPackageManager().resolveActivity(i, 0) == null) {
                Log.i(TAG, "ap: 本机不响应 TETHER_SETTINGS，回落设置首页");
                return false;
            }
            startActivity(i);
            Log.i(TAG, "ap: 已用 TETHER_SETTINGS 直开热点页");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "ap: TETHER_SETTINGS 不可用 " + t);
            return false;
        }
    }

    /** 回落到设置首页（华为等不响应 TETHER_SETTINGS 的机器走这条）。 */
    private boolean openSettingsHome() {
        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            Log.i(TAG, "ap: 已打开设置首页");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "ap: open settings failed: " + t);
            return false;
        }
    }

    private void runApJob(final String ssid, final String pwd) {
        apSsid = ssid;
        apPwd = pwd;
        apStage = 0;
        apMoreTried = false;
        apRetry = 0;
        apPwdDone = false;
        apSsidDone = false;
        apRenav = 0;
        running = true;
        gen++;
        wakeScreen();
        Log.i(TAG, "=== ap job start ssid=[" + ssid + "] pwdLen=" + pwd.length() + " ===");
        // v43：先用标准 Intent 直开热点页。vivo / 原生 / 多数 Android 8+ 都响应，
        // 能跳过「设置首页 → 菜单 → 热点」两级点击；华为不响应这个 action，自动回落老路。
        if (!openSettingsApPage() && !openSettingsHome()) {
            apFinish("打不开系统设置");
            return;
        }
        apNext(2600L);
    }

    private void apNext(long ms) {
        final int g = gen;
        step(ms, new Runnable() {
            @Override public void run() {
                if (g != gen) return;
                apTick();
            }
        });
    }

    private void apFinish(String msg) {
        apMsg = msg;
        apBusy = false;
        Log.i(TAG, "ap job done: " + msg);
        try { performGlobalAction(GLOBAL_ACTION_HOME); } catch (Throwable ignored) { }
        // 热点提交后系统会自己重启一次，先让开，别立刻抢屏幕去点面板
        final int g = gen;
        step(6000L, new Runnable() {
            @Override public void run() {
                if (g != gen) return;
                running = false;
            }
        });
    }

    private void apDone() {
        StringBuilder sb = new StringBuilder("已提交");
        if (apSsid.length() > 0) sb.append(" · 名称 ").append(apSsid);
        if (apPwd.length() > 0) sb.append(" · 密码已更新");
        sb.append("，热点会重启一次，已连设备要重连");
        apFinish(sb.toString());
    }

    /** 找不到目标时先重试几次，避免页面还没加载完就被判死。返回 true 表示已安排重试。 */
    private boolean apRetryOr(String why) {
        if (apRetry < 5) {
            apRetry++;
            Log.i(TAG, "ap: " + why + "，第 " + apRetry + " 次重试");
            apNext(1400L);
            return true;
        }
        apFinish(why);
        return false;
    }

    /** 当前最前面的窗口是不是系统设置（冷启动要好几秒，不能一上来就下结论）。 */
    private boolean onSettings() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null) return false;
            CharSequence p = r.getPackageName();
            return p != null && "com.android.settings".contentEquals(p);
        } catch (Throwable t) {
            return false;
        }
    }

    private void apTick() {
        Log.i(TAG, "ap tick stage=" + apStage);
        switch (apStage) {
            case 0: {
                if (!onSettings()) {
                    apRetryOr("设置页面还没出现");
                    return;
                }
                // v43 同页模式：vivo 等 ROM 的「热点配置」整页有两个输入框（名称、密码），
                // 一次填完点「完成」，不像华为那样分两个弹窗。先认出来走快路。
                List<AccessibilityNodeInfo> es0 = editables();
                if (es0.size() >= 2) {
                    Log.i(TAG, "ap: 检测到同页双输入框（共 " + es0.size() + " 个）");
                    apRetry = 0;
                    apStage = 20;
                    apNext(400L);
                    return;
                }
                // v43 配置入口：vivo 热点页把名称/密码收在「热点配置」这一行，点进去才有输入框
                AccessibilityNodeInfo cfg = findSmart(UiFinder.T_CONFIG, ApProfile.CONFIG_WORDS);
                if (cfg != null) {
                    apRetry = 0;
                    Log.i(TAG, "ap: 点配置入口 -> " + describe(cfg));
                    click(cfg);
                    apStage = 21;
                    apNext(1800L);
                    return;
                }
                // 设置可能仍停在上次的「个人热点」子页，那样首页条目根本不存在，
                // 先认出来直接往下走，省一次来回导航。
                if (findSmart(UiFinder.T_SSID, ApProfile.SSID_WORDS) != null && findSmart(UiFinder.T_PWD, ApProfile.PWD_WORDS) != null) {
                    Log.i(TAG, "ap: 已经在热点页，跳过首页导航");
                    apRetry = 0;
                    apStage = 2;
                    apNext(500L);
                    return;
                }
                AccessibilityNodeInfo n = findSmart(UiFinder.T_HOME, ApProfile.SETTING_HOME_WORDS);
                /* v42: SETTING_HOME_WORDS 已含各厂商措辞 */
                /* v42 */
                if (n == null) { apRetryOr("设置首页找不到「移动网络」入口"); return; }
                apRetry = 0;
                Log.i(TAG, "ap: tap -> " + describe(n));
                click(n);
                apStage = 1;
                apNext(1600L);
                return;
            }
            case 1: {
                AccessibilityNodeInfo n = findSmart(UiFinder.T_TILE, ApProfile.TILE_WORDS);
                /* v42: TILE_WORDS 末位就是「热点」，不必二次兜底 */
                if (n == null) { apRetryOr("这一页找不到「个人热点」"); return; }
                apRetry = 0;
                Log.i(TAG, "ap: tap -> " + describe(n));
                click(n);
                apStage = 2;
                apNext(1700L);
                return;
            }
            case 2: {
                // 顺序很关键：先密码，后名称。
                // 改完任何一项热点都会重启、设置页跟着重建，所以「最后一次改动」必须放最后，
                // 改完立刻收工，不再回头去找第二个控件。
                if (!apPwdDone && apPwd.length() > 0) {
                    AccessibilityNodeInfo n = findSmart(UiFinder.T_PWD, ApProfile.PWD_WORDS);
                    if (n == null) { apMiss("热点页找不到「密码」那一行"); return; }
                    apRetry = 0;
                    Log.i(TAG, "ap: tap 密码行");
                    click(n);
                    apStage = 3;
                    apNext(1500L);
                } else {
                    apStage = 4;
                    apNext(400L);
                }
                return;
            }
            case 3: {
                List<AccessibilityNodeInfo> es = editables();
                if (es.isEmpty()) { apMiss("密码对话框里没有输入框"); return; }
                apRetry = 0;
                boolean ok = setText(es.get(0), apPwd);
                Log.i(TAG, "ap: pwdOk=" + ok + " len=" + apPwd.length());
                if (!ok) { apFinish("密码写不进去"); return; }
                if (!tapOk()) { apFinish("密码对话框点不到「确定」"); return; }
                apPwdDone = true;
                apStage = 4;
                apNext(2600L);
                return;
            }
            case 4: {
                if (!apSsidDone && apSsid.length() > 0) {
                    AccessibilityNodeInfo n = findSmart(UiFinder.T_SSID, ApProfile.SSID_WORDS);
                    if (n == null) { apMiss("热点页找不到「设备名称」那一行"); return; }
                    apRetry = 0;
                    Log.i(TAG, "ap: tap 设备名称行");
                    click(n);
                    apStage = 5;
                    apNext(1500L);
                } else {
                    apStage = 7;
                    apNext(300L);
                }
                return;
            }
            case 5: {
                List<AccessibilityNodeInfo> es = editables();
                if (es.isEmpty()) { apMiss("设备名称对话框里没有输入框"); return; }
                apRetry = 0;
                boolean ok = setText(es.get(0), apSsid);
                Log.i(TAG, "ap: ssidOk=" + ok + " -> " + apSsid);
                if (!ok) { apFinish("设备名称写不进去"); return; }
                if (!tapOk()) { apFinish("设备名称对话框点不到「确定」"); return; }
                apSsidDone = true;
                apStage = 7;
                apNext(600L);
                return;
            }
            case 20: {
                // 同页模式：vivo / 部分 ROM 把名称和密码放在同一页，第 1 框名称、第 2 框密码
                List<AccessibilityNodeInfo> es = editables();
                if (es.size() < 2) { apMiss("配置页输入框不足两个"); return; }
                apRetry = 0;
                boolean okSsid = apSsid.length() == 0 || setText(es.get(0), apSsid);
                boolean okPwd = apPwd.length() == 0 || setText(es.get(1), apPwd);
                Log.i(TAG, "ap: 同页写入 ssid=" + okSsid + " pwd=" + okPwd);
                if (!okSsid || !okPwd) { apFinish("输入框写不进去"); return; }
                if (!tapOk()) { apFinish("配置页点不到「完成」"); return; }
                apSsidDone = true;
                apPwdDone = true;
                apStage = 7;
                apNext(2600L);
                return;
            }
            case 21: {
                // 刚点完「热点配置」入口，等页面加载后回到 stage 0 重新判断
                apStage = 0;
                apNext(400L);
                return;
            }
            default: {
                apDone();
                return;
            }
        }
    }

    /**
     * 找控件落空：先等两轮（页面常常慢一拍），还不行就整页重新导航一遍。
     * 改完一项后设置页会重建，「密码」「设备名称」这些行会短暂消失，
     * 光靠等是等不回来的，必须退回去重新点一次。
     */
    private boolean apMiss(String why) {
        if (apRetry < 2) {
            apRetry++;
            Log.i(TAG, "ap: " + why + "，第 " + apRetry + " 次等待");
            apNext(1800L);
            return true;
        }
        apRenav(why);
        return true;
    }

    private void apRenav(String why) {
        if (apRenav >= 3) { apFinish(why); return; }
        apRenav++;
        apRetry = 0;
        Log.i(TAG, "ap: " + why + "，重新导航第 " + apRenav + " 次");
        if (!openSettingsApPage()) openSettingsHome();
        apStage = 0;
        apNext(2800L);
    }

    private List<AccessibilityNodeInfo> editables() {
        List<AccessibilityNodeInfo> out = new java.util.ArrayList<AccessibilityNodeInfo>();
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            for (int i = 0; i < wins.size(); i++) {
                collectEditable(wins.get(i).getRoot(), out, 0);
            }
        } catch (Throwable ignored) { }
        if (out.isEmpty()) {
            try { collectEditable(getRootInActiveWindow(), out, 0); } catch (Throwable ignored) { }
        }
        try {
            final java.util.Map<AccessibilityNodeInfo, Integer> top =
                    new java.util.IdentityHashMap<AccessibilityNodeInfo, Integer>();
            for (int i = 0; i < out.size(); i++) {
                android.graphics.Rect r = new android.graphics.Rect();
                out.get(i).getBoundsInScreen(r);
                top.put(out.get(i), r.top);
            }
            java.util.Collections.sort(out, new java.util.Comparator<AccessibilityNodeInfo>() {
                public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                    Integer x = top.get(a), y = top.get(b);
                    int i = x == null ? 0 : x.intValue();
                    int j = y == null ? 0 : y.intValue();
                    return i - j;
                }
            });
        } catch (Throwable ignored) { }
        return out;
    }

    private void collectEditable(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 32) return;
        try {
            CharSequence cls = n.getClassName();
            boolean isEdit = n.isEditable() || (cls != null && cls.toString().contains("EditText"));
            if (isEdit) {
                boolean dup = false;
                for (int i = 0; i < out.size(); i++) {
                    if (out.get(i).equals(n)) { dup = true; break; }
                }
                if (!dup) out.add(n);
            }
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) collectEditable(n.getChild(i), out, depth + 1);
        } catch (Throwable ignored) { }
    }

    private boolean setText(AccessibilityNodeInfo n, String text) {
        try {
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        } catch (Throwable t) {
            Log.w(TAG, "setText failed: " + t);
            return false;
        }
    }

    /** 对话框的「确定」。EMUI 这个按钮是 Button，文本就是「确定」，clickable=true */
    /** 对话框/页面上的确认按钮。先按词表找，找不到就猜「右上角那个可点按钮」。 */
    private boolean tapOk() {
        for (int i = 0; i < ApProfile.OK_WORDS.length; i++) {
            AccessibilityNodeInfo n = find(ApProfile.OK_WORDS[i]);
            if (n != null && click(n)) {
                Log.i(TAG, "ap: tapped " + ApProfile.OK_WORDS[i]);
                return true;
            }
        }
        // v43 兜底：各厂商的「完成/保存」几乎都在右上，「取消」在左上。
        // 词表漏了就靠位置猜，比直接失败强。
        List<AccessibilityNodeInfo> cands = new java.util.ArrayList<AccessibilityNodeInfo>();
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            for (int i = 0; i < wins.size(); i++) {
                collectOkCandidates(wins.get(i).getRoot(), cands, 0);
            }
        } catch (Throwable ignored) { }
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < cands.size(); i++) {
            AccessibilityNodeInfo c = cands.get(i);
            android.graphics.Rect r = new android.graphics.Rect();
            c.getBoundsInScreen(r);
            if (r.width() <= 0) continue;
            // 分数越小越像「完成」：靠右、靠上
            int score = (2000 - r.centerX()) + r.centerY() * 3;
            if (score < bestScore) { bestScore = score; best = c; }
        }
        if (best != null && click(best)) {
            Log.i(TAG, "ap: 兜底点右上按钮 -> " + describe(best));
            return true;
        }
        return false;
    }

    private void collectOkCandidates(AccessibilityNodeInfo n,
                                     List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 32) return;
        try {
            if (n.isClickable()) {
                CharSequence t = n.getText();
                String s = t == null ? "" : t.toString();
                boolean bad = s.contains("取消") || s.contains("返回")
                        || s.contains("Cancel") || s.contains("关闭") || s.contains("上一步");
                if (!bad && s.length() > 0) {
                    boolean dup = false;
                    for (int i = 0; i < out.size(); i++) { if (out.get(i).equals(n)) { dup = true; break; } }
                    if (!dup) out.add(n);
                }
            }
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) collectOkCandidates(n.getChild(i), out, depth + 1);
        } catch (Throwable ignored) { }
    }
}
