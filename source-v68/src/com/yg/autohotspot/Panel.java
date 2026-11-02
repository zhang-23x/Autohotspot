package com.yg.autohotspot;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * v34 面板配置：访问密码、配色、流量限额、最大连接数、设备备注、定时关热点锁。
 *
 * 全部落在 SharedPreferences("panel")，重启不丢。
 * 密码是明文存的：这台机器是个局域网热点面板，加密码是为了挡住同网段
 * 随手点开的人，不是抗暴力破解，没必要上哈希。
 */
public class Panel {

    private static final String SP = "panel";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    static String s(Context c, String k, String def) {
        try { return sp(c).getString(k, def); } catch (Throwable t) { return def; }
    }

    static int i(Context c, String k, int def) {
        try { return sp(c).getInt(k, def); } catch (Throwable t) { return def; }
    }

    static long l(Context c, String k, long def) {
        try { return sp(c).getLong(k, def); } catch (Throwable t) { return def; }
    }

    static void setS(Context c, String k, String v) {
        try { sp(c).edit().putString(k, v).apply(); } catch (Throwable ignored) { }
    }

    static void setI(Context c, String k, int v) {
        try { sp(c).edit().putInt(k, v).apply(); } catch (Throwable ignored) { }
    }

    static void setL(Context c, String k, long v) {
        try { sp(c).edit().putLong(k, v).apply(); } catch (Throwable ignored) { }
    }

    private static void setB(Context c, String k, boolean v) {
        try { sp(c).edit().putBoolean(k, v).apply(); } catch (Throwable ignored) { }
    }

    private static boolean b(Context c, String k, boolean def) {
        try { return sp(c).getBoolean(k, def); } catch (Throwable t) { return def; }
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder o = new StringBuilder(s.length() + 8);
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch == '"' || ch == '\\') o.append('\\').append(ch);
            else if (ch == '\n') o.append("\\n");
            else if (ch == '\r') o.append("\\r");
            else if (ch == '\t') o.append("\\t");
            else if (ch < 0x20) o.append(' ');
            else o.append(ch);
        }
        return o.toString();
    }

    /* ------------------------------------------------- 访问密码 */

    static String pass(Context c) { return s(c, "pass", ""); }

    static boolean hasPass(Context c) { return pass(c).length() > 0; }

    static void setPass(Context c, String p) {
        String v = p == null ? "" : p.trim();
        setS(c, "pass", v);
        if (v.length() == 0) setS(c, "token", "");
    }

    /** 没设密码时一律放行；设了就比对，对上后前端拿到同一个 token */
    static boolean authOk(Context c, String token) {
        String p = pass(c);
        if (p.length() == 0) return true;
        return token != null && p.equals(token);
    }

    /* ------------------------------------------------- 配色 */

    static String theme(Context c) { return "light".equals(s(c, "theme", "dark")) ? "light" : "dark"; }

    static void setTheme(Context c, String t) { setS(c, "theme", "light".equals(t) ? "light" : "dark"); }

    /* ------------------------------------------------- 限额与连接数 */

    static int limitMB(Context c) { return Math.max(0, i(c, "limitMB", 0)); }

    static void setLimitMB(Context c, int mb) { setI(c, "limitMB", Math.max(0, mb)); }

    static int maxClients(Context c) { return Math.max(0, i(c, "maxClients", 0)); }

    static void setMaxClients(Context c, int n) { setI(c, "maxClients", Math.max(0, Math.min(32, n))); }

    static boolean maxDrop(Context c) { return b(c, "maxDrop", false); }

    static void setMaxDrop(Context c, boolean v) { setB(c, "maxDrop", v); }

    /**
     * 超限挤人：断开一次热点再拉回来，把多出来的设备挤掉。
     * Android 10 没有「踢单个客户端」的公开接口，只能整机断一次。
     * 10 分钟冷却，避免反复断网。
     */
    static void dropOverflow(final Context c, int n, int max) {
        long now = System.currentTimeMillis();
        if (now - l(c, "lastDropAt", 0L) < 10L * 60 * 1000L) return;
        setL(c, "lastDropAt", now);
        setS(c, "lastDropInfo", "超限挤人：在线 " + n + " 台 > 上限 " + max + " 台，已断一次热点");
        try { HotspotService.turnOff(); } catch (Throwable ignored) { }
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(45000L); } catch (Throwable ignored) { }
                try { setManualOff(c, false); } catch (Throwable ignored) { }
                try { HotspotService.trigger(); } catch (Throwable ignored) { }
            }
        }).start();
    }

    /* ------------------------------------------------- 定时关热点锁 */

    /**
     * 定时「关热点」之后置 true，看门狗就不会再自己把热点拉回来；
     * 定时「开热点」或用户在面板上点恢复时清掉。
     */
    static boolean manualOff(Context c) { return b(c, "manualOff", false); }

    static void setManualOff(Context c, boolean v) { setB(c, "manualOff", v); }

    /* ------------------------------------------------- 设备备注 */

    static String note(Context c, String mac) {
        if (mac == null || mac.length() == 0) return "";
        return s(c, "n:" + mac.toLowerCase(Locale.US), "");
    }

    static void setNote(Context c, String mac, String name) {
        if (mac == null || mac.length() == 0) return;
        setS(c, "n:" + mac.toLowerCase(Locale.US), name == null ? "" : name.trim());
    }

    /** 所有备注，输出 {"mac":"名字"} */
    static String notesJson(Context c) {
        StringBuilder sb = new StringBuilder("{");
        try {
            boolean first = true;
            for (Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
                String k = e.getKey();
                if (k == null || !k.startsWith("n:")) continue;
                Object v = e.getValue();
                if (!(v instanceof String)) continue;
                String val = (String) v;
                if (val.length() == 0) continue;
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(esc(k.substring(2))).append("\":\"").append(esc(val)).append("\"");
            }
        } catch (Throwable ignored) { }
        return sb.append("}").toString();
    }

    /* ------------------------------------------------- 拉黑名单 */

    /** 黑名单条目统一小写去空，IP 或 MAC 都行 */
    private static String normKey(String v) {
        if (v == null) return "";
        String s = v.trim().toLowerCase(Locale.US);
        if (s.length() == 0) return "";
        return s.replace(':', '-').replace('.', '-');
    }

    static java.util.List<String> blockList(Context c) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        try {
            String raw = s(c, "block", "");
            if (raw.length() == 0) return out;
            String[] parts = raw.split(",");
            for (int i = 0; i < parts.length; i++) {
                String x = parts[i].trim();
                if (x.length() > 0) out.add(x);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static boolean isBlocked(Context c, String ip, String mac) {
        java.util.List<String> l = blockList(c);
        if (l.isEmpty()) return false;
        String a = normKey(ip), b = normKey(mac);
        for (int i = 0; i < l.size(); i++) {
            String k = normKey(l.get(i));
            if (k.length() == 0) continue;
            if (k.equals(a) || (b.length() > 0 && k.equals(b))) return true;
        }
        return false;
    }

    /** v 空=清空；-xxx=移除；xxx=添加。返回 null 表示成功 */
    static String setBlock(Context c, String v) {
        java.util.List<String> l = blockList(c);
        String t = v == null ? "" : v.trim();
        if (t.length() == 0) {
            setS(c, "block", "");
            return null;
        }
        if (t.startsWith("-")) {
            String k = normKey(t.substring(1));
            for (int i = l.size() - 1; i >= 0; i--) {
                if (normKey(l.get(i)).equals(k)) l.remove(i);
            }
        } else {
            String k = normKey(t);
            if (k.length() == 0) return "地址为空";
            boolean dup = false;
            for (int i = 0; i < l.size(); i++) {
                if (normKey(l.get(i)).equals(k)) { dup = true; break; }
            }
            if (!dup) {
                if (l.size() >= 32) return "最多 32 条";
                l.add(t);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(l.get(i));
        }
        setS(c, "block", sb.toString());
        return null;
    }

    static String blockJson(Context c) {
        java.util.List<String> l = blockList(c);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(esc(l.get(i))).append('"');
        }
        return sb.append("]").toString();
    }

    private static long lastBlockCheck = 0;
    private static long lastKickAt = 0;

    /**
     * 拉黑生效。
     *
     * 本机没有 root，iptables 封包走不通；华为也没开放热点 ACL。
     * 唯一能把人赶下线的手段是重开一次热点 —— 代价是全屋掉几秒。
     * 所以只对「黑名单里出现了在线设备」这一件事动手，并且踢完 10 秒内不重复，
     * 让对方每次重连都被挤掉，实际用不成。
     *
     * @return 被踢的地址，没动手返回 null
     */
    static String enforceBlock(Context c) {
        java.util.List<String> bl = blockList(c);
        if (bl.isEmpty()) return null;
        long now = System.currentTimeMillis();
        if (now - lastBlockCheck < 6000) return null;
        lastBlockCheck = now;
        if (now - lastKickAt < 10000) return null;

        java.util.List<String[]> on = PhoneServer.neighList();
        for (int i = 0; i < on.size(); i++) {
            String ip = on.get(i)[0];
            String mac = on.get(i)[1];
            if (!isBlocked(c, ip, mac)) continue;
            lastKickAt = now;
            setL(c, "blockKickAt", now);
            setS(c, "blockKickIp", ip.length() > 0 ? ip : mac);
            if (HotspotService.INSTANCE != null) {
                try { HotspotService.trigger(); } catch (Throwable ignored) { }
            }
            return ip.length() > 0 ? ip : mac;
        }
        return null;
    }

    /* ------------------------------------------------- 本月流量 */

    private static String monthKey() {
        return new SimpleDateFormat("yyyyMM", Locale.US).format(new Date());
    }

    /**
     * 本月累计字节 = 当前 TrafficStats 总量 − 本月基线。
     * 跨月或者手机重启过（总量变小）就重置基线，避免算出负数。
     */
    static long monthBytes(Context c, long rx, long tx) {
        String mk = monthKey();
        String stored = s(c, "baseMonth", "");
        long baseRx = l(c, "baseRx", 0);
        long baseTx = l(c, "baseTx", 0);
        if (!mk.equals(stored) || rx < baseRx || tx < baseTx) {
            setS(c, "baseMonth", mk);
            setL(c, "baseRx", rx);
            setL(c, "baseTx", tx);
            baseRx = rx;
            baseTx = tx;
        }
        long d = (rx - baseRx) + (tx - baseTx);
        return d < 0 ? 0 : d;
    }

    static void resetMonth(Context c, long rx, long tx) {
        setS(c, "baseMonth", monthKey());
        setL(c, "baseRx", rx);
        setL(c, "baseTx", tx);
    }

    /* ------------------------------------------------- 汇总给前端 */

    /** 读系统热点配置里的 SSID（反射，读不到返回空串） */
    static String apSsid(Context c) {
        Object o = apCfg(c);
        if (o != null) {
            try {
                java.lang.reflect.Field f = o.getClass().getField("SSID");
                Object v = f.get(o);
                if (v != null && v.toString().length() > 0) return v.toString();
            } catch (Throwable ignored) { }
        }
        return s(c, "apS", "");
    }

    /** 用户改热点时记下名字密码，遮掩系统读不到 SSID 的情况 */
    static void setApInfo(Context c, String ssid, String pwd) {
        if (ssid != null && ssid.length() > 0) setS(c, "apS", ssid);
        if (pwd != null && pwd.length() > 0) setS(c, "apP", pwd);
    }

    /** 读系统热点配置里的密码 */
    static String apPwd(Context c) {
        Object o = apCfg(c);
        if (o != null) {
            try {
                java.lang.reflect.Field f = o.getClass().getField("preSharedKey");
                Object v = f.get(o);
                String t = v == null ? "" : v.toString();
                if (t.length() > 0) return t;
            } catch (Throwable ignored) { }
        }
        return s(c, "apP", "");
    }

    private static Object apCfg(Context c) {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            java.lang.reflect.Method m = wm.getClass().getMethod("getWifiApConfiguration");
            return m.invoke(wm);
        } catch (Throwable t) { return null; }
    }

    static String cfgJson(Context c, long monthBytes) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"hasPass\":").append(hasPass(c)).append(",");
        sb.append("\"theme\":\"").append(theme(c)).append("\",");
        sb.append("\"limitMB\":").append(limitMB(c)).append(",");
        sb.append("\"monthBytes\":").append(monthBytes).append(",");
        sb.append("\"maxClients\":").append(maxClients(c)).append(",");
        sb.append("\"maxDrop\":").append(maxDrop(c)).append(",");
        sb.append("\"ssid\":\"").append(esc(apSsid(c))).append("\",");
        sb.append("\"apwd\":\"").append(esc(apPwd(c))).append("\",");
        sb.append("\"manualOff\":").append(manualOff(c)).append(",");
        sb.append("\"rules\":").append(Automation.json(c)).append(",");
        sb.append("\"lastRule\":\"").append(esc(s(c, "lastRule", ""))).append("\",");
        sb.append("\"notes\":").append(notesJson(c));
        sb.append(",\"block\":").append(blockJson(c));
        sb.append(",\"apSsid\":\"").append(esc(apSsid(c))).append("\",\"apPwd\":\"").append(esc(apPwd(c))).append("\"");
        sb.append("}");
        return sb.toString();
    }
}
