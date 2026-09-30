package com.shuduqinghelper;

import android.graphics.Point;

/**
 * 下方操作区双盘坐标配置.
 * 左盘 3x3: 点数字即向选中格填入确定数字;
 * 右盘 3x3: 候选修改盘, 点数字切换该候选(显示/移除), 用于数对/三链数剔除.
 *
 * 存储两个并排白色近方形块的矩形, 各 9 个数字键中心按 3x3 均分.
 * 序列化: "L,T,R,B|l,t,r,b" (左盘|右盘), 旧版单盘格式仍可读取.
 */
public class PadConfig {

    public int padLeft, padTop, padRight, padBottom;
    // keys[d] = 左盘(填数)数字 d 的点击中心, d=1..9 (1左上 ... 9右下)
    public final Point[] keys = new Point[10];
    // 右盘(候选修改)数字键中心; null 表示旧配置未校准右盘
    public Point[] rightKeys = null;
    public int rightLeft, rightTop, rightRight, rightBottom;

    public PadConfig(int left, int top, int right, int bottom) {
        this.padLeft = left;
        this.padTop = top;
        this.padRight = right;
        this.padBottom = bottom;
        computeKeys(keys, left, top, right, bottom);
    }

    /** 设置右盘 (候选修改盘) 矩形并计算按键中心 */
    public void setRightPad(int left, int top, int right, int bottom) {
        rightLeft = left; rightTop = top; rightRight = right; rightBottom = bottom;
        rightKeys = new Point[10];
        computeKeys(rightKeys, left, top, right, bottom);
    }

    private static void computeKeys(Point[] keys, int l, int t, int r, int b) {
        for (int d = 1; d <= 9; d++) {
            int row = (d - 1) / 3;
            int col = (d - 1) % 3;
            keys[d] = new Point(
                    l + Math.round((col + 0.5f) * (r - l) / 3f),
                    t + Math.round((row + 0.5f) * (b - t) / 3f));
        }
    }

    public boolean isValid() {
        return (padRight - padLeft) > 80 && (padBottom - padTop) > 80;
    }

    /** 右盘是否已校准 (数对剔除需要) */
    public boolean hasRightPad() {
        return rightKeys != null && (rightRight - rightLeft) > 80
                && (rightBottom - rightTop) > 80;
    }

    /** 序列化: "L,T,R,B|l,t,r,b"; 无右盘时仅 "L,T,R,B" */
    public String serialize() {
        String left = padLeft + "," + padTop + "," + padRight + "," + padBottom;
        if (!hasRightPad()) return left;
        return left + "|" + rightLeft + "," + rightTop + "," + rightRight + "," + rightBottom;
    }

    public static PadConfig deserialize(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String[] halves = s.split("\\|");
            String[] p = halves[0].split(",");
            if (p.length != 4) return null;
            PadConfig cfg = new PadConfig(
                    Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            if (halves.length == 2) {
                String[] q = halves[1].split(",");
                if (q.length == 4) {
                    cfg.setRightPad(
                            Integer.parseInt(q[0]), Integer.parseInt(q[1]),
                            Integer.parseInt(q[2]), Integer.parseInt(q[3]));
                }
            }
            return cfg;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
