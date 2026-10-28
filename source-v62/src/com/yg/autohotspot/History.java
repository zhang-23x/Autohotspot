package com.yg.autohotspot;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 热点开/关事件流水。
 *
 * 没有 root 读不到系统日志，所以自己记：每次采样看热点在不在跑，
 * 状态一变就追加一条。用来回答「昨天夜里几点断过」这类问题。
 *
 * 文件：getFilesDir()/hotspot-history.txt，每行 "epochMillis|1"（开）或 "epochMillis|0"（关）。
 */
final class History {

    private static final String LOG_FILE = "hotspot-history.txt";
    private static final String STATE_FILE = "hotspot-laststate.txt";
    private static final long KEEP_MS = 7L * 24 * 3600 * 1000;
    /** 面板只显示最近这么多条；写盘时也只留这么多，旧的自动删掉。 */
    private static final int MAX_LINES = 20;
    /** 超过这个间隔没采样，说明进程死过或被关机，按断档处理。 */
    private static final long GAP_MS = 180000L;
    /** 状态没变时，最少隔这么久才更新一次心跳，避免页面每 2 秒刷就写盘。 */
    private static final long HEARTBEAT_MS = 20000L;
    private static final Object LOCK = new Object();

    private History() { }

    /** 热点是否在跑：本机 AP 地址是否挂在某个网卡上（v42 改为运行时探测）。 */
    static boolean up() {
        try {
            Enumeration<NetworkInterface> es = NetworkInterface.getNetworkInterfaces();
            while (es != null && es.hasMoreElements()) {
                NetworkInterface ni = es.nextElement();
                if (ni == null || !ni.isUp()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as != null && as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address
                            && ApProfile.apIp().equals(a.getHostAddress())) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * 采样一次。三种情况：
     *  1. 状态变了 → 记一条；
     *  2. 距上次采样太久（进程被杀 / 手机关机）→ 先把断档记成「掉线」，再记当前状态；
     *  3. 其它情况只更新心跳，最多 20 秒写一次盘。
     */
    static void sample(Context c) {
        synchronized (LOCK) {
            try {
                boolean now = up();
                long ts = System.currentTimeMillis();
                String flag = now ? "1" : "0";
                // 这次开机的墙上时刻：每次重启都会变，是"手机是不是重启过"的唯一可靠指纹。
                long bootWall = ts - SystemClock.elapsedRealtime();

                String rec = readSmall(c, STATE_FILE);
                String stateStr = null;
                long lastTs = 0;
                long lastBootWall = 0;
                if (rec != null && rec.length() > 0) {
                    String[] f = rec.split("\\|");
                    if (f.length >= 1) stateStr = f[0];
                    if (f.length >= 2) {
                        try { lastTs = Long.parseLong(f[1].trim()); } catch (Throwable ignored) { }
                    }
                    if (f.length >= 3) {
                        try { lastBootWall = Long.parseLong(f[2].trim()); } catch (Throwable ignored) { }
                    }
                }

                if (stateStr == null) {
                    writeSmall(c, STATE_FILE, flag + "|" + ts + "|" + bootWall);
                    append(c, ts, now);
                    return;
                }

                // 开机时刻变了 = 手机重启过（不管是点按钮、长按电源键还是断电）。
                // 这个判断比"采样间隔多大"可靠：重启只要几十秒，间隔阈值抓不住。
                if (lastBootWall > 0 && Math.abs(bootWall - lastBootWall) > 5000L) {
                    append(c, lastTs > 0 ? lastTs : ts, false);
                    writeSmall(c, STATE_FILE, flag + "|" + ts + "|" + bootWall);
                    if (now) append(c, ts, now);
                    return;
                }

                if (lastTs > 0 && (ts - lastTs) > GAP_MS) {
                    // 这中间没人采样：进程死了或被关机。上一段以「掉线」收尾，
                    // 当前状态另起一条，这样重启/断电在面板上是有痕迹的。
                    append(c, lastTs, false);
                    writeSmall(c, STATE_FILE, flag + "|" + ts + "|" + bootWall);
                    append(c, ts, now);
                    return;
                }

                if (!flag.equals(stateStr)) {
                    writeSmall(c, STATE_FILE, flag + "|" + ts + "|" + bootWall);
                    append(c, ts, now);
                } else if (ts - lastTs > HEARTBEAT_MS) {
                    writeSmall(c, STATE_FILE, flag + "|" + ts + "|" + bootWall);
                }
            } catch (Throwable t) {
                Log.w(HotspotService.TAG, "history sample failed: " + t);
            }
        }
    }

    /** 最近 7 天内、且最多 MAX_LINES 条的事件，按时间升序返回 JSON 数组。 */
    static String json(Context c) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("[");
        try {
            List<long[]> rows = load(c);
            long cutoff = System.currentTimeMillis() - KEEP_MS;
            List<long[]> keep = new ArrayList<long[]>();
            for (int i = 0; i < rows.size(); i++) {
                long[] r = rows.get(i);
                if (r[0] < cutoff) continue;
                keep.add(r);
            }
            int from = keep.size() > MAX_LINES ? keep.size() - MAX_LINES : 0;
            boolean first = true;
            for (int i = from; i < keep.size(); i++) {
                long[] r = keep.get(i);
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"t\":").append(r[0]).append(",\"up\":").append(r[1] == 1);
                sb.append("}");
            }
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "history json failed: " + t);
        }
        sb.append("]");
        return sb.toString();
    }

    // ------------------------------------------------------------ files

    private static void append(Context c, long ts, boolean up) {
        File f = new File(c.getFilesDir(), LOG_FILE);
        List<long[]> rows = load(c);
        rows.add(new long[]{ts, up ? 1 : 0});
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        int from = 0;
        while (from < rows.size() && rows.get(from)[0] < cutoff) from++;
        if (rows.size() - from > MAX_LINES) from = rows.size() - MAX_LINES;

        StringBuilder sb = new StringBuilder(rows.size() * 16);
        for (int i = from; i < rows.size(); i++) {
            sb.append(rows.get(i)[0]).append('|').append(rows.get(i)[1]).append('\n');
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            OutputStreamWriter w = new OutputStreamWriter(out, "UTF-8");
            w.write(sb.toString());
            w.flush();
            w.close();
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "history write failed: " + t);
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }

    private static List<long[]> load(Context c) {
        List<long[]> rows = new ArrayList<long[]>();
        File f = new File(c.getFilesDir(), LOG_FILE);
        if (!f.exists()) return rows;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int p = line.indexOf('|');
                if (p <= 0) continue;
                try {
                    long ts = Long.parseLong(line.substring(0, p).trim());
                    int v = Integer.parseInt(line.substring(p + 1).trim());
                    rows.add(new long[]{ts, v});
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "history read failed: " + t);
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
        return rows;
    }

    private static String readSmall(Context c, String name) {
        BufferedReader r = null;
        try {
            File f = new File(c.getFilesDir(), name);
            if (!f.exists()) return null;
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String s = r.readLine();
            return s == null ? null : s.trim();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
    }

    private static void writeSmall(Context c, String name, String value) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(c.getFilesDir(), name), false);
            OutputStreamWriter w = new OutputStreamWriter(out, "UTF-8");
            w.write(value);
            w.flush();
            w.close();
        } catch (Throwable ignored) {
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }
}
