package com.yg.autohotspot;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * v49 通用界面搜索器。
 *
 * 解决的问题：以前每换一台手机，都要人肉补「按钮叫什么名字」。
 * 这里把「写死词表」升级成三层：
 *
 *   第 1 层  学的词：上次在这台机器上搜成功过的文本，直接先试（最快）
 *   第 2 层  内置词：华为/小米/vivo/三星/原生 的常见写法（覆盖大多数）
 *   第 3 层  打分搜：前两层都没命中时，扫描当前整页所有节点，
 *            按「文本相关性 + 控件类型 + 是否可点 + 是否有开关子节点」打分，
 *            取最高分。搜成功后**把命中的文本记下来**，下次就走第 1 层。
 *
 * 所以换机不需要再补词表：第一次可能慢一点（要扫全页），之后就是秒中。
 */
public class UiFinder {

    private static final String PREF = "uifinder";

    /** 目标类型，不同类型有不同的打分规则 */
    public static final int T_TILE   = 1;  // 快捷设置里的热点磁贴
    public static final int T_HOME   = 2;  // 设置首页里通往热点的那一项
    public static final int T_CONFIG = 3;  // 热点页里的「更多设置 / 配置」
    public static final int T_SSID   = 4;  // 名称输入框
    public static final int T_PWD    = 5;  // 密码输入框
    public static final int T_OK     = 6;  // 确定 / 保存按钮

    /** 强相关词：出现即高置信，几乎不会误判 */
    private static final String[] STRONG = {
            "个人热点", "移动热点", "便携式热点", "便携式WLAN热点", "便携式 WLAN 热点",
            "热点和网络共享", "个人热点和网络共享",
            "Portable hotspot", "Portable Hotspot", "Hotspot", "Tethering"
    };

    /** 弱相关词：要配合控件特征才敢点 */
    private static final String[] WEAK = {
            "热点", "网络共享", "共享网络", "共享"
    };

    /** 排除词：这些页面/控件跟热点无关，命中要减分 */
    private static final String[] NEGATIVE = {
            "蓝牙", "Bluetooth", "NFC", "投屏", "打印", "打印服务", "投影",
            "飞行模式", "Airplane", "VPN", "私人 DNS", "Private DNS",
            "流量", "套餐", "运营商", "APN", "飞行"
    };

    // ------------------------------------------------------------ 词表

    /** 读这台机器上学到的词（顺序稳定，最新的在前） */
    public static String[] learned(Context c, int type) {
        SharedPreferences sp = prefs(c);
        String raw = sp == null ? "" : sp.getString("w" + type, "");
        if (raw == null || raw.length() == 0) return new String[0];
        return raw.split("\n");
    }

    /** 记住一个成功的词，排到最前面；重复的会先去掉 */
    public static void learn(Context c, int type, String word) {
        if (word == null) return;
        String w = word.trim();
        if (w.length() == 0 || w.length() > 40) return;
        SharedPreferences sp = prefs(c);
        if (sp == null) return;
        StringBuilder sb = new StringBuilder(w).append("\n");
        String[] old = learned(c, type);
        int n = 0;
        for (int i = 0; i < old.length; i++) {
            String o = old[i].trim();
            if (o.length() == 0 || o.equals(w)) continue;
            sb.append(o).append("\n");
            if (++n >= 12) break;   // 最多记 12 个，避免无限膨胀
        }
        sp.edit().putString("w" + type, sb.toString()).apply();
    }

    /** 学到的词 + 内置词，去重后合并（第 1 层 + 第 2 层） */
    public static String[] merge(Context c, int type, String[] builtin) {
        String[] le = learned(c, type);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < le.length; i++) {
            String s = le[i].trim();
            if (s.length() > 0) out.add(s);
        }
        if (builtin != null) {
            for (int i = 0; i < builtin.length; i++) {
                String s = builtin[i];
                if (s != null && s.length() > 0 && !out.contains(s)) out.add(s);
            }
        }
        return out.toArray(new String[out.size()]);
    }

    private static SharedPreferences prefs(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 打分

    /**
     * 给一个节点打分。返回 0 表示不像；>=55 认为可用。
     * type 决定额外权重：磁贴看「有没有开关状态」，输入框看「能不能编辑」。
     */
    public static int score(AccessibilityNodeInfo n, int type) {
        if (n == null) return 0;
        String text = textOf(n);
        if (text.length() == 0) return 0;

        int s = 0;
        boolean strong = false;
        for (int i = 0; i < STRONG.length; i++) {
            if (text.contains(STRONG[i])) { s += 60; strong = true; break; }
        }
        if (!strong) {
            for (int i = 0; i < WEAK.length; i++) {
                if (text.contains(WEAK[i])) { s += 26; break; }
            }
        }
        if (s == 0) return 0;

        // 说明文字里常出现「流量」「套餐」这类词（vivo 的热点行 content-desc 就是
        // 「个人热点.优先共享已连接的 WLAN 网络。共享移动网络时可能产生流量费用。」）
        // 长文本一律不做负面判断，否则真目标会被自己的一句说明拖下水。
        if (text.length() <= 20) {
            for (int i = 0; i < NEGATIVE.length; i++) {
                if (text.contains(NEGATIVE[i])) { s -= 45; break; }
            }
        }

        // 文本太长通常是说明文字，不是标题
        if (text.length() <= 10) s += 10;
        else if (text.length() > 24) s -= 12;

        if (type == T_TILE) {
            // 磁贴：自己可勾选，或子节点里有可勾选的开关
            if (n.isCheckable()) s += 34;
            if (hasCheckableChild(n)) s += 30;
            if (n.isClickable()) s += 12;
            s += stateBonus(n);
        } else if (type == T_HOME) {
            // 设置首页的一级项：可点、通常不是开关
            if (n.isClickable()) s += 16;
            if (!n.isCheckable() && !hasCheckableChild(n)) s += 12;
        } else if (type == T_CONFIG) {
            if (n.isClickable()) s += 18;
            if (n.isCheckable() || hasCheckableChild(n)) s -= 20;  // 开关不是配置入口
        } else if (type == T_SSID || type == T_PWD) {
            // 输入框：可编辑优先，但名字里的「密码 / 名称」才是关键
            if (n.isEditable()) s += 20;
            if (type == T_PWD && text.contains("密码")) s += 26;
            if (type == T_SSID && text.contains("名称")) s += 26;
        } else if (type == T_OK) {
            if (n.isClickable()) s += 22;
        }
        return s;
    }

    /**
     * 扫整棵树的最高分节点。threshold 建议 55。
     * 返回 null 表示这一页没有像样的候选。
     */
    public static AccessibilityNodeInfo best(AccessibilityNodeInfo root, int type, int threshold) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> all = new ArrayList<AccessibilityNodeInfo>();
        walk(root, all, 0, 2000);
        AccessibilityNodeInfo bestNode = null;
        int bestScore = 0;
        for (int i = 0; i < all.size(); i++) {
            AccessibilityNodeInfo n = all.get(i);
            int s = score(n, type);
            if (s > bestScore) {
                bestScore = s;
                bestNode = n;
            }
        }
        return bestScore >= threshold ? bestNode : null;
    }

    /** 把整棵树摊平（带节点预算，防止异常页面卡死） */
    public static void walk(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth, int budget) {
        if (n == null || depth > 32 || out.size() >= budget) return;
        out.add(n);
        try {
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) {
                walk(n.getChild(i), out, depth + 1, budget);
            }
        } catch (Throwable ignored) { }
    }

    /** 节点自己的文本；没有文本时退回 contentDescription */
    public static String textOf(AccessibilityNodeInfo n) {
        if (n == null) return "";
        try {
            CharSequence t = n.getText();
            if (t != null && t.length() > 0) return t.toString();
            CharSequence d = n.getContentDescription();
            if (d != null && d.length() > 0) return d.toString();
        } catch (Throwable ignored) { }
        return "";
    }

    /** 子树里有没有可勾选的控件（开关） */
    public static boolean hasCheckableChild(AccessibilityNodeInfo n) {
        if (n == null) return false;
        try {
            if (n.isCheckable()) return true;
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch == null) continue;
                if (ch.isCheckable()) return true;
                if (hasCheckableChild(ch)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** 从节点描述里认开/关状态，给磁贴用 */
    private static int stateBonus(AccessibilityNodeInfo n) {
        String t = textOf(n);
        if (t.contains("已开启") || t.contains("开启") || t.contains("已打开")
                || t.contains("On") || t.contains("ON")) return 14;
        if (t.contains("已关闭") || t.contains("关闭") || t.contains("Off")
                || t.contains("OFF")) return 10;
        try {
            if (n.isChecked()) return 14;
        } catch (Throwable ignored) { }
        return 0;
    }

    /** 从子树里找出第一个能勾选的节点，点它比点父容器可靠 */
    public static AccessibilityNodeInfo firstCheckable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 8) return null;
        try {
            if (n.isCheckable()) return n;
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) {
                AccessibilityNodeInfo r = firstCheckable(n.getChild(i), depth + 1);
                if (r != null) return r;
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
