package com.yg.autohotspot;

import android.content.Context;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.util.List;

/**
 * 双卡信息。
 *
 * 只做三件事：
 *   1. 读出每个卡槽插没插卡、是哪家运营商；
 *   2. 读出当前默认数据卡在哪个槽；
 *   3. 在 Device Owner 状态下把 READ_PHONE_STATE 授给自己（否则读不到双卡列表）。
 *
 * 注意：读 SubscriptionManager 需要 READ_PHONE_STATE（API 29 起是运行时权限）。
 * 普通安装的手机会授不上，这时 slots 里 has 全是 false，页面上的卡1/卡2 会变灰，
 * 但「当前走哪张卡」仍能从 Settings.Global 里读到一部分。
 */
public class SimInfo {

    /** Device Owner 下自动授予 READ_PHONE_STATE。重复调用无副作用。 */
    static void ensurePermission(Context c) {
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager)
                    c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null || !dpm.isDeviceOwnerApp(c.getPackageName())) return;
            android.content.ComponentName admin =
                    new android.content.ComponentName(c, AdminReceiver.class);
            String perm = android.Manifest.permission.READ_PHONE_STATE;
            int st = dpm.getPermissionGrantState(admin, c.getPackageName(), perm);
            if (st != android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                boolean ok = dpm.setPermissionGrantState(admin, c.getPackageName(), perm,
                        android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                android.util.Log.i(HotspotService.TAG, "sim: grant READ_PHONE_STATE -> " + ok);
            }
        } catch (Throwable t) {
            android.util.Log.w(HotspotService.TAG, "sim: grant failed " + t);
        }
    }

    /** 卡槽总数（含没插卡的）。取不到按 2 处理。 */
    static int slotCount(Context c) {
        int n = 0;
        try {
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                try {
                    n = tm.getActiveModemCount();
                } catch (Throwable t) {
                    n = tm.getPhoneCount();
                }
            }
        } catch (Throwable ignored) { }
        if (n <= 0) n = 2;
        if (n > 4) n = 4;
        return n;
    }

    private static List<SubscriptionInfo> subs(Context c) {
        try {
            SubscriptionManager sm = (SubscriptionManager) c.getSystemService(
                    Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return null;
            return sm.getActiveSubscriptionInfoList();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前默认数据卡所在槽位（1 起），未知返回 0。 */
    public static int currentSlot(Context c) {
        List<SubscriptionInfo> list = subs(c);
        int defSub = -999;
        try {
            SubscriptionManager sm = (SubscriptionManager) c.getSystemService(
                    Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm != null) defSub = sm.getDefaultDataSubscriptionId();
        } catch (Throwable ignored) { }
        if (list != null && defSub > 0) {
            for (SubscriptionInfo si : list) {
                if (si.getSubscriptionId() == defSub) return si.getSimSlotIndex() + 1;
            }
        }
        // 兜底：multi_sim_data_call 存的是 subId
        try {
            String g = android.provider.Settings.Global.getString(
                    c.getContentResolver(), "multi_sim_data_call");
            if (g != null && g.trim().length() > 0) {
                int sid = Integer.parseInt(g.trim());
                if (list != null) {
                    for (SubscriptionInfo si : list) {
                        if (si.getSubscriptionId() == sid) return si.getSimSlotIndex() + 1;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    /** 槽位对应的 subId（写 multi_sim_data_call 要用它）。没有卡返回 -1。 */
    public static int subIdOfSlot(Context c, int slot1) {
        List<SubscriptionInfo> list = subs(c);
        if (list == null) return -1;
        for (SubscriptionInfo si : list) {
            if (si.getSimSlotIndex() == (slot1 - 1)) return si.getSubscriptionId();
        }
        return -1;
    }

    /** 该槽能不能用（插了卡且被系统识别）。 */
    public static boolean hasSim(Context c, int slot1) {
        List<SubscriptionInfo> list = subs(c);
        if (list == null) return false;
        for (SubscriptionInfo si : list) {
            if (si.getSimSlotIndex() == slot1 - 1) return true;
        }
        return false;
    }

    /** 该槽的运营商名，没有返回空串。 */
    public static String carrierOf(Context c, int slot1) {
        List<SubscriptionInfo> list = subs(c);
        if (list == null) return "";
        for (SubscriptionInfo si : list) {
            if (si.getSimSlotIndex() != slot1 - 1) continue;
            try {
                CharSequence cs = si.getCarrierName();
                if (cs != null && cs.length() > 0) return cs.toString();
            } catch (Throwable ignored) { }
            try {
                CharSequence cs = si.getDisplayName();
                if (cs != null) return cs.toString();
            } catch (Throwable ignored) { }
        }
        return "";
    }

    /** 页面用：{"count":2,"current":1,"slots":[{"slot":1,"has":true,"name":"中国移动","short":"移动"}]} */
    public static String json(Context c) {
        int slots = slotCount(c);
        int cur = currentSlot(c);
        List<SubscriptionInfo> list = subs(c);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"count\":").append(slots);
        sb.append(",\"current\":").append(cur);
        sb.append(",\"slots\":[");
        for (int i = 0; i < slots; i++) {
            if (i > 0) sb.append(",");
            boolean has = false;
            String name = "";
            if (list != null) {
                for (SubscriptionInfo si : list) {
                    if (si.getSimSlotIndex() != i) continue;
                    has = true;
                    try {
                        CharSequence cs = si.getCarrierName();
                        if (cs != null) name = cs.toString();
                    } catch (Throwable ignored) { }
                    if (name.length() == 0) {
                        try {
                            CharSequence cs = si.getDisplayName();
                            if (cs != null) name = cs.toString();
                        } catch (Throwable ignored) { }
                    }
                    break;
                }
            }
            sb.append("{\"slot\":").append(i + 1);
            sb.append(",\"has\":").append(has);
            sb.append(",\"name\":\"").append(esc(name)).append("\"");
            sb.append(",\"short\":\"").append(esc(shortName(name))).append("\"");
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 「中国移动」→「移动」，给按钮上的小字用。 */
    private static String shortName(String n) {
        if (n == null) return "";
        String s = n.trim();
        if (s.length() == 0) return "";
        if (s.startsWith("中国") && s.length() > 2) return s.substring(2);
        return s.length() <= 4 ? s : s.substring(0, 4);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
