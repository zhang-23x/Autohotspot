package com.yg.autohotspot;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 手机自带的小型 HTTP 控制台（不需要电脑、不需要 USB、不需要 ADB）。
 *
 * 起因：原来那套面板跑在电脑上，靠 adb 读手机；一拔线 adb 通道就断，面板就瞎了。
 * 现在把采集与控制放进手机自己，任何连上热点的设备（包括手机本机）直接访问
 * http://192.168.43.1:8080 即可。
 *
 * 端口 8080，随无障碍服务一起常驻。
 */
public class PhoneServer {

    static final String TAG = "AutoHotspot";
    static final int PORT = 8080;

    private static final Object LOCK = new Object();
    private static volatile boolean started = false;
    private static ServerSocket ss = null;
    static volatile android.content.Context APP = null;

    /* 速率采样：保存上一次的累计字节和时间戳，两次相减得到瞬时速率 */
    private static long lastRx = -1;
    private static long lastTx = -1;
    private static long lastTs = 0;

    /**
     * 通过 HTTP 面板访问过的客户端（IP -> 最后访问毫秒）。
     *
     * Android 9+ 起普通应用读 /proc/net/arp 会被 SELinux 拦成空表、华为私有 getStaList
     * 也因缺少系统签名而调不通，所以热点客户端列表改为「多源合并」：
     * 谁能拿到就用谁，HTTP 访问记录是唯一 100% 可用的真实来源。
     */
    private static final java.util.Map<String, Long> seenClients =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /** 记下每台设备最后一次见到的 MAC。断开后 ARP 表项失效（没有 lladdr），靠它把 MAC 补回去。 */
    private static final java.util.Map<String, String> lastMac =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    static void start(final Context ctx) {
        synchronized (LOCK) {
            if (started && ss != null && !ss.isClosed()) return;
            started = true;
        }
        APP = ctx.getApplicationContext();
        final Context app = ctx.getApplicationContext() == null
                ? ctx : ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            public void run() {
                // 外层永不死：任何异常都重建监听，保证 8080 不会因为一次 accept 出错就永久失联
                while (true) {
                    ServerSocket s = null;
                    try {
                        s = new ServerSocket();
                        s.setReuseAddress(true);
                        s.bind(new InetSocketAddress(PORT));
                        ss = s;
                        Log.i(TAG, "phone server listening on " + PORT);
                        while (true) {
                            Socket c = s.accept();
                            try {
                                handle(app, c);
                            } catch (Throwable ignored) {
                            } finally {
                                try { c.close(); } catch (Throwable ignored) { }
                            }
                        }
                    } catch (Throwable e) {
                        Log.e(TAG, "phone server cycle error: " + e);
                        try { if (s != null) s.close(); } catch (Throwable ignored) { }
                        ss = null;
                        try { Thread.sleep(1000L); } catch (Throwable ignored) { }
                    }
                }
            }
        }, "phone-http");
        t.setDaemon(true);
        t.start();
    }

    /** 从外部做一次健康检查：线程活着但端口没在听时，强制重建。 */
    static boolean healthy() {
        try {
            return started && ss != null && !ss.isClosed();
        } catch (Throwable t) {
            return false;
        }
    }

    /** v59 只有「设置」页里的改动才要密码；看数据、切卡、命名、测速都免密 */
    /** 延迟：TCP 连国内 DNS 的公网地址，取最快值 */
    /** 测速会话：后台线程跑，前端每 250ms 轮询 /api/speedstate 拿实时值 */
    static class Spd {
        static volatile boolean run = false;
        static volatile String stage = "idle";
        static volatile int ping = -1;
        static volatile double down = 0, now = 0, peak = 0;
        static volatile String err = "";
        static final java.util.ArrayList<Double> trace = new java.util.ArrayList<Double>();

        static synchronized void reset() {
            stage = "idle"; ping = -1; down = 0; now = 0; peak = 0; err = ""; trace.clear();
        }

        static synchronized void add(double v) {
            now = v;
            if (v > peak) peak = v;
            trace.add(v);
            if (trace.size() > 300) trace.remove(0);
        }

        static synchronized String json() {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"run\":").append(run);
            sb.append(",\"stage\":\"").append(stage).append('"');
            sb.append(",\"ping\":").append(ping);
            sb.append(",\"down\":").append(String.format(java.util.Locale.US, "%.2f", down));
            sb.append(",\"now\":").append(String.format(java.util.Locale.US, "%.2f", now));
            sb.append(",\"peak\":").append(String.format(java.util.Locale.US, "%.2f", peak));
            sb.append(",\"err\":\"").append(esc(err)).append('"');
            sb.append(",\"trace\":[");
            for (int i = 0; i < trace.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(String.format(java.util.Locale.US, "%.2f", trace.get(i)));
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    static void startSpeedJob() {
        if (Spd.run) return;
        Spd.reset();
        Spd.run = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    Spd.stage = "ping";
                    String p = speedPing();
                    if (p.indexOf("\"ok\":true") < 0) {
                        Spd.err = "连不上测速节点"; Spd.stage = "fail"; return;
                    }
                    Spd.ping = (int) numOf(p, "ms");
                    Spd.stage = "down";
                    speedDownStream();
                } catch (Throwable t) {
                    Spd.err = String.valueOf(t.getMessage()); Spd.stage = "fail";
                } finally {
                    Spd.run = false;
                }
            }
        }, "speedjob").start();
    }

    /** 下载测速：边下边把每 200ms 窗口速度写进 Spd.trace */
    /** 下载测速：交给系统 DownloadManager，它跑在系统进程里，不受第三方 App 单连接限速 */
    static void speedDownStream() {
        final android.content.Context ctx = APP;
        if (ctx == null) { Spd.err = "上下文丢失"; Spd.stage = "fail"; return; }
        android.app.DownloadManager dm = null;
        long id = -1;
        java.io.File tmp = new java.io.File(ctx.getExternalFilesDir(null), "spdtest.bin");
        try { if (tmp.exists()) tmp.delete(); } catch (Throwable ig) { }
        try {
            dm = (android.app.DownloadManager) ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            if (dm == null) { Spd.err = "系统下载服务不可用"; Spd.stage = "fail"; return; }
            android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(
                    android.net.Uri.parse("https://mirrors.huaweicloud.com/ubuntu/ls-lR.gz"));
            req.setDestinationInExternalFilesDir(ctx, null, "spdtest.bin");
            req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_HIDDEN);
            req.setTitle("AutoHotspot 测速");
            req.setDescription("正在测量下载速度");
            id = dm.enqueue(req);
        } catch (Throwable t) {
            Spd.err = "发起下载失败：" + t.getMessage();
            Spd.stage = "fail";
            return;
        }
        long t0 = System.currentTimeMillis();
        long limit = t0 + 60000L;
        long lastT = 0, lastB = 0;
        long steadyT = 0, steadyB = 0;
        long finalB = 0;
        while (System.currentTimeMillis() < limit) {
            try { Thread.sleep(200); } catch (Throwable ig) { }
            long now = System.currentTimeMillis();
            long bytes = -1;
            int status = -1;
            android.database.Cursor c = null;
            try {
                c = dm.query(new android.app.DownloadManager.Query().setFilterById(id));
                if (c != null && c.moveToFirst()) {
                    int bi = c.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int si = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                    if (bi >= 0) bytes = c.getLong(bi);
                    if (si >= 0) status = c.getInt(si);
                }
            } catch (Throwable ig) {
            } finally {
                try { if (c != null) c.close(); } catch (Throwable ig) { }
            }
            if (bytes < 0) continue;
            finalB = bytes;
            if (bytes == lastB) continue;
            if (lastT == 0) {
                lastT = now; lastB = bytes;
                steadyT = now + 1500L; steadyB = bytes;
                continue;
            }
            if (now < steadyT) { lastT = now; lastB = bytes; continue; }
            long dtm = now - lastT;
            if (dtm > 0) {
                double mbps = (bytes - lastB) * 8.0 / (dtm / 1000.0) / 1000000.0;
                Spd.add(mbps);
            }
            lastT = now; lastB = bytes;
            if (status == android.app.DownloadManager.STATUS_SUCCESSFUL
                    || status == android.app.DownloadManager.STATUS_FAILED) break;
            if (bytes > 60L * 1024 * 1024) break;
        }
        long endT = System.currentTimeMillis();
        long totalMs = endT - t0;
        try { dm.remove(id); } catch (Throwable ig) { }
        try { if (tmp.exists()) tmp.delete(); } catch (Throwable ig) { }
        android.util.Log.i("AHS", "dm down bytes=" + finalB + " totalMs=" + totalMs);
        if (finalB < 1048576 || totalMs < 300) {
            Spd.err = "数据太少（" + (finalB / 1024) + " KB / " + totalMs + " ms）";
            Spd.stage = "fail";
            return;
        }
        Spd.down = finalB * 8.0 / (totalMs / 1000.0) / 1000000.0;
        Spd.peak = Spd.down;
        Spd.now = Spd.down;
        Spd.stage = "done";
    }

    static double numOf(String j, String key) {
        try {
            int i = j.indexOf("\"" + key + "\":");
            if (i < 0) return 0;
            int s = i + key.length() + 3;
            int e = s;
            while (e < j.length() && "-.0123456789".indexOf(j.charAt(e)) >= 0) e++;
            return Double.parseDouble(j.substring(s, e));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String speedPing() {
        String[] hosts = {"223.5.5.5", "119.29.29.29", "114.114.114.114"};
        int[] ports = {53, 443, 53};
        long best = -1;
        for (int i = 0; i < hosts.length; i++) {
            for (int k = 0; k < 3; k++) {
                Socket s = null;
                try {
                    s = new Socket();
                    long t0 = System.currentTimeMillis();
                    s.connect(new java.net.InetSocketAddress(hosts[i], ports[i]), 2000);
                    long dt = System.currentTimeMillis() - t0;
                    if (best < 0 || dt < best) best = dt;
                } catch (Throwable ignored) {
                } finally {
                    try { if (s != null) s.close(); } catch (Throwable ignored) { }
                }
            }
        }
        if (best < 0) return "{\"ok\":false,\"msg\":\"连不上测速节点\"}";
        return "{\"ok\":true,\"ms\":" + best + "}";
    }

    /** 下载：从阿里云镜像拉文件，最多读 8 秒 */
    private static String speedDown() {
        final String[] urls = {
                "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ls-lR.gz",
                "https://mirrors.ustc.edu.cn/ubuntu/ls-lR.gz",
                "https://mirrors.huaweicloud.com/ubuntu/ls-lR.gz",
                "https://mirrors.aliyun.com/ubuntu/ls-lR.gz"
        };
        final int N = 1;
        final long WARM = 1500L;
        final long WIN = 5000L;
        final long HARD = WARM + WIN + 5000L;
        final java.util.concurrent.atomic.AtomicLong done = new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicLong firstByte = new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicInteger okCnt = new java.util.concurrent.atomic.AtomicInteger(0);
        final String[] errMsg = new String[1];
        final long[] perSrc = new long[N];
        final Object lock = new Object();
        Thread[] ts = new Thread[N];
        for (int k = 0; k < N; k++) {
            final String u = urls[k];
            final int idx = k;
            ts[k] = new Thread(new Runnable() {
                public void run() {
                    java.net.HttpURLConnection cn = null;
                    InputStream is = null;
                    try {
                        cn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                        cn.setConnectTimeout(5000);
                        cn.setReadTimeout(5000);
                        cn.setInstanceFollowRedirects(true);
                        cn.setRequestProperty("User-Agent", "Mozilla/5.0 AutoHotspot");
                        cn.connect();
                        int rc = cn.getResponseCode();
                        android.util.Log.i("AHS", "speed src " + u + " code=" + rc);
                        if (rc != 200) return;
                        is = cn.getInputStream();
                        byte[] buf = new byte[65536];
                        int n;
                        long t0 = 0;
                        while ((n = is.read(buf)) > 0) {
                            long now = System.currentTimeMillis();
                            if (t0 == 0) {
                                t0 = now;
                                firstByte.compareAndSet(0, now);
                                okCnt.incrementAndGet();
                            }
                            long g0 = firstByte.get();
                            if (g0 == 0) g0 = t0;
                            long el = now - g0;
                            if (el > WARM) { done.addAndGet(n); perSrc[idx] += n; }
                            if (el > WARM + WIN) break;
                            if (done.get() > 80L * 1024 * 1024) break;
                        }
                    } catch (Throwable t) {
                        android.util.Log.e("AHS", "speed src " + u + " fail: " + t);
                        synchronized (lock) { if (errMsg[0] == null) errMsg[0] = String.valueOf(t.getMessage()); }
                    } finally {
                        try { if (is != null) is.close(); } catch (Throwable ig) { }
                        try { if (cn != null) cn.disconnect(); } catch (Throwable ig) { }
                    }
                }
            });
            ts[k].start();
        }
        long begin = System.currentTimeMillis();
        for (int k = 0; k < N; k++) {
            try { ts[k].join(Math.max(300, HARD - (System.currentTimeMillis() - begin))); } catch (Throwable ig) { }
        }
        for (int k = 0; k < N; k++) {
            if (ts[k].isAlive()) ts[k].interrupt();
        }
        long got = done.get();
        if (okCnt.get() == 0) {
            String em = errMsg[0] == null ? "" : "：" + errMsg[0].replace('"', ' ');
            return "{\"ok\":false,\"msg\":\"镜像都连不上" + em + "\"}";
        }
        if (got < 131072) {
            return "{\"ok\":false,\"msg\":\"数据太少（" + (got / 1024) + " KB）\"}";
        }
        double mbps = got * 8.0 / (WIN / 1000.0) / 1000000.0;
        return "{\"ok\":true,\"mbps\":" + String.format(java.util.Locale.US, "%.2f", mbps)
                + ",\"bytes\":" + got + ",\"ms\":" + WIN
                + ",\"src\":[" + perSrc[0] + "]}";
    }

    private static long contentLength(String head) {
        try {
            String h = head.toLowerCase();
            int i = h.indexOf("content-length:");
            if (i < 0) return 0;
            int e = h.indexOf('\n', i);
            if (e < 0) e = h.length();
            return Long.parseLong(h.substring(i + 15, e).trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }
    /** 上传：浏览器 POST 数据过来，按收完耗时算速度 */
    private static String speedUpload(InputStream in, long limit) {
        long start = System.currentTimeMillis();
        long total = 0;
        byte[] buf = new byte[16384];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (limit > 0 && total >= limit) break;
                if (total > 40L * 1024 * 1024) break;
            }
        } catch (Throwable ignored) { }
        long dt = System.currentTimeMillis() - start;
        if (dt < 10) dt = 10;
        double mbps = total * 8.0 / (dt / 1000.0) / 1000000.0;
        return "{\"ok\":true,\"mbps\":" + String.format(java.util.Locale.US, "%.2f", mbps)
                + ",\"bytes\":" + total + ",\"ms\":" + dt + "}";
    }

    private static boolean needPass(String path) {
        if (!path.startsWith("/api/action")) return false;
        String q = queryOf(path);
        String d = q.startsWith("do=") ? q.substring(3) : q;
        if (d.startsWith("pass") || d.startsWith("ap")) return true;
        if (d.startsWith("limit") || d.startsWith("max") || d.startsWith("theme")) return true;
        if (d.startsWith("heal") || d.startsWith("off") || d.startsWith("unoff")) return true;
        if (d.startsWith("reboot") || d.startsWith("block")) return true;
        return false;
    }

    private static void handle(Context ctx, Socket c) throws Exception {
        c.setSoTimeout(30000);
        InputStream in = c.getInputStream();
        StringBuilder head = new StringBuilder();
        int prev = 0, ch;
        while ((ch = in.read()) != -1) {
            head.append((char) ch);
            if (head.length() > 8192) break;
            if (ch == '\n' && prev == '\n') break;
            if (ch == '\n' && head.length() > 1 && head.charAt(head.length() - 2) == '\r') {
                // 兼容 CRLF 空行
            }
            prev = ch;
            if (head.toString().endsWith("\r\n\r\n") || head.toString().endsWith("\n\n")) break;
        }

        // 记录访问者，作为热点客户端列表的兜底数据源
        try {
            InetAddress ra = c.getInetAddress();
            if (ra != null) {
                String rip = ra.getHostAddress();
                if (ApProfile.inApSubnet(rip)) {
                    seenClients.put(rip, System.currentTimeMillis());
                }
            }
        } catch (Throwable ignored) { }

        String first = head.toString().split("\r?\n")[0];
        String path = "/";
        if (first != null && (first.startsWith("GET ") || first.startsWith("POST "))) {
            String[] p = first.split(" ");
            if (p.length > 1) path = p[1];
        }

        String body;
        String type = "application/json; charset=utf-8";
        String extra = "";
        String token = tokenOf(head.toString(), path);

        if (path.startsWith("/api/login")) {
            String pwd = param(queryOf(path), "pwd");
            if (!Panel.hasPass(ctx)) {
                body = "{\"ok\":true,\"msg\":\"这台机器还没设访问密码\",\"token\":\"\"}";
            } else if (Panel.authOk(ctx, pwd)) {
                extra = "Set-Cookie: ah=" + urlEnc(pwd) + "; Path=/; Max-Age=31536000\r\n";
                body = "{\"ok\":true,\"msg\":\"已登录\",\"token\":\"" + esc(pwd) + "\"}";
            } else {
                body = "{\"ok\":false,\"msg\":\"密码不对\"}";
            }
        } else if (needPass(path) && !Panel.authOk(ctx, token)) {
            body = "{\"ok\":false,\"msg\":\"需要访问密码\",\"needLogin\":true}";
        } else if (path.startsWith("/api/checkpass")) {
            String k = param(queryOf(path), "k");
            body = Panel.authOk(ctx, k)
                    ? "{\"ok\":true}"
                    : "{\"ok\":false,\"msg\":\"密码不对\"}";
        } else if (path.startsWith("/api/action")) {
            String qs = queryOf(path);
            String doWhat = qs.startsWith("do=") ? qs.substring(3) : qs;
            if (doWhat.startsWith("heal")) {
                if (HotspotService.INSTANCE != null) {
                    HotspotService.trigger();
                    body = "{\"ok\":true,\"msg\":\"已触发热点自愈\"}";
                } else {
                    body = "{\"ok\":false,\"msg\":\"无障碍服务未启用，请下拉控制中心手动点一下「个人热点」\"}";
                }
            } else if (doWhat.startsWith("reboot")) {
                String err = Reboot.reboot(ctx);
                body = err == null
                        ? "{\"ok\":true,\"msg\":\"重启指令已下发，页面即将断开\"}"
                        : "{\"ok\":false,\"msg\":\"" + esc(err) + "\"}";
            } else if (doWhat.startsWith("apinfo")) {
                Panel.setApInfo(ctx, param(doWhat, "ssid"), param(doWhat, "pwd"));
                body = "{\"ok\":true,\"msg\":\"已记住，刷新后二维码就有了\"}";
            } else if (doWhat.startsWith("ap")) {
                String ssid = param(doWhat, "ssid");
                String pwd = param(doWhat, "pwd");
                Panel.setApInfo(ctx, ssid, pwd);
                String err = HotspotService.changeAp(ssid, pwd);
                body = err == null
                        ? "{\"ok\":true,\"msg\":\"已下发，正在替你点设置页…\"}"
                        : "{\"ok\":false,\"msg\":\"" + esc(err) + "\"}";
            } else if (doWhat.startsWith("pass")) {
                Panel.setPass(ctx, param(doWhat, "v"));
                body = "{\"ok\":true,\"msg\":\"" + (Panel.hasPass(ctx) ? "访问密码已设置" : "访问密码已清除") + "\"}";
            } else if (doWhat.startsWith("theme")) {
                Panel.setTheme(ctx, param(doWhat, "v"));
                body = "{\"ok\":true,\"msg\":\"配色已切换\"}";
            } else if (doWhat.startsWith("limit")) {
                Panel.setLimitMB(ctx, ni(param(doWhat, "v"), 0));
                body = "{\"ok\":true,\"msg\":\"流量限额已保存\"}";
            } else if (doWhat.startsWith("max")) {
                Panel.setMaxClients(ctx, ni(param(doWhat, "v"), 0));
                Panel.setMaxDrop(ctx, "1".equals(param(doWhat, "drop")));
                body = "{\"ok\":true,\"msg\":\"连接数上限已保存\"}";
            } else if (doWhat.startsWith("note")) {
                Panel.setNote(ctx, param(doWhat, "mac"), param(doWhat, "name"));
                body = "{\"ok\":true,\"msg\":\"备注已保存\"}";
            } else if (doWhat.startsWith("rules")) {
                String err = Automation.save(ctx, param(doWhat, "json"));
                body = err == null
                        ? "{\"ok\":true,\"msg\":\"定时规则已保存\"}"
                        : "{\"ok\":false,\"msg\":\"" + esc(err) + "\"}";
            } else if (doWhat.startsWith("monthReset")) {
                long mrx = android.net.TrafficStats.getTotalRxBytes();
                long mtx = android.net.TrafficStats.getTotalTxBytes();
                Panel.resetMonth(ctx, mrx < 0 ? 0 : mrx, mtx < 0 ? 0 : mtx);
                body = "{\"ok\":true,\"msg\":\"本月流量已归零\"}";
            } else if (doWhat.startsWith("unoff")) {
                Panel.setManualOff(ctx, false);
                HotspotService.trigger();
                body = "{\"ok\":true,\"msg\":\"已解除锁定，正在重新打开热点\"}";
            } else if (doWhat.startsWith("statsClear")) {
                Stats.clear(ctx);
                body = "{\"ok\":true,\"msg\":\"曲线已清空\"}";
            } else if (doWhat.startsWith("sim")) {
                int slot = ni(param(doWhat, "slot"), 0);
                if (slot < 1 || slot > 4) {
                    body = "{\"ok\":false,\"msg\":\"卡槽号不对\"}";
                } else if (!SimInfo.hasSim(ctx, slot)) {
                    body = "{\"ok\":false,\"msg\":\"卡" + slot + " 没有插卡\"}";
                } else if (SimInfo.currentSlot(ctx) == slot) {
                    body = "{\"ok\":true,\"msg\":\"已经在用卡" + slot + "了\"}";
                } else {
                    String err = HotspotService.switchDataSim(slot);
                    body = err == null
                            ? "{\"ok\":true,\"msg\":\"正在切到卡" + slot + "，数据会断几秒\"}"
                            : "{\"ok\":false,\"msg\":\"" + esc(err) + "\"}";
                }
            } else if (doWhat.startsWith("speed")) {
                if (Spd.run) {
                    body = "{\"ok\":true,\"msg\":\"测速进行中\"}";
                } else {
                    startSpeedJob();
                    body = "{\"ok\":true,\"msg\":\"开始测速\"}";
                }
            } else if (doWhat.startsWith("block")) {
                String r = Panel.setBlock(ctx, param(doWhat, "v"));
                body = r == null
                        ? "{\"ok\":true,\"msg\":\"拉黑名单已更新\"}"
                        : "{\"ok\":false,\"msg\":\"" + esc(r) + "\"}";
            } else {
                body = "{\"ok\":false,\"msg\":\"unknown action\"}";
            }
        } else if (path.startsWith("/api/ul")) {
            body = speedUpload(in, contentLength(head.toString()));
        } else if (path.startsWith("/api/speedstate")) {
            body = Spd.json();
        } else if (path.startsWith("/api/status")) {
            body = statusJson(ctx);
        } else {
            body = page();
            type = "text/html; charset=utf-8";
        }

        byte[] b = body.getBytes("UTF-8");
        OutputStream os = c.getOutputStream();
        PrintWriter pw = new PrintWriter(os);
        pw.print("HTTP/1.1 200 OK\r\n");
        pw.print("Content-Type: " + type + "\r\n");
        pw.print("Content-Length: " + b.length + "\r\n");
        pw.print("Cache-Control: no-store\r\n");
        if (extra.length() > 0) pw.print(extra);
        pw.print("Connection: close\r\n\r\n");
        pw.flush();
        os.write(b);
        os.flush();
    }

    /* ---------------- 状态采集 ---------------- */

    private static boolean tetherUp() {
        try {
            Enumeration<NetworkInterface> es = NetworkInterface.getNetworkInterfaces();
            while (es.hasMoreElements()) {
                NetworkInterface ni = es.nextElement();
                if (!ni.isUp()) continue;
                String ifn = ni.getName();
                // v43：vivo 等机型热点挂在 ap0 上
                if (ifn == null || (!ifn.startsWith("wlan") && !ApProfile.isApIface(ifn))) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && ApProfile.apIp().equals(a.getHostAddress())) return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static String apIp() {
        try {
            Enumeration<NetworkInterface> es = NetworkInterface.getNetworkInterfaces();
            while (es.hasMoreElements()) {
                NetworkInterface ni = es.nextElement();
                if (!ni.isUp()) continue;
                String ifn = ni.getName();
                // v43：vivo 等机型热点挂在 ap0 上
                if (ifn == null || (!ifn.startsWith("wlan") && !ApProfile.isApIface(ifn))) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address) {
                        String ip = a.getHostAddress();
                        if (ip.startsWith(ApProfile.apPrefix())) return ip;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /**
     * 取热点已连接客户端。
     *
     * Android 9 以后普通应用读 /proc/net/arp 会被 SELinux 拦成空表，
     * 所以先试华为自带的 WifiManager#getStaList()（EMUI 私有方法，返回 StationInfo 列表），
     * 拿不到再退回读 ARP，两条路都失败就返回空数组，页面显示「暂无」而不是报错。
     */
    private static String clientsJson(Context ctx) {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<String, String>();
        java.util.LinkedHashMap<String, String> hist = new java.util.LinkedHashMap<String, String>();

        // 路线一：华为私有 getStaList（缺系统签名，多数机型拿不到，但拿到最准）
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                Class<?> cls = wm.getClass();
                java.lang.reflect.Method m = null;
                for (String name : new String[]{"getStaList", "getStaInfoList", "getStationList"}) {
                    try {
                        m = cls.getMethod(name);
                        if (m != null) break;
                    } catch (Throwable ignored) {
                    }
                }
                if (m != null) {
                    Object r = m.invoke(wm);
                    if (r instanceof List) {
                        for (Object o : (List<?>) r) {
                            if (o == null) continue;
                            String mac = callStr(o, "getMacAddress", "getHwAddr", "getMac");
                            String ip = callStr(o, "getIpAddress", "getIp", "getIP");
                            if (mac == null && ip == null) continue;
                            String key = (ip == null || ip.length() == 0) ? mac : ip;
                            if (key != null && !map.containsKey(key)) {
                                map.put(key, mac == null ? "" : mac);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 路线二：ip neigh（不写死 wlan0 —— vivo 的热点在 ap0 上，写死就永远空）
        // 状态词 REACHABLE/STALE/DELAY/PROBE 当作在线，FAILED/INCOMPLETE 当作已断开。
        String apIf = ApProfile.apIfaceName();
        try {
            Process np = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/ip", "neigh", "show"});
            BufferedReader nbr = new BufferedReader(new InputStreamReader(np.getInputStream()));
            String nline;
            int guard = 0;
            while ((nline = nbr.readLine()) != null && guard++ < 400) {
                String t = nline.trim();
                if (t.length() == 0) continue;
                String[] f = t.split("\\s+");
                if (f.length < 2) continue;
                String nip = f[0];
                if (!ApProfile.inApSubnet(nip)) continue;
                if (ApProfile.apIp().equals(nip)) continue;
                String nmac = "";
                String ndev = "";
                for (int k = 0; k < f.length; k++) {
                    if ("lladdr".equals(f[k]) && k + 1 < f.length) nmac = f[k + 1];
                    else if ("dev".equals(f[k]) && k + 1 < f.length) ndev = f[k + 1];
                }
                if (ndev.length() > 0 && apIf != null
                        && !ndev.equals(apIf) && !ndev.startsWith("wlan")
                        && !ApProfile.isApIface(ndev)) continue;
                String st = "";
                for (int k = f.length - 1; k >= 1; k--) {
                    String s = f[k].toUpperCase(java.util.Locale.ROOT);
                    if (s.equals("REACHABLE") || s.equals("STALE") || s.equals("DELAY")
                            || s.equals("PROBE") || s.equals("FAILED") || s.equals("INCOMPLETE")) {
                        st = s;
                        break;
                    }
                }
                boolean on = !st.equals("FAILED") && !st.equals("INCOMPLETE");
                if (on) {
                    if (!map.containsKey(nip)) map.put(nip, nmac);
                } else if (!map.containsKey(nip) && !hist.containsKey(nip)) {
                    hist.put(nip, nmac);
                }
            }
            nbr.close();
            np.destroy();
        } catch (Throwable ignored) {
        }

        // 路线三：/proc/net/arp（Android 9+ 多数机型已封）
        try {
            BufferedReader abr = new BufferedReader(new FileReader("/proc/net/arp"));
            String aline;
            boolean afirst = true;
            while ((aline = abr.readLine()) != null) {
                if (afirst) { afirst = false; continue; }
                String[] f = aline.trim().split("\\s+");
                if (f.length < 6) continue;
                if (apIf != null && !f[5].equals(apIf)
                        && !f[5].startsWith("wlan") && !ApProfile.isApIface(f[5])) continue;
                if (ApProfile.apIp().equals(f[0])) continue;
                if ("0x2".equals(f[2])) {
                    if (!map.containsKey(f[0])) map.put(f[0], f[3]);
                } else if (!map.containsKey(f[0])) {
                    // flags 不是 0x2 说明表项已失效（设备断了）；留在列表里但标成已断开。
                    // ip neigh 那边的 FAILED 行没有 lladdr，用 arp 里残留的 MAC 补上，好认是哪台。
                    String old = hist.get(f[0]);
                    if (old == null || old.length() == 0) hist.put(f[0], f[3]);
                }
            }
            abr.close();
        } catch (Throwable ignored) {
        }

        // 路线四：HTTP 访问记录（一定可用，但只证明「访问过」，不代表现在还连着）
        // 以前算 6 小时，结果设备早断了列表里还挂着半天；收到 30 分钟，且单独放 hist，
        // 输出时标 online:false，总览的台数不再把它算进去。
        long nowMs = System.currentTimeMillis();
        for (java.util.Map.Entry<String, Long> e : seenClients.entrySet()) {
            if (nowMs - e.getValue() > 30L * 60 * 1000) continue;
            if (map.containsKey(e.getKey()) || hist.containsKey(e.getKey())) continue;
            hist.put(e.getKey(), "");
        }

        List<String> out = new ArrayList<String>();
        for (java.util.Map.Entry<String, String> e : map.entrySet()) {
            String m = e.getValue() == null ? "" : e.getValue();
            if (m.length() > 0) {
                if (lastMac.size() > 64) lastMac.clear();
                lastMac.put(e.getKey(), m);
            }
            out.add(clientItem(e.getKey(), m, true));
        }
        for (java.util.Map.Entry<String, String> e : hist.entrySet()) {
            if (map.containsKey(e.getKey())) continue;
            String m = e.getValue() == null ? "" : e.getValue();
            if (m.length() == 0) {
                String old = lastMac.get(e.getKey());
                if (old != null) m = old;
            }
            out.add(clientItem(e.getKey(), m, false));
        }
        return "[" + join(out, ",") + "]";
    }

    /** 当前 ARP / 邻居表里的有效表项，元素 {ip, mac}。给拉黑判定用。 */
    static java.util.List<String[]> neighList() {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/ip", "neigh", "show"});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String ln;
            while ((ln = r.readLine()) != null) {
                if (ln.indexOf("lladdr") < 0) continue;
                if (ln.indexOf("FAILED") >= 0 || ln.indexOf("INCOMPLETE") >= 0) continue;
                String[] t = ln.trim().split("\\s+");
                if (t.length < 5) continue;
                String mac = "";
                for (int i = 0; i < t.length - 1; i++) {
                    if ("lladdr".equals(t[i])) { mac = t[i + 1]; break; }
                }
                out.add(new String[]{t[0], mac});
            }
            r.close();
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 单条设备记录：online=true 表示此刻还连着（ARP / ip neigh 有有效表项）。 */
    private static String clientItem(String kip, String kmac, boolean online) {
        String m = kmac == null ? "" : kmac;
        boolean ipLike = kip != null && kip.indexOf('.') > 0;
        return "{\"ip\":\"" + esc(ipLike ? kip : "")
                + "\",\"mac\":\"" + esc(m)
                + "\",\"ipKnown\":" + ipLike
                + ",\"online\":" + online + "}";
    }

    /** 反射调用无参字符串取值器，全部失败返回 null。 */
    private static String callStr(Object o, String... names) {
        for (String n : names) {
            try {
                java.lang.reflect.Method m = o.getClass().getMethod(n);
                Object v = m.invoke(o);
                if (v != null) {
                    String s = String.valueOf(v).trim();
                    if (s.length() > 0) return s;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 热点 SSID，华为通过 getWifiApConfiguration 暴露。 */
    private static String apSsid(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "";
            java.lang.reflect.Method m = wm.getClass().getMethod("getWifiApConfiguration");
            Object cfg = m.invoke(wm);
            if (cfg == null) return "";
            try {
                Object v = cfg.getClass().getField("SSID").get(cfg);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {
            }
            return callStr(cfg, "getSSID", "getSsid") == null ? "" : callStr(cfg, "getSSID", "getSsid");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 运营商名。v44 起三级兜底，因为 Android 11+ 的 getNetworkOperatorName()
     * 需要 READ_PHONE_STATE，普通装机拿不到：
     *   1. getNetworkOperatorName()  —— 有权限时最准
     *   2. getSimOperatorName()      —— 读 SIM 卡名，多数机型不需要权限
     *   3. getNetworkOperator()      —— 只有 MCC+MNC（如 46000），本地映射成中文名
     */
    private static String operatorName(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "";
            String s = tm.getNetworkOperatorName();
            if (s != null && s.trim().length() > 0) return s.trim();
            s = tm.getSimOperatorName();
            if (s != null && s.trim().length() > 0) return s.trim();
            return cnCarrier(tm.getNetworkOperator());
        } catch (Throwable t) {
            return "";
        }
    }

    /** MCC+MNC -> 中文运营商名。只认中国大陆 460xx，其余返回空。 */
    private static String cnCarrier(String mccMnc) {
        if (mccMnc == null) return "";
        String m = mccMnc.trim();
        if (m.length() < 5 || !m.startsWith("460")) return "";
        String r = m.substring(3);
        if (r.equals("00") || r.equals("02") || r.equals("04")
                || r.equals("07") || r.equals("08")) return "中国移动";
        if (r.equals("01") || r.equals("06") || r.equals("09")) return "中国联通";
        if (r.equals("03") || r.equals("05") || r.equals("11")) return "中国电信";
        if (r.equals("15")) return "中国广电";
        return "";
    }

    /** 网络制式。getDataNetworkType() 需要 READ_PHONE_STATE，失败就退到「是不是蜂窝网」。 */
    private static String networkType(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "";
            int t = 0;
            try {
                t = tm.getDataNetworkType();
            } catch (Throwable ignored) {
                // Android 11+ 没 READ_PHONE_STATE 会抛 SecurityException，继续走兜底
            }
            switch (t) {
                case TelephonyManager.NETWORK_TYPE_NR: return "5G NR";
                case TelephonyManager.NETWORK_TYPE_LTE: return "4G LTE";
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSDPA: return "3G HSPA";
                case TelephonyManager.NETWORK_TYPE_UMTS: return "3G UMTS";
                case TelephonyManager.NETWORK_TYPE_EDGE: return "2G EDGE";
                case TelephonyManager.NETWORK_TYPE_GPRS: return "2G GPRS";
                default: break;
            }
            if (t > 0) return "type" + t;
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network n = cm.getActiveNetwork();
                NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
                if (nc != null) {
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "蜂窝数据";
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WiFi";
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "有线";
                }
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }
    private static String join(List<String> xs, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(xs.get(i));
        }
        return sb.toString();
    }

    /** JSON 字符串转义：运营商名、SSID 里可能出现引号或反斜杠。 */
    /** 从 query 串里取一个参数（先做 URL 解码，中文 SSID 才不出乱码） */
    private static String param(String q, String key) {
        if (q == null || q.length() == 0) return "";
        String[] parts = q.split("&");
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i];
            int eq = s.indexOf('=');
            String k = eq >= 0 ? s.substring(0, eq) : s;
            if (key.equals(k)) return urlDec(eq >= 0 ? s.substring(eq + 1) : "");
        }
        return "";
    }

    private static String urlDec(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    private static String urlEnc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 取 "?a=1&b=2" 里的查询串部分 */
    private static String queryOf(String path) {        if (path == null) return "";
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(q + 1) : "";
    }

    /** 访问凭证：优先看 Cookie ah=，其次看 ?k= */
    private static String tokenOf(String head, String path) {
        try {
            String[] lines = head.split("\r?\n");
            for (int i = 0; i < lines.length; i++) {
                String ln = lines[i];
                if (ln == null) continue;
                if (!ln.toLowerCase(java.util.Locale.US).startsWith("cookie:")) continue;
                String[] parts = ln.substring(7).split(";");
                for (int k = 0; k < parts.length; k++) {
                    String p = parts[k].trim();
                    if (p.startsWith("ah=")) return urlDec(p.substring(3));
                }
            }
        } catch (Throwable ignored) { }
        return param(queryOf(path), "k");
    }

    private static int ni(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 已连设备数（给每分钟的曲线采样用） */
    static int clientCount(Context ctx) {
        try {
            return countOnline(clientsJson(ctx));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 从 clientsJson 结果里数 online:true 的条数。历史记录不算在线。 */
    private static int countOnline(String j) {
        int n = 0;
        int i = -1;
        if (j == null) return 0;
        while ((i = j.indexOf("\"online\":true", i + 1)) >= 0) n++;
        return n;
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static String loadAvg() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/loadavg"));
            String s = br.readLine();
            if (s != null) {
                String[] f = s.trim().split("\\s+");
                if (f.length > 0) return f[0];
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) { }
        }
        return "0";
    }

    private static String statusJson(Context ctx) {
        History.sample(ctx);
        int level = -1, temp = -1, status = -1, plugged = -1;
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent b = ctx.registerReceiver(null, f);
            if (b != null) {
                level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) level = level * 100 / scale;
                temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            }
        } catch (Throwable ignored) { }

        // 系统热状态（API 29+），内核 thermal 上报的真实机身热度分级
        int thermalStatus = -1;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.os.PowerManager pmw =
                        (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pmw != null) thermalStatus = pmw.getCurrentThermalStatus();
            }
        } catch (Throwable ignored) { }

        long uptime = SystemClock.elapsedRealtime() / 1000L;

        long memTotal = 0, memAvail = 0;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            memTotal = mi.totalMem / (1024 * 1024);
            memAvail = mi.availMem / (1024 * 1024);
        } catch (Throwable ignored) { }

        long rx = 0, tx = 0;
        try {
            rx = TrafficStats.getTotalRxBytes();
            tx = TrafficStats.getTotalTxBytes();
            if (rx < 0) rx = 0;
            if (tx < 0) tx = 0;
        } catch (Throwable ignored) { }

        // 瞬时速率 = 两次采样的字节差 / 时间差
        long now = SystemClock.elapsedRealtime();
        long rxRate = 0, txRate = 0;
        if (lastTs > 0 && now > lastTs) {
            long dt = now - lastTs;
            if (rx >= lastRx) rxRate = (rx - lastRx) * 1000L / dt;
            if (tx >= lastTx) txRate = (tx - lastTx) * 1000L / dt;
        }
        lastRx = rx; lastTx = tx; lastTs = now;

        boolean up = tetherUp();
        String clients = clientsJson(ctx);
        int n = countOnline(clients);
        int nKnown = clients.equals("[]") ? 0 : clients.split("\\{").length - 1;

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"online\":true,");
        sb.append("\"source\":\"phone\",");
        sb.append("\"acc\":").append(HotspotService.INSTANCE != null).append(",");
        sb.append("\"ts\":").append(System.currentTimeMillis()).append(",");
        sb.append("\"owner\":").append(Reboot.isOwner(ctx)).append(",");
        sb.append("\"hist\":").append(History.json(ctx)).append(",");
        sb.append("\"ap\":{\"busy\":").append(HotspotService.apBusyNow())
                .append(",\"msg\":\"").append(esc(HotspotService.apState())).append("\"},");
        sb.append("\"hotspot\":{");
        sb.append("\"up\":").append(up).append(",");
        sb.append("\"state\":\"").append(up ? "TetheredState" : "Down").append("\",");
        sb.append("\"ssid\":\"").append(esc(apSsid(ctx))).append("\",");
        sb.append("\"ip\":\"").append(apIp()).append("\",");
        sb.append("\"clients\":").append(n);
        sb.append(",\"clientsKnown\":").append(nKnown);
        sb.append("},");
        sb.append("\"net\":{");
        sb.append("\"operator\":\"").append(esc(operatorName(ctx))).append("\",");
        sb.append("\"netType\":\"").append(esc(networkType(ctx))).append("\",");
        sb.append("\"rxRate\":").append(rxRate).append(",");
        sb.append("\"txRate\":").append(txRate);
        sb.append("},");
        sb.append("\"sim\":").append(SimInfo.json(ctx)).append(",");
        sb.append("\"battery\":{");
        sb.append("\"level\":").append(level).append(",");
        sb.append("\"temp\":").append(temp >= 0 ? (temp / 10) : -1).append(",");
        sb.append("\"charging\":").append(status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL).append(",");
        sb.append("\"usbPowered\":").append(plugged == BatteryManager.BATTERY_PLUGGED_USB).append(",");
        sb.append("\"thermalStatus\":").append(thermalStatus);
        sb.append("},");
        sb.append("\"system\":{");
        sb.append("\"uptimeSec\":").append(uptime).append(",");
        sb.append("\"load1\":").append(loadAvg()).append(",");
        sb.append("\"cpuCount\":").append(Runtime.getRuntime().availableProcessors()).append(",");
        sb.append("\"memTotalMB\":").append(memTotal).append(",");
        sb.append("\"memAvailMB\":").append(memAvail).append(",");
        sb.append("\"memPct\":").append(memTotal > 0 ? (int) ((memTotal - memAvail) * 100 / memTotal) : 0);
        sb.append("},");
        sb.append("\"traffic\":{\"rxBytes\":").append(rx).append(",\"txBytes\":").append(tx).append("},");

        // 本月流量 / 限额 / 连接数上限 / 24 小时曲线
        long monthBytes = Panel.monthBytes(ctx, rx, tx);
        int limitMB = Panel.limitMB(ctx);
        int maxC = Panel.maxClients(ctx);
        sb.append("\"cfg\":").append(Panel.cfgJson(ctx, monthBytes)).append(",");
        sb.append("\"limitHit\":").append(limitMB > 0 && monthBytes >= (long) limitMB * 1024L * 1024L).append(",");
        sb.append("\"overCount\":").append(maxC > 0 && n > maxC).append(",");
        sb.append("\"stats\":").append(Stats.json(ctx)).append(",");
        sb.append("\"devices\":").append(clients);
        sb.append("}");
        try { Stats.sample(ctx, rx, tx, n, up); } catch (Throwable ignored) { }
        try { Panel.enforceBlock(ctx); } catch (Throwable ignored) { }
        return sb.toString();
    }
    /* ---------------- 登录页（设了访问密码才出现） ---------------- */

    private static final String LOGIN_PAGE =
        "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>"
        + "<meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'>"
        + "<title>热点控制台 · 需要密码</title><style>"
        + "*{box-sizing:border-box;margin:0;padding:0}"
        + "body{background:radial-gradient(900px 500px at 50% -10%,#13203a,#07090f 62%);color:#e8eef8;"
        + "font:14px/1.55 -apple-system,'Segoe UI','Microsoft YaHei',sans-serif;min-height:100vh;"
        + "display:grid;place-items:center;padding:24px}"
        + ".box{width:100%;max-width:330px;background:linear-gradient(160deg,#111621,#0d121b);"
        + "border:1px solid #1e2634;border-radius:16px;padding:20px}"
        + "h1{font-size:15px;margin-bottom:6px}"
        + ".hint{font-size:11.5px;color:#7d8ba1;line-height:1.7}"
        + "input{width:100%;background:#0d131f;border:1px solid #1e2634;color:#e8eefc;padding:11px 13px;"
        + "border-radius:11px;font-size:15px;font-family:inherit;outline:none;margin-top:13px}"
        + "input:focus{border-color:#2f6fed}"
        + "button{width:100%;background:linear-gradient(135deg,#1d4ed8,#0ea5a0);color:#fff;border:0;"
        + "border-radius:14px;padding:12px;font-size:14px;font-weight:700;font-family:inherit;margin-top:11px;box-shadow:0 2px 10px rgba(29,78,216,0.22);transition:transform .09s ease,box-shadow .09s ease,opacity .09s ease}"
        + ".err{color:#f0426e;font-size:11.5px;margin-top:9px;min-height:15px}"
        + ".gaugebox{margin:8px 0 2px}.sprow{display:flex;align-items:center;gap:8px;margin:7px 0}.sprow .spl{flex:0 0 34px;font-size:10px;color:var(--dim)}.sprow svg{flex:1;height:34px;background:rgba(148,163,184,.06);border-radius:8px}.g3{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px;margin:10px 0}.mini{background:rgba(148,163,184,.08);border-radius:12px;padding:9px 8px;text-align:center}.mini .label{margin:0 0 3px}.mini .v{font-size:16px;font-weight:700;line-height:1.15}.mini .v .u{font-size:9.5px;font-weight:400;color:var(--dim);margin-left:2px}#sbtn{margin-top:6px}</style></head><body><div class='box'>"
        + "<h1>HONOR X10 热点控制台</h1>"
        + "<div class='hint'>这台机器给面板设了访问密码，输入后才能查看状态和操作。</div>"
        + "<input id='p' type='password' placeholder='访问密码' autocomplete='current-password'>"
        + "<button id='b'>进入</button><div class='err' id='e'></div></div><script>"
        + "var p=document.getElementById('p'),b=document.getElementById('b'),e=document.getElementById('e');"
        + "function go(){var v=p.value;if(!v){e.textContent='请输入密码';return}"
        + "b.disabled=true;e.textContent='';"
        + "fetch('/api/login?pwd='+encodeURIComponent(v)).then(function(r){return r.json()}).then(function(a){"
        + "if(a.ok){location.href='/'}else{e.textContent=a.msg||'密码不对';b.disabled=false}})"
        + ".catch(function(t){e.textContent='连接失败: '+t.message;b.disabled=false})}"
        + "b.onclick=go;p.onkeydown=function(ev){if(ev.key==='Enter')go()};p.focus();"
        + "</script></body></html>";

    /* ---------------- 主页面 ---------------- */

        /**
     * v44：标题里的机型名跟着实际设备走，不再写死 HONOR X10。
     * logo 取型号首段（vivo V2282A -> V228，ALI-AN00 -> ALI），最多 4 字符。
     */
    private static String page() {
        String mk = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String md = Build.MODEL == null ? "" : Build.MODEL.trim();
        String full;
        if (mk.length() == 0) full = md;
        else if (md.toLowerCase().startsWith(mk.toLowerCase())) full = md;
        else full = mk + " " + md;
        full = full.trim();
        if (full.length() == 0) full = "Android";

        String logo = md.length() == 0 ? mk : md;
        int cut = logo.length();
        for (int i = 0; i < logo.length(); i++) {
            char ch = logo.charAt(i);
            if (ch == '-' || ch == ' ' || ch == '_') { cut = i; break; }
        }
        logo = logo.substring(0, cut).toUpperCase(Locale.US);
        if (logo.length() == 0) logo = "AH";
        if (logo.length() > 4) logo = logo.substring(0, 4);

        return PAGE.replace("HONOR X10", full).replace(">X10<", ">" + logo + "<");
    }

    private static final String PAGE =
          "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'><title>HONOR X10 热点控制台</title><style>:root{--bg1:#13203a;--bg2:#07090f;--card1:#111621;--card2:#0d121b;--line:#1e2634;--txt:#e8eef8;--dim:#7d8ba1;--input:#0d131f;--bar:#1b2331;--accent:#22d3a6;--accent2:#3b82f6;--red:#e11d48}body.light{--bg1:#dfe9fb;--bg2:#f5f8fd;--card1:#ffffff;--card2:#f8fafd;--line:#e2e8f2;--txt:#101828;--dim:#64748b;--input:#f1f5fb;--bar:#e6ecf5;--accent:#0f9e7f;--accent2:#2563eb;--red:#dc2626}*{box-sizing:border-box;margin:0;padding:0}body{background:radial-gradient(900px 500px at 50% -10%,var(--bg1),var(--bg2) 62%);color:var(--txt);font:13px/1.5 -apple-system,'Segoe UI','Microsoft YaHei',sans-serif;min-height:100vh;padding:12px 11px 30px}.wrap{max-width:560px;margin:0 auto}header{display:flex;align-items:center;gap:10px"
        + ";margin-bottom:9px}.logo{width:34px;height:34px;border-radius:10px;background:linear-gradient(135deg,#3b82f6,#22d3a6);display:grid;place-items:center;font-weight:800;font-size:11px;color:#04121a}h1{font-size:13.5px;font-weight:700}.sub{font-size:10.5px;color:var(--dim);margin-top:1px}.dot{margin-left:auto;width:8px;height:8px;border-radius:50%;background:var(--accent);box-shadow:0 0 0 0 rgba(34,211,166,.6);animation:p 1.8s infinite}@keyframes p{0%{box-shadow:0 0 0 0 rgba(34,211,166,.5)}70%{box-shadow:0 0 0 8px rgba(34,211,166,0)}100%{box-shadow:0 0 0 0 rgba(34,211,166,0)}}.tabs{display:flex;gap:4px;margin-bottom:9px;position:sticky;top:0;z-index:9;background:var(--bg2);padding:5px 0;border-bottom:1px solid var(--line)}.tab{flex:1;text-align:center;padding:7px 2px;border-radius:9px;font-size:12px;color:var(--dim);border:1px solid transparent;cursor:pointer;user-select:none}.tab.on{color:v"
        + "ar(--txt);background:var(--card1);border-color:var(--line);font-weight:700}.pane{display:none}.pane.on{display:block}.card{background:linear-gradient(160deg,var(--card1),var(--card2));border:1px solid var(--line);border-radius:13px;padding:11px;margin-bottom:8px}"
        + ".grp{background:linear-gradient(160deg,var(--card1),var(--card2));border:1px solid var(--line);border-radius:13px;margin-bottom:8px;overflow:hidden}.grp>summary{list-style:none;cursor:pointer;padding:13px;font-size:13.5px;font-weight:600;display:flex;align-items:center;justify-content:space-between;color:var(--txt)}.grp>summary::-webkit-details-marker{display:none}.grp>summary:after{content:'▾';font-size:11px;color:var(--dim);transition:transform .15s}.grp[open]>summary:after{transform:rotate(180deg)}.gbody{padding:0 13px 14px}.seph{height:1px;background:var(--line);margin:15px 0}.qrbox{display:flex;justify-content:center;padding:12px 0}.qrbox svg{width:186px;height:186px;background:#fff;border-radius:10px;padding:9px}.kv{display:flex;justify-content:space-between;align-items:center;padding:8px 0;border-bottom:1px solid var(--line)}.kv:last-of-type{border-bottom:none}.kv b{font-weight:600;color:var(--txt);word-break:break-all;text-align:right}.kv span{color:var(--dim);font-size:12px}.label{font-size:10.5px;color:var(--dim);letter-spacing:.5px;margin-bottom:4px;font-weight:600}.value{font-size:20px;font-weight:700;font-variant-numeric:tabular-nums}.value .u{font-size:11px;color:var(--dim);margin-left:2px;font-weight:500}.grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}.bar{height:4px;border-radius:3px;background:var(--bar);margin-top:6px;overflow:hidden}.bar>i{display:block;height:100%;background:linear-gradient(90deg,var(--accent2),var(--accent));transition:width .5s}.hint{font-size:10.8px;color:var(--dim);margin-top:4px;line-height:1.6}.hero{display:flex;align-items:center;gap:11px}.ring{width:46px;"
        + "height:46px;border-radius:50%;display:grid;place-items:center;font-size:18px;background:radial-gradient(circle at 35% 30%,#1b2a3f,#0c1420);border:2px solid var(--line)}body.light .ring{background:radial-gradient(circle at 35% 30%,#eef4ff,#dbe7f8)}.hero.up .ring{border-color:rgba(34,211,166,.55);box-shadow:0 0 18px rgba(34,211,166,.2)}.big{font-size:19px;color:var(--accent)}table{width:100%;border-collapse:collapse;font-size:12px;table-layout:fixed}th:nth-child(1){width:36%}th:nth-child(2){width:38%}th:nth-child(3){width:26%}th{text-align:left;color:var(--dim);font-size:10px;padding:5px 5px;border-bottom:1px solid var(--line)}td{padding:6px 5px;border-bottom:1px solid rgba(30,38,52,.5);font-family:ui-monospace,Consolas,monospace;font-size:10.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}td:nth-child(3){white-space:normal;font-family:inherit;font-size:11.5px;overflow:visible}body.light td{border-bottom-color:#eef2f8}tr:last-child td{border-bottom:none}button{width:100%;background:linear-gradient(135deg,#1d4ed8,#0ea5a0);color:#fff;border:0;border-radius:10px;padding:10px;font-size:12.5px;font-we"
        + "ight:700;font-family:inherit;margin-top:7px}button:active{opacity:.92;transform:translateY(1.5px) scale(.985);box-shadow:inset 0 2px 9px rgba(0,0,0,0.3)}button.ghost{background:transparent;border:1px solid var(--line);color:var(--dim);font-weight:600}button.ghost:active{box-shadow:inset 0 2px 7px rgba(0,0,0,0.22)}button:disabled{opacity:.35;filter:grayscale(1);box-shadow:none}button.danger{background:var(--red)}.bs{padding:6px;font-size:11px;border-radius:8px;margin-top:0}.bg{padding:6px;font-size:11px;border-radius:8px;margin-top:0;background:transparent;border:1px solid var(--line);color:var(--dim);font-weight:600}input,select{width:100%;background:var(--input);border:1px solid var(--line);color:var(--txt);padding:9px 11px;border-radius:9px;font-size:12.5px;font-family:inherit;outline:none;margin-top:5px}input:focus,select:focus{border-color:#2f6fed}input::placeholder{color:var(--dim);opacity:.75}.row{display:flex;gap:7px;align-items:center}.row>*{flex:1}.toast{position:fixed;left:50%;bottom:18px;transform:translate(-50%,70px);background:var(--card1);border:1px solid var(--line);padding:10px 1"
        + "6px;border-radius:11px;font-size:12.5px;opacity:0;transition:.28s;pointer-events:none;z-index:99;max-width:90vw}.toast.show{opacity:1;transform:translate(-50%,0)}.warn{background:rgba(225,29,72,.12);border:1px solid rgba(225,29,72,.4);color:#ff6b8a;border-radius:10px;padding:8px 10px;font-size:11.3px;margin-bottom:8px;line-height:1.6}body.light .warn{color:#c81e45}.chart{width:100%;height:110px;display:block;margin-top:5px}.tag{display:inline-block;font-size:9.5px;padding:1px 5px;border-radius:5px;background:var(--bar);color:var(--dim)}.gaugebox{margin:8px 0 2px}.sprow{display:flex;align-items:center;gap:8px;margin:7px 0}.sprow .spl{flex:0 0 34px;font-size:10px;color:var(--dim)}.sprow svg{flex:1;height:34px;background:rgba(148,163,184,.06);border-radius:8px}.g3{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px;margin:10px 0}.mini{background:rgba(148,163,184,.08);border-radius:12px;padding:9px 8px;text-align:center}.mini .label{margin:0 0 3px}.mini .v{font-size:16px;font-weight:700;line-height:1.15}.mini .v .u{font-size:9.5px;font-weight:400;color:var(--dim);margin-left:2px}#sbtn{margin-top:6px}</style></head><body><div class='wrap'><header><div class='logo'>X10</div><div><h1>HONOR X10 热点控制台</h1><div class='sub' id='sub'>读取中…</div></div><div class='dot'></div></header><div class='tabs'><div class='tab on' data-p='ov'>总览</div><div class='tab' data-p='dev'>测速</div><div class='tab' id='tabset' data-p='set'>设置</div><div class='tab' data-p='share'>分享</div><div cla"
        + "ss='tab' data-p='log'>记录</div></div><div class='pane on' id='ov'><div class='warn' id='warn' style='display:none'></div><div class='card'><div class='hero' id='hero'><div class='ring'>📶</div><div><div class='label'>个人热点</div><div class='value big' id='hs'>—</div><div class='hint' id='hsh'>—</div></div></div></div><div class='grid'><div class='card'><div class='label'>电池</div><div class='value'><span id='b'>—</span><span class='u'>%</span></div><div class='bar'><i id='bb'></i></div><div class='hint' id='bh'>—</div></div><div class='card'><div class='label'>电池温度</div><div class='value'><span id='t'>—</span><span class='u'>°C</span></div><div class='hint' id='th'>—</div></div><div class='card'><div class='label'>访问设备</div><div class='value'><span id='c'>—</span><span class='u'>台</span></div><div class='hint' id='ch'>—</div></div><div class='card'><div class='label'>内存</div><div class='valu"
        + "e'><span id='m'>—</span><span class='u'>%</span></div><div class='bar'><i id='mb'></i></div><div class='hint' id='mh'>—</div></div></div><div class='grid'><div class='card'><div class='label'>移动网络</div><div class='value' id='nt' style='font-size:14px'>—</div><div class='hint' id='op'>—</div><div class='row' id='simrow' style='display:none;margin-top:7px'><button class='bs' id='sim1' onclick='pickSim(1)'>卡1</button><button class='bs' id='sim2' onclick='pickSim(2)'>卡2</button></div></div><div class='card'><div class='label'>实时速率</div><div class='value' id='rate' style='font-size:12.5px'>—</div><div class='hint' id='rh'>下行 / 上行</div></div></div><div class='card'><div class='label'>运行时长</div><div class='value' id='up' style='font-size:16px'>—</div><div class='hint'>累计流量 ↓<span id='rx'>—</span> ↑<span id='tx'>—</span></div></div><div class='card' id='lc' style='display:none'><div class='label'>本月流量</div><div class='value' style='font-size:16px'><span id='mu'>—</span><span class='u'>/ <span id='lm'>—</span></span></div><div class='bar'><i id='lb'></i></div><div class='hint' id='lh'>"
        + "—</div></div></div><div class='pane' id='dev'><div class='card'><div style='display:flex;align-items:center;gap:8px'><div class='label' style='margin:0;flex:1'>网速测试</div><span class='tag' id='stag'>未开始</span></div>"
+ "<div class='gaugebox'><svg viewBox='0 0 240 200' style='width:100%;height:auto;display:block'><defs><linearGradient id='sgg' x1='0' y1='1' x2='1' y2='0'><stop offset='0%' stop-color='#22d3a6'/><stop offset='100%' stop-color='#38bdf8'/></linearGradient></defs>"
+ "<circle cx='120' cy='118' r='88' fill='none' stroke='rgba(148,163,184,.18)' stroke-width='14' stroke-linecap='round' stroke-dasharray='414.7 552.9' transform='rotate(135 120 118)'/>"
+ "<circle id='svarc' cx='120' cy='118' r='88' fill='none' stroke='url(#sgg)' stroke-width='14' stroke-linecap='round' stroke-dasharray='0.01 552.9' opacity='0' transform='rotate(135 120 118)'/>"
+ "<g id='sgt' font-size='8.5' fill='#64748b' text-anchor='middle'></g>"
+ "<line id='sndl' x1='120' y1='118' x2='120' y2='58' stroke='#22d3a6' stroke-width='3' stroke-linecap='round'/>"
+ "<circle cx='120' cy='118' r='5.5' fill='#22d3a6'/>"
+ "<text id='svnum' x='120' y='170' text-anchor='middle' font-size='26' font-weight='700' fill='currentColor'>0</text>"
+ "<text x='120' y='184' text-anchor='middle' font-size='9.5' fill='#94a3b8'>Mbps</text></svg></div>"
+ "<div class='sprow'><span class='spl'>下载</span><svg viewBox='0 0 200 40' preserveAspectRatio='none'><polyline id='sdp' fill='none' stroke='#22d3a6' stroke-width='1.6' points=''/></svg></div>"
+ "<div class='sprow'><span class='spl'>上行</span><svg viewBox='0 0 200 40' preserveAspectRatio='none'><polyline id='sup2' fill='none' stroke='#38bdf8' stroke-width='1.6' points=''/></svg></div>"
+ "<div class='g3'><div class='mini'><div class='label'>时延</div><div class='v'><span id='sping'>—</span><span class='u'>ms</span></div></div>"
+ "<div class='mini'><div class='label'>↓ 下载</div><div class='v'><span id='sdown'>—</span></div></div>"
+ "<div class='mini'><div class='label'>↑ 上行</div><div class='v'><span id='sup'>—</span></div></div></div>"
+ "<button id='sbtn' onclick='runSpeed()'>开始测速</button><div class='hint' id='sph'>点按钮开始，约 12 秒。延迟与下载走移动网络（6 路并发取稳态）。注意：系统对 App 内连接有限速，测得值会明显低于浏览器里的真实速度，只作相对参考。</div></div></div><div class='pane' id='set'>"
        + "<details class='grp' open><summary>常用</summary><div class='gbody'>"
        + "<div class='label'>热点名称与密码</div>"
        + "<div class='hint'>改完热点会重开一次，已连设备掉线后自动重连。密码 8–63 位，哪项留空就不动哪项。</div>"
        + "<input id='ssid' placeholder='热点名称（不改就留空）' autocomplete='off'>"
        + "<input id='pwd' placeholder='新密码（不改就留空）' autocomplete='off'>"
        + "<button onclick='saveAp()'>保存并重开热点</button>"
        + "<div class='hint' id='aph'>—</div>"
        + "</div></details>"
        + "<details class='grp'><summary>安全</summary><div class='gbody'>"
        + "<div class='label'>面板访问密码</div>"
        + "<div class='hint'>设了之后，点「设置」页签要输密码才能进来。留空保存 = 取消密码。</div>"
        + "<input id='apw' type='password' placeholder='新访问密码（留空=取消）' autocomplete='off'>"
        + "<button onclick='savePass()'>保存访问密码</button>"
        + "<div class='hint' id='pwh'>—</div>"
        + "<div class='seph'></div>"
        + "<div class='label'>拉黑设备</div>"
        + "<div class='hint'>本机没有 root，没法直接封包。加进名单后，只要它连上来就会重开一次热点把它挤掉，对方每次重连都会被踢。</div>"
        + "<input id='blk' placeholder='输 IP 或 MAC，例如 192.168.43.247' autocomplete='off'>"
        + "<div class='row'><button onclick='addBlock()'>加入黑名单</button><button class='ghost' onclick='clearBlock()'>清空名单</button></div>"
        + "<div id='bll' class='hint'>名单为空</div>"
        + "<div class='seph'></div>"
        + "<div class='label'>定时任务</div>"
        + "<div class='hint'>每天到点执行一次。关热点之后看门狗不会再自动拉回来，要到「系统 → 恢复自动守护」或加一条开热点规则才恢复。</div>"
        + "<div class='row'><input id='rt' type='time' value='04:00'><select id='ra'><option value='reboot'>重启手机</option><option value='off'>关热点</option><option value='on'>开热点</option></select></div>"
        + "<button onclick='addRule()'>添加规则</button>"
        + "<div id='rl'><div class='hint'>还没有规则</div></div>"
        + "<div class='hint' id='clh'>—</div>"
        + "</div></details>"
        + "<details class='grp'><summary>限额</summary><div class='gbody'>"
        + "<div class='label'>每月流量限额</div>"
        + "<div class='hint'>填 0 = 不限。超了只在总览顶部显示红色提醒，不会自动断网。</div>"
        + "<input id='lim' type='number' min='0' placeholder='例如 10240（MB）'>"
        + "<div class='row'><button onclick='saveLimit()'>保存限额</button><button class='ghost' onclick='resetMonth()'>本月归零</button></div>"
        + "<div class='hint' id='lmh'>—</div>"
        + "<div class='seph'></div>"
        + "<div class='label'>最大连接设备数</div>"
        + "<div class='hint'>系统没有这个开关，这里只能「数一数 + 提醒」；勾上自动断网，超限时会短暂关一次热点再打开。填 0 = 不限。</div>"
        + "<input id='maxc' type='number' min='0' max='32' placeholder='例如 5'>"
        + "<label class='hint' style='display:flex;gap:7px;align-items:center;margin-top:7px'><input type='checkbox' id='maxd' style='width:auto;margin:0'> 超限自动断一次热点</label>"
        + "<button onclick='saveMax()'>保存上限</button>"
        + "<div class='hint' id='mxh'>—</div>"
        + "</div></details>"
        + "<details class='grp'><summary>外观</summary><div class='gbody'>"
        + "<div class='label'>配色</div>"
        + "<div class='row'><button class='ghost' onclick='setTheme(0)'>深色</button><button class='ghost' onclick='setTheme(1)'>浅色</button></div>"
        + "</div></details>"
        + "<details class='grp'><summary>系统</summary><div class='gbody'>"
        + "<div class='label'>热点控制</div>"
        + "<div class='hint' id='rbh'>—</div>"
        + "<button onclick='heal()'>检查热点状态</button>"
        + "<div class='row'><button class='ghost' onclick='turnOff()'>关闭热点</button><button class='ghost' onclick='unoff()'>恢复自动守护</button></div>"
        + "<button class='danger' onclick='rebootPhone()'>重启手机</button>"
        + "</div></details>"
        + "</div>"
        + "<div class='pane' id='share'>"
        + "<div class='card'><div class='label'>扫码连热点</div>"
        + "<div class='hint' style='margin-top:4px'>本机读不到热点名密码（Android 10 限制），自己填一次，之后一直记住。</div>"
        + "<input id='shsi' placeholder='热点名称（SSID）' />"
        + "<input id='shpi' placeholder='热点密码' />"
        + "<button class='bs' onclick='saveApInfo()'>保存并生成二维码</button>"
        + "<div id='qrbox' class='qrbox'></div>"
        + "<div class='hint'>iPhone 相机直连，安卓扫码后手动输密码</div></div>"
        + "<div class='card'><div class='label'>热点信息</div>"
        + "<div class='kv'><span>名称</span><b id='shs'>—</b></div>"
        + "<div class='kv'><span>密码</span><b id='shp'>—</b></div>"
        + "<button onclick='copyWifi()'>复制名称和密码</button></div>"
        + "</div>"
        + "<div class='pane' id='log'><div class='card'><div class='label'>已连接设备</div><table><thead><tr><th>IP</th><th>MAC</th><th>备注</th></tr></thead><tbody id='dt'><tr><td colspan='3' class='hint'>暂无</td></tr></tbody></table><div class='hint' style='margin-top:6px'>点一行给它起个名字，之后就一直显示这个名。变灰的行表示已经断开（ARP 里已经没有它了）。IP 来自 ARP / IP 邻居 / 访问记录三源合并；MAC 受 Android 10 权限限制可能为空。</div></div><div class='card'><div style='display:flex;align-items:center;gap:8px'><div class='label' style='margin:0;flex:1'>24 小时流量曲线</div><button class='bs' id='cvm' style='width:auto;flex:0 0 auto;padding:5px 12px;margin:0' onclick='toggleChart()'>柱形</button></div><svg class='chart' id='cvs' viewBox='0 0 300 116' preserveAspectRatio='none'></svg><div class='hint' id='cvh'>每 5 分钟采一个点</div><button class='ghost' onclick='clearStats()'>清空曲线</button></div><div class='card'><div class='label'>运行记录</div><div class='hint'>最近 20 条热点的开关变化，用来查「几点断过」</div><table style='margin-top:5px'><thead><tr><th>时间</th><th>事件</th><th>持续</th></tr></thead><tbody id='hb'><tr><td colspan='3' class='hint'>正在记录…</td></tr></tbody></table></div></div><div class='hint' style='text-align:center;margin-top:12px'>本页由手机自己提供 · 无需电脑 · 每 2 秒刷新</div></div><div class='toast' id='toast'></div><script>var $=function(i){return document.getElementById(i)};var CUR=null,RULES=[],TAB='ov';function fb(b){if(b==nu"
        + "ll||b<0)return '—';var u=['B','KB','MB','GB','TB'],i=0,v=b;while(v>=1024&&i<u.length-1){v/=1024;i++}return v.toFixed(i?1:0)+u[i]}function fu(s){if(s==null)return '—';var d=Math.floor(s/86400),h=Math.floor(s%86400/3600),m=Math.floor(s%3600/60);return d?d+'天'+h+'时':(h?h+'时'+m+'分':m+'分')}function fsp(b){if(!b||b<0)return '0 KB/s';if(b>=131072)return (b/131072).toFixed(2)+' Mbps';if(b>=1024)return (b/1024).toFixed(1)+' KB/s';return b+' B/s'}function toast(t){var e=$('toast');e.textContent=t;e.className='toast show';clearTimeout(e._h);e._h=setTimeout(function(){e.className='toast'},2600)}function thermalText(s){var m=['正常','轻度升温','中度升温','严重','危重','关机级','未知'];return (s>=0&&s<=6)?m[s]:'未知'}var PW='',UNLOCKED=false,SPEEDING=false;function askPass(){var v=prompt('输入面板访问密码（只有改设置时才要）：');if(v===null)return;v=v.trim();if(!v){toast('密码不能为空');return}fetch('/api/checkpass?k='+encodeURIComponent(v)).then(function(r){return r.json()}).then(function(a){if(a.ok){PW=v;UNLOCKED=true;toast('已解锁');if(CUR)render(CUR);$('tabset').click()}else toast(a.msg||'密码不对')}).catch(function(e){toast('失败: '+e.message)})}function spPct(v){if(!(v>0))return 0;var p=Math.log(1+v/5)/Math.log(101);return p<0?0:(p>1?1:p)}function spVal(v){return v>=100?Math.round(v):(v>=10?v.toFixed(1):v.toFixed(2))}var SGC=2*Math.PI*88*0.75,SCIRC=552.9;function setGauge(v){var p=spPct(v),a=-135+p*270;var nd=document.getElementById('sndl');if(nd)nd.setAttribute('transform','rotate('+a.toFixed(1)+' 120 118)');var ar=document.getElementById('svarc');if(ar){ar.setAttribute('opacity',v>0?'1':'0')}if(ar)ar.setAttribute('stroke-dasharray',(SGC*p).toFixed(1)+' '+SCIRC);var nv=document.getElementById('svnum');if(nv)nv.textContent=spVal(v)}function drawSp(id,arr,mx){var e=document.getElementById(id);if(!e)return;if(!arr||arr.length<1){e.setAttribute('points','');return}var m=Math.max(1,mx||1),n=arr.length,pts=[];for(var i=0;i<n;i++){var x=n>1?i/(n-1)*200:100;var y=38-Math.min(1,arr[i]/m)*34;pts.push(x.toFixed(1)+','+y.toFixed(1))}e.setAttribute('points',pts.join(' '))}function buildTicks(){var g=document.getElementById('sgt');if(!g)return;g.innerHTML='';var NS='http://www.w3.org/2000/svg';var vals=[0,5,10,25,50,100,150,300,500];for(var i=0;i<vals.length;i++){var p=spPct(vals[i]),a=(-135+p*270)*Math.PI/180;var r=56,x=120+r*Math.sin(a),y=118-r*Math.cos(a);var t=document.createElementNS(NS,'text');t.setAttribute('x',x.toFixed(1));t.setAttribute('y',(y+3).toFixed(1));t.textContent=vals[i];g.appendChild(t)}}var SPOLL=null,SPUP=[];function finishSpeed(){}function runSpeed(){if(SPEEDING)return;SPEEDING=true;var b=document.getElementById('sbtn');if(b){b.disabled=true;b.textContent='测速中…'}document.getElementById('stag').textContent='准备';document.getElementById('sping').textContent='—';document.getElementById('sdown').textContent='—';document.getElementById('sph').textContent='正在测延迟…';setGauge(0);drawSp('sdp',[],1);drawSp('sup2',[],1);SPUP=[];act('do=speed&what=all').then(function(){if(SPOLL)clearInterval(SPOLL);SPOLL=setInterval(pollSpeed,250);pollSpeed()})}function pollSpeed(){fetch('/api/speedstate',{cache:'no-store'}).then(function(r){return r.json()}).then(function(s){var st=s.stage;if(st==='ping'){document.getElementById('stag').textContent='延迟';document.getElementById('sph').textContent='正在测延迟…'}else if(st==='down'){document.getElementById('stag').textContent='下载';document.getElementById('sph').textContent='正在测下载（约 10 秒）…'}if(s.ping>=0)document.getElementById('sping').textContent=s.ping;if(s.now>0)setGauge(s.now);if(s.trace&&s.trace.length)drawSp('sdp',s.trace,Math.max(1,s.peak));var tx=(CUR&&CUR.net&&CUR.net.txRate)||0;SPUP.push(tx/125000);if(SPUP.length>60)SPUP.shift();drawSp('sup2',SPUP,Math.max.apply(null,SPUP.concat([0.1])));if(st==='done'||st==='fail'){if(SPOLL){clearInterval(SPOLL);SPOLL=null}if(st==='done'){setGauge(s.down||0);document.getElementById('sdown').textContent=(s.down||0).toFixed(2);document.getElementById('sup').textContent=fsp(tx);document.getElementById('stag').textContent='完成';document.getElementById('sph').textContent='下载 '+(s.down||0).toFixed(2)+' Mbps · 峰值 '+(s.peak||0).toFixed(2)+' Mbps · 延迟 '+(s.ping>=0?s.ping:'—')+' ms'}else{document.getElementById('stag').textContent='失败';document.getElementById('sph').textContent='测速失败：'+(s.err||'未知原因')}SPEEDING=false;var bb=document.getElementById('sbtn');if(bb){bb.disabled=false;bb.textContent='重新测速'}}}).catch(function(e){})}"
        + "var QRGF=(function(){var e=[],l=[],i,x=1;for(i=0;i<256;i++){e[i]=0;l[i]=0}for(i=0;i<255;i++){e[i]=x;l[x]=i;x<<=1;if(x&256)x^=285}for(i=255;i<512;i++)e[i]=e[i-255];return{e:e,l:l}})();"
        + "function qrBytes(t){var b=[],i,c;for(i=0;i<t.length;i++){c=t.charCodeAt(i);if(c<128)b.push(c);else if(c<2048){b.push(192|(c>>6),128|(c&63))}else{b.push(224|(c>>12),128|((c>>6)&63),128|(c&63))}}return b}"
        + "function qrRs(d,n){var e=QRGF.e,l=QRGF.l,g=[1],i,j;for(i=0;i<n;i++){var ng=[];for(j=0;j<=g.length;j++)ng.push(0);for(j=0;j<g.length;j++){ng[j]^=g[j];ng[j+1]^=(g[j]===0?0:e[(l[g[j]]+i)%255])}g=ng}var r=[];for(i=0;i<n;i++)r.push(0);for(i=0;i<d.length;i++){var f=d[i]^r[0];r.shift();r.push(0);if(f!==0){for(j=0;j<n;j++)r[j]^=(g[j+1]===0?0:e[(l[g[j+1]]+l[f])%255])}}return r}"
        + "function qrBuild(t){"
        + " var b=qrBytes(t);if(b.length>78)return null;"
        + " var N=33,i,j,r,c,k;"
        + " var bits=[];function put(v,n){for(var q=n-1;q>=0;q--)bits.push((v>>q)&1)}"
        + " put(4,4);put(b.length,8);"
        + " for(i=0;i<b.length;i++)put(b[i],8);"
        + " for(i=0;i<4&&bits.length<640;i++)bits.push(0);"
        + " while(bits.length%8)bits.push(0);"
        + " var cw=[];for(i=0;i<bits.length;i+=8){var v=0;for(k=0;k<8;k++)v=(v<<1)|bits[i+k];cw.push(v)}"
        + " var pd=[236,17],pi=0;while(cw.length<80)cw.push(pd[pi++%2]);"
        + " var allp=cw.concat(qrRs(cw,20));"
        + " var m=[],f=[];"
        + " for(i=0;i<N;i++){m.push([]);f.push([]);for(j=0;j<N;j++){m[i].push(0);f[i].push(0)}}"
        + " function sf(rr,cc,v){if(rr>=0&&rr<N&&cc>=0&&cc<N){m[rr][cc]=v;f[rr][cc]=1}}"
        + " function fnd(rr,cc){for(var dr=-1;dr<=7;dr++)for(var dc=-1;dc<=7;dc++){var a=rr+dr,bb=cc+dc;if(a<0||a>=N||bb<0||bb>=N)continue;var v=0;if(dr>=0&&dr<=6&&dc>=0&&dc<=6){v=(dr===0||dr===6||dc===0||dc===6||(dr>=2&&dr<=4&&dc>=2&&dc<=4))?1:0}sf(a,bb,v)}}"
        + " fnd(0,0);fnd(0,N-7);fnd(N-7,0);"
        + " for(i=8;i<N-8;i++){sf(6,i,i%2===0?1:0);sf(i,6,i%2===0?1:0)}"
        + " for(var dr2=-2;dr2<=2;dr2++)for(var dc2=-2;dc2<=2;dc2++){sf(26+dr2,26+dc2,(Math.abs(dr2)===2||Math.abs(dc2)===2||(dr2===0&&dc2===0))?1:0)}"
        + " var fd=(1<<3)|0,fv=fd<<10,k2;for(k2=14;k2>=10;k2--){if((fv>>k2)&1)fv^=0x537<<(k2-10)}var fmt=((fd<<10)|fv)^0x5412;"
        + " for(i=0;i<15;i++){var bt=(fmt>>i)&1;if(i<6)sf(i,8,bt);else if(i===6)sf(7,8,bt);else if(i===7)sf(8,8,bt);else if(i===8)sf(8,7,bt);else sf(8,14-i,bt);if(i<8)sf(8,N-1-i,bt);else sf(N-15+i,8,bt)}"
        + " sf(N-8,8,1);"
        + " var bi=0,tb=allp.length*8;"
        + " for(var col=N-1;col>0;col-=2){"
        + "   if(col===6)col--;"
        + "   for(var kk=0;kk<N;kk++){"
        + "     r=(((col+1)&2)===0)?(N-1-kk):kk;"
        + "     for(var tt=0;tt<2;tt++){"
        + "       c=col-tt;"
        + "       if(f[r][c])continue;"
        + "       var bit=0;"
        + "       if(bi<tb){bit=(allp[bi>>3]>>(7-(bi&7)))&1;bi++}"
        + "       if(((r+c)&1)===0)bit^=1;"
        + "       m[r][c]=bit;"
        + "     }"
        + "   }"
        + " }"
        + " var dd=\"\";"
        + " for(r=0;r<N;r++)for(c=0;c<N;c++)if(m[r][c])dd+=\"M\"+c+\" \"+r+\"h1v1h-1z\";"
        + " return (\"<svg xmlns=Q1http://www.w3.org/2000/svgQ1 viewBox=Q10 0 33 33Q1 shape-rendering=Q1crispEdgesQ1><rect width=Q133Q1 height=Q133Q1 fill=Q1#fffQ1/><path d=Q1\"+dd+\"Q1 fill=Q1#000Q1/></svg>\").replace(/Q1/g,String.fromCharCode(34));"
        + "}"
        + "function saveApInfo(){var s=$('shsi').value.trim(),p=$('shpi').value.trim();if(!s||!p){toast('名称和密码都要填');return}act('do=apinfo&ssid='+encodeURIComponent(s)+'&pwd='+encodeURIComponent(p))}"
        + "function drawQr(ssid,pwd){ var box=document.getElementById(\"qrbox\");if(!box)return;"
        + " var t=\"WIFI:T:WPA;S:\"+ssid+\";P:\"+pwd+\";H:false;;\";"
        + " var svg=qrBuild(t);"
        + " box.innerHTML=svg?svg:\"<div class=hint>名称或密码太长，生成不了二维码</div>\";"
        + "}"
        + "function copyWifi(){"
        + " var s=document.getElementById(\"shs\").textContent;"
        + " var p=document.getElementById(\"shp\").textContent;"
        + " var t=\"WiFi 名称：\"+s+String.fromCharCode(10)+\"密码：\"+p;"
        + " if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(t).then(function(){toast(\"已复制\")},function(){toast(\"复制失败\")})}"
        + " else{var ta=document.createElement(\"textarea\");ta.value=t;document.body.appendChild(ta);ta.select();try{document.execCommand(\"copy\");toast(\"已复制\")}catch(e){toast(\"复制失败\")}document.body.removeChild(ta)}"
        + "}"
        + "function act(q){if(/^do=(pass|ap|limit|max|theme|heal|unoff|reboot|block)/.test(q)&&CUR&&CUR.cfg&&CUR.cfg.hasPass)q=q+'&k='+encodeURIComponent(PW);return fetch('/api/action?'+q).then(function(r){return r.json()}).then(function(a){toast((a.ok?'✅ ':'⚠️ ')+(a.msg||(a.ok?'完成':'失败')));setTimeout(refresh,700);return a}).catch(function(e){toast"
        + "('失败: '+e.message)})}Array.prototype.forEach.call(document.querySelectorAll('.tab'),function(e){e.onclick=function(){if(e.getAttribute('data-p')==='set'&&CUR&&CUR.cfg&&CUR.cfg.hasPass&&!UNLOCKED){askPass();return}Array.prototype.forEach.call(document.querySelectorAll('.tab'),function(x){x.className='tab'});Array.prototype.forEach.call(document.querySelectorAll('.pane'),function(x){x.className='pane'});e.className='tab on';var p=e.getAttribute('data-p');$(p).className='pane on';TAB=p;if(p==='log')drawChart();if(p==='share'){try{drawQr((CUR&&CUR.cfg&&CUR.cfg.apSsid)||'',(CUR&&CUR.cfg&&CUR.cfg.apPwd)||'')}catch(e){}}if(p==='cron')drawRules();if(p==='dev')buildTicks();}});function saveAp(){var s=$('ssid').value.trim(),p=$('pwd').value.trim();if(!s&&!p){toast('名称和密码至少填一个');return}if(p&&p.length<8){toast('密码至少 8 位');return}if(p&&p.length>63){toast('密码最长 63 位');return}if(!confirm('改完热点会重开一次，已连设备会掉线再重连。继续？'))return;toast('正在替你点设置页…');fetch('/api/action?do=ap&ssid='+encodeURIComponent(s)+'&pwd='+encodeURIComponent(p)).then(function(r){return r.json()}).then(function(a){toast((a.ok?'✅ ':'⚠️ ')+a.msg);setTimeout(refresh,150"
        + "0)}).catch(function(e){toast('失败: '+e.message)})}function drawBlock(){var o=(CUR&&CUR.cfg)||{},l=o.block||[];var box=document.getElementById('bll');if(!box)return;box.innerHTML='';if(!l.length){box.className='hint';box.textContent='名单为空';return}box.className='';for(var i=0;i<l.length;i++){var row=document.createElement('div');row.style.cssText='display:flex;align-items:center;gap:8px;margin:5px 0';var sp=document.createElement('span');sp.style.cssText='flex:1;font-size:11.5px';sp.textContent=l[i];var bt=document.createElement('button');bt.className='ghost';bt.style.cssText='width:auto;flex:0 0 auto;padding:4px 10px;margin:0';bt.textContent='解禁';bt.setAttribute('data-bl',l[i]);bt.onclick=function(){delBlock(this.getAttribute('data-bl'))};row.appendChild(sp);row.appendChild(bt);box.appendChild(row)}}function addBlock(){var e=document.getElementById('blk');var v=e?e.value.trim():'';if(!v){toast('先填 IP 或 MAC');return}act('do=block&v='+encodeURIComponent(v)).then(function(){if(e)e.value='';setTimeout(drawBlock,500)})}function delBlock(v){act('do=block&v=-'+encodeURIComponent(v)).then(function(){setTimeout(drawBlock,500)})}function clearBlock(){if(!confirm('清空整个黑名单？'))return;act('do=block&v=').then(function(){setTimeout(drawBlock,500)})}function savePass(){var v=$('apw').value;if(!confirm(v?'设置访问密码？下次打开页面要输入它。':'清除访问密码？之后谁都能打开这个面板。'))return;act('do=pass&v='+encodeURIComponent(v)).then(function(a){if(a&&a.ok)$('apw').value=''})}function saveLimit(){act('do=limit&v='+(parseInt($('lim').value||'0',10)||0))}function resetMonth(){if(!confirm('把本月流量计数清零？'))return;act('do=monthReset')}function saveMax(){act('do=max&v='+(parseInt($('maxc').value||'0',10)||0)+'&drop='+($('maxd').checked?'1':'0'))}function setTheme(v){act('do=theme&v='+(v?'light':'dark')).then(function(){applyTheme(v?'light':'dark')})}function heal(){if(!CUR)return;if(CUR.hotspot.up){toast('热点正在运行，无需操作');return}if(CUR.acc){act('do=heal')}else{toast('热点已关闭，请下拉控制中心手动开启')}}function turnOff(){if(!confirm('现在关掉热点？之后看门狗不会再自动打开，要手动恢复。'))return;act('do=off')}function unoff(){act('do=unoff')}function clearStats(){if(!confir"
        + "m('清空曲线数据？'))return;act('do=statsClear').then(function(){drawChart()})}function rebootPhone(){if(!confirm('确定重启这台手机？热点会中断约 1 分钟。'))return;toast('正在重启…');fetch('/api/action?do=reboot').then(function(r){return r.json()}).then(function(a){toast((a.ok?'✅ ':'⚠️ ')+a.msg)}).catch(function(e){toast('失败: '+e.message)})}function drawRules(){var b=$('rl');if(!RULES.length){b.innerHTML='<div class=hint>还没有规则</div>';return}var nm={reboot:'重启手机',off:'关热点',on:'开热点'};var h='';RULES.forEach(function(r,i){h+='<div class=row style=margin-bottom:5px>'+'<div style=flex:1;font-size:12px><b>'+r.at+'</b> · '+(nm[r.type]||r.type)+(r.on?'':' <span class=tag>已暂停</span>')+'</div>'+'<button class=bs style=flex:0_0_52px onclick=tgRule('+i+')>'+(r.on?'暂停':'启用')+'</button>'+'<button class=bg style=flex:0_0_44px onclick=rmRule('+i+')>删</button></div>'});b.innerHTML=h}function saveRules(){act('do=rules&json='+encodeURIC"
        + "omponent(JSON.stringify(RULES)))}function addRule(){var t=$('rt').value||'04:00',a=$('ra').value;RULES.push({at:t,type:a,on:true});saveRules();drawRules()}function tgRule(i){RULES[i].on=!RULES[i].on;saveRules();drawRules()}function rmRule(i){RULES.splice(i,1);saveRules();drawRules()}"
        + "var CHARTMODE='line';try{CHARTMODE=localStorage.getItem('cm')||'line'}catch(e){}"
        + "function toggleChart(){CHARTMODE=(CHARTMODE==='bar'?'line':'bar');try{localStorage.setItem('cm',CHARTMODE)}catch(e){}drawChart()}"
        + "function drawChart(){var S=(CUR&&CUR.stats)||[],sv=$('cvs');sv.innerHTML='';if(!S.length){$('cvh').textContent='还没有采样点，开机满 5 分钟后开始画';return}"
        + "var ns='http://www.w3.org/2000/svg',W=300,H=96,i,n=S.length,mx=1,bar=(CHARTMODE==='bar');"
        + "for(i=0;i<n;i++){var v=S[i].rx+S[i].tx;if(v>mx)mx=v}"
        + "for(i=1;i<=3;i++){var gy=H-(H-6)*i/4-1;var ln=document.createElementNS(ns,'line');ln.setAttribute('x1',0);ln.setAttribute('y1',gy);ln.setAttribute('x2',W);ln.setAttribute('y2',gy);ln.setAttribute('stroke','rgba(148,163,184,0.18)');ln.setAttribute('stroke-width','0.5');sv.appendChild(ln);var tv=document.createElementNS(ns,'text');tv.setAttribute('x',1);tv.setAttribute('y',gy-1.6);tv.setAttribute('fill','#7c8798');tv.setAttribute('font-size','6.5');tv.textContent=fb(mx*i/4);sv.appendChild(tv)}"
        + "if(bar){var slot=W/n,bw=Math.max(0.5,slot*0.62);for(i=0;i<n;i++){var bh=((S[i].rx+S[i].tx)/mx)*(H-7);var r=document.createElementNS(ns,'rect');r.setAttribute('x',(i*slot+(slot-bw)/2).toFixed(2));r.setAttribute('y',(H-bh).toFixed(2));r.setAttribute('width',bw.toFixed(2));r.setAttribute('height',Math.max(0.3,bh).toFixed(2));r.setAttribute('rx','0.7');r.setAttribute('fill','#22d3a6');r.setAttribute('opacity','0.85');sv.appendChild(r)}}"
        + "else{var step=n>1?W/(n-1):W,h='',ar='';for(i=0;i<n;i++){var x=(i*step).toFixed(1),y=(H-((S[i].rx+S[i].tx)/mx)*(H-7)).toFixed(1);h+=(i?'L':'M')+x+' '+y+' '}ar=h+'L'+((n-1)*step).toFixed(1)+' '+H+' L0 '+H+' Z';var p1=document.createElementNS(ns,'path');p1.setAttribute('d',ar);p1.setAttribute('fill','rgba(34,211,166,0.15)');sv.appendChild(p1);var p2=document.createElementNS(ns,'path');p2.setAttribute('d',h);p2.setAttribute('fill','none');p2.setAttribute('stroke','#22d3a6');p2.setAttribute('stroke-width','1.5');sv.appendChild(p2)}"
        + "var cnt=n>2?4:1;for(i=0;i<cnt;i++){var idx=cnt>1?Math.round(i*(n-1)/(cnt-1)):0;var sm=S[idx];if(!sm)continue;var dd=new Date(sm.t);var lab=('0'+dd.getHours()).slice(-2)+':'+('0'+dd.getMinutes()).slice(-2);var t2=document.createElementNS(ns,'text');var px=cnt>1?i/(cnt-1)*W:W-14;t2.setAttribute('x',Math.min(W-13,Math.max(1,px)).toFixed(1));t2.setAttribute('y',H+11);t2.setAttribute('text-anchor',cnt>1?(i===0?'start':(i===cnt-1?'end':'middle')):'end');t2.setAttribute('fill','#7c8798');t2.setAttribute('font-size','6.8');t2.textContent=lab;sv.appendChild(t2)}"
        + "$('cvh').textContent='共 '+n+' 个点 · 峰值 '+fb(mx)+' / 5 分钟';var b2=$('cvm');if(b2)b2.textContent=(bar?'曲线':'柱形')}"+ "function fhis"
        + "t(L){var b=$('hb');if(!L||!L.length){b.innerHTML='<tr><td colspan=3 class=hint>暂无记录</td></tr>';return}var r=[],pv=null,i;for(i=L.length-1;i>=0&&r.length<12;i--){var e=L[i],t=new Date(e.t);var hh=('0'+t.getHours()).slice(-2)+':'+('0'+t.getMinutes()).slice(-2);var dd=(t.getMonth()+1)+'/'+t.getDate(),du='—';if(pv){var m=Math.round((pv.t-e.t)/60000);du=m>=60?Math.floor(m/60)+'时'+(m%60)+'分':m+'分'}r.push('<tr><td>'+dd+' '+hh+'</td><td>'+(e.up?'开启':'掉线')+'</td><td>'+du+'</td></tr>');pv=e}b.innerHTML=r.join('')}function drawSim(){var s=CUR&&CUR.sim,row=$('simrow');if(!s||!s.slots||!s.slots.length){row.style.display='none';return}row.style.display='flex';for(var i=0;i<s.slots.length;i++){var o=s.slots[i],b=$('sim'+(i+1));if(!b)continue;b.textContent='卡'+(i+1)+(o.has&&o.short?' '+o.short:(o.has?'':' 无卡'));b.disabled=!o.has;b.className='bs'+(s.current===o.slot?'':' ghost');b.title=o.name||''}}function pickSim(n){var s=CUR&&CUR.sim;if(!s)return;var o=(s.slots||[])[n-1];if(!o||!o.has){toast('卡'+n+' 没有插卡');return}if(s.current===n){toast('已经在用卡'+n+'了');return}if(!confirm('切到卡'+n+(o.name?'（'+o.name+'）':'')+'？数据会断几秒，热点上的设备会掉一次线。'))return;act('do=sim&slot='+n)}function applyTheme(t){document.body.className=(t==='light'?'light':'');document.querySelector('meta[name=theme-color]');}function noteEdit(i){var d=(CUR&&CUR.devices)||[],x=d[i];if(!x)return;var k=x.mac||x.ip;if(!k)return;var nm=prompt('给这台设备起个名字（留空清除）',(CUR.cfg.notes&&CUR.cfg.notes[k.toLowerCase()])||'');if(nm===null)return;act('do=note&mac='+encodeURIComponent(k)+'&name='+encodeURIComp"
        + "onent(nm))}function refresh(){fetch('/api/status',{cache:'no-store'}).then(function(r){return r.json()}).then(function(d){if(d.needLogin){location.href='/';return}CUR=d;render(d);}).catch(function(e){$('sub').textContent='读取失败: '+e.message})}function render(d){var ts=tabset;if(ts)ts.textContent=(d.cfg&&d.cfg.hasPass&&!UNLOCKED)?'设置 🔒':'设置';try{drawBlock()}catch(e){}try{var q1=document.getElementById('shs'),q2=document.getElementById('shp');if(q1&&d.cfg){q1.textContent=d.cfg.apSsid||'(未读到)';q2.textContent=d.cfg.apPwd||'(未读到)';if(TAB==='share'&&(window._qs!==q1.textContent||window._qp!==q2.textContent)){window._qs=q1.textContent;window._qp=q2.textContent;drawQr(d.cfg.apSsid||'',d.cfg.apPwd||'')}}}catch(e){}var up=d.hotspot.up;$('sub').textContent='手机本机提供 · '+new Date(d.ts).toLocaleTimeString('zh-CN');if(d.cfg&&d.cfg.theme)applyTheme(d.cfg.theme);$('hero').className='hero '+(up?'up':'');$('hs').textContent=up?'运行中':'已关闭';$('hs').style.color=up?'':'var(--red)';$('hsh').textContent='AP '+(d.hotspot.ip||'—')+' · '+(up?'hostapd 运行中':'未监听');$('b').textContent=d.battery.level>=0?d.battery.level:'—';$('bb').style.width=(d.battery.level>0?d.battery.level:0)+'%';$('bh').textContent=(d.battery.charging?'⚡ 充电中':'未充电')+(d.battery.usbPowered?' · USB 供电':'');$('t').textContent=d.battery.temp>=0?d.battery.temp:'—';$('th').textContent='电池 · 内核热状态 '+t"
        + "hermalText(d.battery.thermalStatus);$('c').textContent=d.hotspot.clients;$('ch').textContent=(d.cfg.maxClients>0?'上限 '+d.cfg.maxClients+' 台':'在线设备')+(d.hotspot.clientsKnown>d.hotspot.clients?' · '+(d.hotspot.clientsKnown-d.hotspot.clients)+' 台已断开':'');$('m').textContent=d.system.memPct;$('mb').style.width=d.system.memPct+'%';$('mh').textContent='共 '+d.system.memTotalMB+' MB · 可用 '+d.system.memAvailMB+' MB';var nw=d.net||{};$('nt').textContent=nw.netType||'—';$('op').textContent=nw.operator||'未取到运营商';drawSim();$('rate').textContent='↓ '+fsp(nw.rxRate)+'  ↑ '+fsp(nw.txRate);var sb=$('sup');if(sb&&!SPEEDING)sb.textContent=fsp(nw.txRate);$('rh').textContent=(d.hotspot.ssid?'SSID '+d.hotspot.ssid:'下行 / 上行');$('up').textContent=fu(d.system.uptimeSec);$('rx').textContent=fb(d.traffic.rxBytes);$('tx').textContent=fb(d.traffic.txBytes);$('aph').textContent=(d.ap.busy?'⏳ ':'')+d.ap.msg;$('aph').style.color=d.ap.busy?'#f5a524':(d.ap.msg.indexOf('已提交')>=0?'var(--accent)':'var(--dim)');$('pwh').textContent=d.cfg.hasPass?'已设置：打开页面需要密码':'未设置：同网段谁都能打开';$('lmh').textContent=d.cfg."
        + "limitMB>0?('本月已用 '+fb(d.cfg.monthBytes)+' / '+d.cfg.limitMB+' MB'):'未设限额';$('lim').placeholder=d.cfg.limitMB>0?('当前 '+d.cfg.limitMB+' MB'):'例如 10240（MB）';$('maxc').placeholder=d.cfg.maxClients>0?('当前 '+d.cfg.maxClients+' 台'):'例如 5';$('maxd').checked=!!d.cfg.maxDrop;$('mxh').textContent=d.cfg.maxClients>0?('上限 '+d.cfg.maxClients+' 台'+(d.cfg.maxDrop?' · 超限自动断网':' · 只提醒')):'未设上限';var lc=$('lc');if(d.cfg.limitMB>0){lc.style.display='';$('mu').textContent=fb(d.cfg.monthBytes);$('lm').textContent=d.cfg.limitMB+' MB';var pc=Math.min(100,d.cfg.monthBytes*100/(d.cfg.limitMB*1048576));$('lb').style.width=pc+'%';$('lb').style.background=d.limitHit?'var(--red)':'';$('lh').textContent=pc.toFixed(1)+'% · '+(d.limitHit?'已超限':'还差 '+fb(Math.max(0,d.cfg.limitMB*1048576-d.cfg.monthBytes)))}else lc.style.display='none';var w=$('warn'),ws=[];if(d.limitHit)ws.push('本月流量已达 '+fb(d.cfg.monthBytes)+'，超过设定的 '+d.cf"
        + "g.limitMB+' MB 限额');if(d.overCount)ws.push('当前 '+d.hotspot.clients+' 台设备在线，超过上限 '+d.cfg.maxClients+' 台'+(d.cfg.maxDrop?'（已自动断一次热点挤人）':'（只提醒）'));if(d.cfg.manualOff)ws.push('热点处在「定时关闭」锁定状态，看门狗不会自动打开它');if(d.cfg.lastRule)ws.push('最近一次定时动作：'+d.cfg.lastRule);if(ws.length){w.style.display='';w.innerHTML=ws.join('<br>')}else w.style.display='none';$('rbh').textContent=d.owner?'已具备设备所有者权限，点一下就会真正重启（热点会中断约 1 分钟）':'当前不是设备所有者。点按钮仍会尝试 root 通道，失败会如实告知原因。';var t=$('dt'),N=d.cfg.notes||{};if(!d.devices.length){t.innerHTML='<tr><td colspan=3 class=hint>暂无</td></tr>'}else{t.innerHTML=d.devices.map(function(x,i){var k=(x.mac||x.ip||'').toLowerCase();return '<tr'+(x.online?'':' style=opacity:.45')+' onclick=noteEdit('+i+')><td>'+(x.ip||'—')+'</td><td>'+(x.mac||'—')+'</td><td>'+(N[k]||'<span class=tag>点我命名</span>')+(x.online?'':' <span class=tag>已断开</span>')+'</td></tr>'}).join('')}RULES=(d.cfg.rules||[]);if(TAB==='cron')drawRules();if(TAB==='log')drawChart();fhist(d.hist)}refresh();setInterval(refresh,2000);</script></body><"
        + "/html>";
}
