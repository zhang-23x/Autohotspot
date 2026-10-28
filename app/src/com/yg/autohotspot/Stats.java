package com.yg.autohotspot;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * v34 24 小时曲线采样：每 5 分钟记一条（这段时间的流量增量 + 在线设备数 + 热点是否在跑）。
 *
 * 存增量而不是总量：手机重启后 TrafficStats 归零，总量曲线会掉回 0，
 * 增量曲线不受影响。24 小时 = 288 条，文件只有十几 KB。
 */
public class Stats {

    private static final String FILE = "stats.txt";
    private static final long STEP_MS = 5 * 60 * 1000L;
    private static final int MAX = 24 * 12;

    private static long lastRx = -1;
    private static long lastTx = -1;
    private static long lastAt = 0;

    static synchronized void sample(Context c, long rx, long tx, int clients, boolean up) {
        try {
            long now = System.currentTimeMillis();
            if (lastAt > 0 && now - lastAt < STEP_MS) return;

            long drx = (lastRx >= 0 && rx >= lastRx) ? rx - lastRx : 0;
            long dtx = (lastTx >= 0 && tx >= lastTx) ? tx - lastTx : 0;
            lastRx = rx;
            lastTx = tx;
            lastAt = now;

            List<String> rows = read(c);
            rows.add(now + "|" + drx + "|" + dtx + "|" + clients + "|" + (up ? 1 : 0));
            while (rows.size() > MAX) rows.remove(0);
            write(c, rows);
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "stats sample: " + t);
        }
    }

    /** 开机/重连后从文件最后一条恢复计时，避免把中间的空档算成一段流量 */
    static synchronized void prime(Context c) {
        try {
            List<String> rows = read(c);
            if (!rows.isEmpty()) {
                String[] p = rows.get(rows.size() - 1).split("\\|");
                if (p.length >= 1) lastAt = Long.parseLong(p[0].trim());
            }
        } catch (Throwable ignored) { }
    }

    static String json(Context c) {
        StringBuilder sb = new StringBuilder("[");
        try {
            List<String> rows = read(c);
            long cut = System.currentTimeMillis() - 24L * 3600L * 1000L;
            boolean first = true;
            for (String r : rows) {
                String[] p = r.split("\\|");
                if (p.length < 5) continue;
                long t = Long.parseLong(p[0].trim());
                if (t < cut) continue;
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"t\":").append(t)
                        .append(",\"rx\":").append(p[1].trim())
                        .append(",\"tx\":").append(p[2].trim())
                        .append(",\"c\":").append(p[3].trim())
                        .append(",\"up\":").append(p[4].trim()).append("}");
            }
        } catch (Throwable ignored) { }
        return sb.append("]").toString();
    }

    static synchronized void clear(Context c) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (f.exists()) f.delete();
            lastRx = -1;
            lastTx = -1;
            lastAt = 0;
        } catch (Throwable ignored) { }
    }

    private static List<String> read(Context c) {
        List<String> out = new ArrayList<String>();
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists()) return out;
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() > 0) out.add(line);
            }
            r.close();
        } catch (Throwable ignored) { }
        return out;
    }

    private static void write(Context c, List<String> rows) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            PrintWriter w = new PrintWriter(f, "UTF-8");
            for (String r : rows) w.println(r);
            w.close();
        } catch (Throwable ignored) { }
    }
}
