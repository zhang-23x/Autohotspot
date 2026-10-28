package com.yg.autohotspot;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * v34 定时规则：每天到点做一件事（重启 / 关热点 / 开热点）。
 *
 * 不额外挂闹钟，挂在 WatchdogReceiver 每分钟那一次 tick 上 ——
 * 那条 Alarm 本来就是 setExactAndAllowWhileIdle，Doze 深睡也能穿透。
 */
public class Automation {

    static String json(Context c) { return Panel.s(c, "rules", "[]"); }

    /** 保存规则；非法 type 或时间直接丢掉，返回 null 表示成功 */
    static String save(Context c, String raw) {
        try {
            JSONArray in = new JSONArray(raw == null ? "[]" : raw);
            JSONArray out = new JSONArray();
            for (int k = 0; k < in.length(); k++) {
                JSONObject o = in.optJSONObject(k);
                if (o == null) continue;
                String type = o.optString("type", "");
                if (!"reboot".equals(type) && !"off".equals(type) && !"on".equals(type)) continue;
                String at = o.optString("at", "");
                if (!at.matches("\\d{1,2}:\\d{2}")) continue;
                String[] hm = at.split(":");
                int hh = Integer.parseInt(hm[0]);
                int mm = Integer.parseInt(hm[1]);
                if (hh < 0 || hh > 23 || mm < 0 || mm > 59) continue;
                JSONObject n = new JSONObject();
                n.put("type", type);
                n.put("at", String.format(Locale.US, "%02d:%02d", hh, mm));
                n.put("on", o.optBoolean("on", true));
                out.put(n);
            }
            Panel.setS(c, "rules", out.toString());
            return null;
        } catch (Throwable t) {
            return "规则格式不对：" + t.getMessage();
        }
    }

    /** 每分钟被 WatchdogReceiver 调一次 */
    static void tick(Context c) {
        try {
            SimpleDateFormat key = new SimpleDateFormat("yyyyMMddHHmm", Locale.US);
            SimpleDateFormat ymd = new SimpleDateFormat("yyyyMMdd", Locale.US);
            Date now = new Date();
            String k = key.format(now);
            if (k.equals(Panel.s(c, "ruleLast", ""))) return;
            String day = ymd.format(now);

            Calendar cal = Calendar.getInstance();
            int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

            JSONArray arr = new JSONArray(json(c));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (!o.optBoolean("on", true)) continue;
                String at = o.optString("at", "");
                String type = o.optString("type", "");
                if (!at.matches("\\d{1,2}:\\d{2}")) continue;
                String[] hm = at.split(":");
                int planMin = Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
                // 没到点就跳过；错过超过 30 分钟也跳过（避免关机一夜后一开机就重启）
                if (nowMin < planMin || nowMin - planMin > 30) continue;
                // 同一天同一条规则只执行一次——记在 done_<type>_<at> 里
                String doneKey = "done_" + type + "_" + at;
                if (day.equals(Panel.s(c, doneKey, ""))) continue;
                Panel.setS(c, doneKey, day);
                Panel.setS(c, "ruleLast", k);
                fire(c, type);
                return;
            }
        } catch (Throwable t) {
            Log.w(HotspotService.TAG, "automation tick: " + t);
        }
    }

    private static void fire(Context c, String type) {
        Log.i(HotspotService.TAG, "automation fire: " + type);
        if ("reboot".equals(type)) {
            Panel.setS(c, "lastRule", "定时重启已下发");
            Reboot.reboot(c);
        } else if ("off".equals(type)) {
            Panel.setManualOff(c, true);
            Panel.setS(c, "lastRule", "定时关闭热点已执行");
            HotspotService.turnOff();
        } else if ("on".equals(type)) {
            Panel.setManualOff(c, false);
            Panel.setS(c, "lastRule", "定时开启热点已执行");
            HotspotService.trigger();
        }
    }
}
