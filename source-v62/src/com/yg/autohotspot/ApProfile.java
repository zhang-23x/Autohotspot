package com.yg.autohotspot;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * v42 设备适配层。
 *
 * 这套 App 原来整条链路都是照着华为/荣耀写的，换台机器会在这几处断掉：
 *   1. AP 地址写死 192.168.43.1（华为默认网段，小米/原生也用它，但有的 ROM 是 192.168.1.1）
 *   2. 控制中心磁贴写死「个人热点」（三星叫「移动热点」，原生叫「热点」）
 *   3. 设置页写死「移动网络 → 个人热点」、「设备名称」「密码」两行
 *   4. 磁贴坐标兜底写死 1080x2400
 *
 * 这里把第 1、2、3 项抽成可探测的常量与函数，第 4 项改成按屏幕比例算。
 * 每个关键词表**第一项都是华为原本用的词**，所以在 X10 上行为和改之前完全一致。
 */
public final class ApProfile {

    private ApProfile() { }

    /* ------------------------------------------------------------ 关键词表 */

    /** 控制中心磁贴 / 设置首页入口。从精确到宽泛。 */
    public static final String[] TILE_WORDS = {
            "个人热点", "移动热点", "便携式热点", "个人热点和网络共享", "网络共享", "热点"
    };

    /** 设置首页里通往热点设置的那一级菜单（华为是「移动网络」）。 */
    public static final String[] SETTING_HOME_WORDS = {
            "移动网络", "更多连接", "其他网络与连接", "连接与共享", "无线和网络",
            "网络和互联网", "更多设置", "连接", "移动数据"
    };

    /** 设置页里「热点名字」那一行。 */
    public static final String[] SSID_WORDS = {
            "设备名称", "热点名称", "网络名称", "热点名", "名称"
    };

    /** 设置页里「热点密码」那一行。 */
    public static final String[] PWD_WORDS = {
            "密码", "热点密码", "网络密码", "WPA2"
    };

    /** 热点页里通往「名称+密码」配置页的入口。vivo 收在下一层，华为是行内弹窗。 */
    public static final String[] CONFIG_WORDS = {
            "热点配置", "热点设置", "配置 WLAN 热点", "配置热点", "WLAN 热点", "修改热点", "热点信息"
    };

    /** 弹窗/页面上的确认按钮。 */
    public static final String[] OK_WORDS = {
            "确定", "保存", "完成", "应用", "确定并保存", "OK", "Save", "Done"
    };

    /** 快捷设置面板的展开按钮。 */
    public static final String[] EXPAND_WORDS = {
            "展开快捷设置", "展开", "编辑", "全部"
    };

    /** 标准的热点设置页 action。Android 8+ 定义，多数厂商会响应，华为不响应。 */
    public static final String INTENT_TETHER = "android.settings.TETHER_SETTINGS";

    /** 面板 HTTP 端口 */
    public static final int HTTP_PORT = 8080;

    /* --------------------------------------------------------- AP 地址探测 */

    private static String cachedAp = null;

    /**
     * 本机作为热点网关时的 IPv4 地址。
     *
     * 判据：wlan* 网卡上、私有网段、**主机号末位是 1** 的地址。
     * 手机连别人 WiFi 时自己拿到的是 192.168.x.100 这种，不会是 .1，
     * 所以「末位 1」能稳定区分「我是网关」和「我是客户端」。
     *
     * 探测不到就回落到华为默认值，保证老机器行为不变。
     */
    /**
     * 热点网卡名的特征。
     * vivo / 多数厂商的热点挂在 ap0 上，部分是 swlan0 / softap0 / wigig0。
     * 这类接口只要有地址，就说明热点正开着。
     */
    public static boolean isApIface(String name) {
        if (name == null) return false;
        String s = name.toLowerCase();
        return s.equals("ap0") || s.startsWith("ap_") || s.startsWith("apbr")
                || s.startsWith("swlan") || s.startsWith("softap") || s.startsWith("wigig");
    }
    /**
     * 当前热点网卡名，取不到返回 null。
     * 给 ip neigh / arp 精确查询用：以前写死 wlan0，vivo 上热点在 ap0，
     * 结果这条线永远空，设备列表就只能靠 HTTP 访问记录来凑。
     */
    public static String apIfaceName() {
        String want = apIp();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                String n = ni.getName();
                if (n == null || !ni.isUp()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && want.equals(a.getHostAddress())) return n;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public static String apIp() {
        if (cachedAp != null) return cachedAp;
        String f = detectAp();
        // 只有真探测到才缓存：热点还没起来时每次调用都重探，
        // 否则会把兜底值钉死，热点起来后地址就对不上了。
        if (f != null) {
            cachedAp = f;
            return f;
        }
        return "192.168.43.1";
    }

    /** 热点重启后 IP 可能变，清一次缓存让它重新探测 */
    public static void invalidate() {
        cachedAp = null;
    }

    private static String detectAp() {
        String byIface = null;
        String byDot1 = null;
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                String n = ni.getName();
                if (n == null || !ni.isUp()) continue;
                String ip = firstPrivateV4(ni);
                if (ip == null) continue;
                // 首选：接口名就是热点接口（vivo 的 ap0 是 192.168.242.138，末位不是 1）
                if (byIface == null && isApIface(n)) byIface = ip;
                // 次选：老判据。wlan* 上、私有网段、末位 .1（华为/小米/原生）
                if (byDot1 == null && n.startsWith("wlan") && ip.endsWith(".1")) byDot1 = ip;
            }
        } catch (Throwable ignored) {
            // 拿不到网卡列表就走兜底，不影响主流程
        }
        return byIface != null ? byIface : byDot1;
    }

    private static String firstPrivateV4(NetworkInterface ni) {
        try {
            Enumeration<InetAddress> as = ni.getInetAddresses();
            while (as.hasMoreElements()) {
                InetAddress a = as.nextElement();
                if (!(a instanceof Inet4Address)) continue;
                String ip = a.getHostAddress();
                if (isPrivateV4(ip)) return ip;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 本机 AP 网段前缀，带点，如 "192.168.43." */
    public static String apPrefix() {
        String ip = apIp();
        int dot = ip.lastIndexOf('.');
        return dot > 0 ? ip.substring(0, dot + 1) : "192.168.43.";
    }

    /** 这个地址是不是「连到本机热点的某台设备」（在同网段且不是本机） */
    public static boolean inApSubnet(String ip) {
        if (ip == null) return false;
        return ip.startsWith(apPrefix()) && !ip.equals(apIp());
    }

    /** 面板访问地址，给通知和界面上展示用 */
    public static String panelUrl() {
        return "http://" + apIp() + ":" + HTTP_PORT;
    }

    /** "http://192.168.43.1:8080" 里那段 host:port，给通知标题用 */
    public static String hostPort() {
        return apIp() + ":" + HTTP_PORT;
    }

    static boolean isPrivateV4(String ip) {
        if (ip == null) return false;
        if (ip.startsWith("10.")) return true;
        if (ip.startsWith("192.168.")) return true;
        if (ip.startsWith("172.")) {
            // 172.16.0.0 – 172.31.255.255
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }
}
