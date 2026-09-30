package com.shuduqinghelper;

import android.graphics.Bitmap;

/**
 * 网格自动检测: 利用棋盘区域与深青色背景的对比, 自动定位 9x9 网格.
 * 替代手动点两角的校准方式, 避免人为点击误差和覆盖层坐标偏移.
 *
 * 原理 (v2: 改用"非深青背景"行投影, 修复圆角处 top 偏下):
 *   1. 降采样, 每像素分类:
 *        - 深青背景: 亮度<130 且 蓝-红>50 (强青色调, 如 BGR(159,131,0))
 *        - 白色像素: 三通道均>200 (棋盘内部白底)
 *   2. 行投影: 取"非深青背景计数 > 70% 屏宽"的连续行带 = 棋盘上下边界
 *      (旧版用纯白>80% 屏宽, 但圆角矩形最顶/底行的白色像素被两侧圆角
 *       吃掉达不到 80%, 导致 top 偏下, 红点全部偏下, 后续点格落到错格)
 *   3. 列投影: 在棋盘行范围内, 列白色计数 > 棋盘高 80% -> 棋盘的左右边界
 *   4. 正方形校验: 棋盘宽 ~= 高
 */
public class GridDetector {

    /**
     * 在截屏中检测数独网格.
     * @return GridConfig (原始分辨率坐标), 失败返回 null
     */
    public static GridConfig detect(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        // 降采样提速: 短边约 360px, 足够定位
        int scale = Math.max(1, Math.min(w, h) / 360);
        int sw = w / scale;
        int sh = h / scale;

        // 白色掩码 + 深青色背景掩码 + 行投影(非深青计数)
        boolean[] white = new boolean[sw * sh];
        int[] whiteRowCount = new int[sh];
        int[] nonTealRowCount = new int[sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int p = bmp.getPixel(x * scale, y * scale);
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                int br = (r + g + b) / 3;
                boolean isWhite = r > 200 && g > 200 && b > 200;
                // 深青背景: 亮度<130 且 蓝-红>50 (强青色调)
                boolean isDarkTeal = br < 130 && (b - r) > 50;
                if (isWhite) {
                    white[y * sw + x] = true;
                    whiteRowCount[y]++;
                }
                if (!isDarkTeal) nonTealRowCount[y]++;
            }
        }

        // 棋盘的上下边界: 非深青背景计数 > 屏宽 70% 的连续行带
        // (70% 比纯白 80% 宽松, 能捕获圆角处的过渡色行; 标题栏等小元素
        //  虽有非深青像素, 但计数远低于 70% 屏宽, 不会被误判)
        int rowThr = (int) (sw * 0.70);
        int top = -1, bottom = -1;
        for (int y = 0; y < sh; y++) {
            if (nonTealRowCount[y] > rowThr) {
                if (top < 0) top = y;
                bottom = y;
            }
        }
        // 棋盘高度至少是屏幕的 1/6, 防止误检
        if (top < 0 || bottom - top < sh / 6) return null;

        // 棋盘的左右边界: 在棋盘行范围内统计列投影 (仍用纯白, 列方向圆角影响小)
        int boardH = bottom - top + 1;
        int colThr = (int) (boardH * 0.80);
        int left = -1, right = -1;
        for (int x = 0; x < sw; x++) {
            int cnt = 0;
            for (int y = top; y <= bottom; y++) {
                if (white[y * sw + x]) cnt++;
            }
            if (cnt > colThr) {
                if (left < 0) left = x;
                right = x;
            }
        }
        if (left < 0) return null;

        int boardW = right - left + 1;
        // 数独棋盘是正方形, 宽高差不超过 10%
        if (Math.abs(boardW - boardH) / (float) Math.min(boardW, boardH) > 0.10f) {
            return null;
        }

        // 还原到原始分辨率
        int origLeft = left * scale;
        int origTop = top * scale;
        int origRight = (right + 1) * scale;
        int origBottom = (bottom + 1) * scale;
        int origW = origRight - origLeft;
        int origH = origBottom - origTop;

        // 数独棋盘是正方形; 检测到的宽和高可能因边框/粗线略有偏差.
        // 取较大值作为正方形边长, 上下/左右双向居中扩展 (单方向扩展会导致整体偏移,
        // 顶部粗边框白色计数常不够 80% 阈值被漏掉, 若只向下补齐会让 top 偏下、红点偏下).
        int side = Math.max(origW, origH);
        int dW = (side - origW) / 2;
        int dH = (side - origH) / 2;
        origLeft -= dW;
        origRight += dW;
        origTop -= dH;
        origBottom += dH;

        return new GridConfig(origLeft, origTop, origRight, origBottom);
    }
}
