package com.shuduqinghelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * 截屏服务: 持有 MediaProjection, 提供截屏能力.
 * 用法:
 *   1. MainActivity 通过 MediaProjectionManager.createScreenCaptureIntent() 拿到 resultCode/data
 *   2. startForegroundService(this, ScreenCaptureService.class) 并传入 extra
 *   3. ScreenCaptureService.getBitmap() 获取当前屏幕 Bitmap
 */
public class ScreenCaptureService extends Service {

    private static final String CHANNEL_ID = "sudoku_capture";
    private static final String EXTRA_RESULT_CODE = "resultCode";
    private static final String EXTRA_DATA = "data";

    private static ScreenCaptureService instance;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth, screenHeight;

    public static ScreenCaptureService getInstance() {
        return instance;
    }

    public static Intent createIntent(Context ctx, int resultCode, Intent data) {
        Intent intent = new Intent(ctx, ScreenCaptureService.class);
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(EXTRA_DATA, data);
        return intent;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        // Android 10+ 前台服务需声明 mediaProjection 类型, 否则部分系统直接杀服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, buildNotification());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            if (data != null) {
                setupProjection(resultCode, data);
            }
        }
        return START_STICKY;
    }

    private void setupProjection(int resultCode, Intent data) {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(resultCode, data);
        // Android 14+ (targetSdk 34) 强制要求: 必须先注册回调才能 createVirtualDisplay
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // 用户从系统通知栏停止了投屏
                stopSelf();
            }
        }, new Handler(Looper.getMainLooper()));

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "SudokuCapture",
                screenWidth, screenHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, new Handler(Looper.getMainLooper()));
    }

    /** 截取当前屏幕, 返回 Bitmap (调用方负责 recycle). 失败返回 null. */
    public Bitmap getBitmap() {
        if (imageReader == null) return null;
        Image image = imageReader.acquireLatestImage();
        if (image == null) return null;

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * screenWidth;

        Bitmap bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride, screenHeight,
                Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);

        Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
        bitmap.recycle();
        image.close();
        return cropped;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        if (imageReader != null) imageReader.close();
        instance = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "数独辅助截屏", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("数独辅助")
                .setContentText("截屏服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }
}
