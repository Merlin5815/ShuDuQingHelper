package com.shuduqinghelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务: 通过 dispatchGesture 模拟点击/双击.
 * 需在系统设置中手动开启本服务.
 */
public class SudokuAccessibilityService extends AccessibilityService {

    private static SudokuAccessibilityService instance;

    public static SudokuAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isReady() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要监听事件, 只用来发手势
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    /** 单击 (x,y) */
    public void click(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 50);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        dispatchGesture(gesture, null, null);
    }

    /** 双击 (x,y), 间隔 80ms */
    public void doubleClick(float x, float y) {
        click(x, y);
        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        click(x, y);
    }
}
