package com.yg.autohotspot;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * 屏幕镜像：MediaProjection -> VirtualDisplay -> ImageReader -> JPEG。
 *
 * 授权这一步绕不过去：Android 要求一次系统弹窗确认，
 * 弹窗本身可以用「屏幕」页签从浏览器点掉。
 * Android 10 起每次重启都要重新确认一次。
 */
public class ScreenCap {

    public static final int REQ = 7801;

    private static MediaProjection proj;
    private static VirtualDisplay vd;
    private static ImageReader rd;
    private static HandlerThread th;
    private static Handler hd;

    private static volatile byte[] last;
    private static volatile long lastTs = 0;
    private static volatile int frames = 0;
    private static volatile String err = "";
    private static volatile boolean on = false;

    static int ow = 540, oh = 1200, odpi = 240;

    public static boolean isOn() { return on && proj != null; }
    public static byte[] jpeg() { return last; }
    public static long ts() { return lastTs; }
    public static int frames() { return frames; }
    public static String error() { return err; }
    static void note(String s) { err = (s == null ? "" : s); }
    public static int w() { return ow; }
    public static int h() { return oh; }

    /** 拉起授权界面。屏幕坏了也没关系，弹窗能用「屏幕」页签点掉。 */
    public static void request(Context c) {
        if (isOn() && proj != null) { err = ""; return; }
        try {
            Intent i = new Intent(c, ProjActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            c.startActivity(i);
        } catch (Throwable t) {
            err = "start: " + t.getClass().getSimpleName();
        }
    }

    public static synchronized void start(Context c, int code, Intent data) {
        stop();
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) { err = "没有 MediaProjectionManager"; return; }
            proj = mpm.getMediaProjection(code, data);
            if (proj == null) { err = "getMediaProjection 返回 null"; return; }

            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int fw = dm.widthPixels, fh = dm.heightPixels;
            odpi = dm.densityDpi;
            ow = 540;
            oh = Math.max(2, Math.round(fh * (540f / fw) / 2) * 2);

            rd = ImageReader.newInstance(ow, oh, PixelFormat.RGBA_8888, 2);
            th = new HandlerThread("ahcap");
            th.start();
            hd = new Handler(th.getLooper());
            rd.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image im = null;
                    try {
                        im = r.acquireLatestImage();
                    } catch (Throwable t) {
                        return;
                    }
                    if (im == null) return;
                    try {
                        Image.Plane p = im.getPlanes()[0];
                        ByteBuffer buf = p.getBuffer();
                        int rs = p.getRowStride();
                        int ps = p.getPixelStride();
                        int iw = im.getWidth(), ih = im.getHeight();
                        int stride = rs / ps;
                        Bitmap b = Bitmap.createBitmap(stride, ih, Bitmap.Config.ARGB_8888);
                        b.copyPixelsFromBuffer(buf);
                        if (stride != iw) {
                            Bitmap cut = Bitmap.createBitmap(b, 0, 0, iw, ih);
                            b.recycle();
                            b = cut;
                        }
                        ByteArrayOutputStream bo = new ByteArrayOutputStream(65536);
                        b.compress(Bitmap.CompressFormat.JPEG, 55, bo);
                        b.recycle();
                        last = bo.toByteArray();
                        lastTs = System.currentTimeMillis();
                        frames++;
                        err = "";
                    } catch (Throwable t) {
                        err = "frame: " + t.getClass().getSimpleName();
                    } finally {
                        try { im.close(); } catch (Throwable t) { }
                    }
                }
            }, hd);

            vd = proj.createVirtualDisplay("ahmirror", ow, oh, odpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    rd.getSurface(), null, null);
            on = true;
            err = "";
        } catch (Throwable t) {
            err = "start: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            stop();
        }
    }

    public static synchronized void stop() {
        on = false;
        try { if (vd != null) vd.release(); } catch (Throwable t) { }
        vd = null;
        try { if (proj != null) proj.stop(); } catch (Throwable t) { }
        proj = null;
        try { if (rd != null) rd.close(); } catch (Throwable t) { }
        rd = null;
        try { if (th != null) th.quitSafely(); } catch (Throwable t) { }
        th = null;
        hd = null;
        last = null;
        frames = 0;
        lastTs = 0;
    }
}
