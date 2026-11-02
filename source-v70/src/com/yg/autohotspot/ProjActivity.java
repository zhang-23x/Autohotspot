package com.yg.autohotspot;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * 只做一件事：拉起系统的「开始录制或投射」确认框，拿到授权就交给 ScreenCap。
 *
 * 这个 Activity 是透明的，弹窗出现后屏幕上不会有别的变化。
 * 弹窗按钮能用面板「屏幕」页签从浏览器点掉。
 */
public class ProjActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                finish();
                return;
            }
            startActivityForResult(mpm.createScreenCaptureIntent(), ScreenCap.REQ);
        } catch (Throwable t) {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == ScreenCap.REQ) {
            if (res == RESULT_OK && data != null) {
                MirrorService.start(getApplicationContext(), res, data);
            }
        }
        finish();
    }
}
