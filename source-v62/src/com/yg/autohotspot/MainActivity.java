package com.yg.autohotspot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView log;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("AutoHotspot —— 开机自动开启个人热点");
        title.setTextSize(18f);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("\n使用前提：\n"
                + "1. 在系统设置里启用本 App 的无障碍服务「AutoHotspot」\n"
                + "2. 在「应用启动管理」里把 AutoHotspot 设为手动管理并允许自启动\n"
                + "3. 关闭锁屏（已通过 ADB 设置 lockscreen.disabled=true）\n\n"
                + "启用后每次开机，本 App 会自动下滑控制中心并点亮「个人热点」磁贴。\n");
        hint.setTextSize(14f);
        root.addView(hint);

        Button now = new Button(this);
        now.setText("立即尝试开启热点");
        now.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                HotspotService.trigger();
                append("已发出指令，请观察控制中心是否自动展开…");
            }
        });
        root.addView(now);

        Button acc = new Button(this);
        acc.setText("打开无障碍设置");
        acc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable ignored) { }
            }
        });
        root.addView(acc);

        // 面板服务：前台常驻，不依赖无障碍，App 一启动就监听 8080
        try {
            PanelService.acquireLocks(getApplicationContext());
            PanelService.start(getApplicationContext());
            PanelService.armWatchdog(getApplicationContext());
        } catch (Throwable e) {
            // ignore
        }

        log = new TextView(this);
        log.setTextSize(13f);
        log.setText("面板地址（连上热点的设备都能打开）：\n"
                + "  " + ApProfile.panelUrl() + "\n\n");
        root.addView(log);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void append(String s) {
        if (log != null) log.append(s + "\n");
    }
}
