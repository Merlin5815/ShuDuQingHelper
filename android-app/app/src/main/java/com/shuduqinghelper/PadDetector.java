package com.shuduqinghelper;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.Locale;

/**
 * 操作区数字盘自动检测 (连通域版).
 *
 * 屏幕下方操作区: 两个并排白色近方形卡片 (左=填数盘, 右=候选修改盘),
 * 更下面是一条更扁更宽的白色功能条 (撤销/灯泡/设置/删除).
 *
 * 旧版"行投影+列投影"会把数字盘与功能条连成一条高行带 (中间缝隙仅十几px),
 * 导致列阈值失配. 改为与 Python 版 findContours 同思路:
 *   1. 降采样, 棋盘以下区域白色掩码 (rgb 均 > 215)
 *   2. 4 连通域 (BFS) 找白色块, 外包矩形 + 面积
 *   3. 近方形过滤 (宽高比 0.7~1.5, 宽 >= 12% 屏宽)
 *      —— 功能条宽高比 ~7:1 被排除
 *   4. 取最靠上、同高度取最左的块 = 左侧填数盘
 *   5. 兜底: 两盘连体 (宽高比 1.8~2.8) 时取左半
 */
public class PadDetector {

    private static final String TAG = FloatingWindow.LOG_TAG;

    public static PadConfig detect(Bitmap bmp, int belowY) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int scale = Math.max(1, Math.min(w, h) / 360);
        int sw = w / scale;
        int sh = h / scale;
        int startY = Math.max(0, belowY / scale);

        boolean[] white = new boolean[sw * sh];
        for (int y = startY; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int p = bmp.getPixel(x * scale, y * scale);
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                if (r > 215 && g > 215 && b > 215) white[y * sw + x] = true;
            }
        }

        // 4 连通域 BFS, 收集外包矩形
        boolean[] seen = new boolean[sw * sh];
        int[] stack = new int[sw * sh];
        int minW = (int) (sw * 0.12f);
        int minArea = sw * sh / 60;

        // 收集合格的近方形卡片 (数字盘), 以及两盘连体时的宽块兜底
        java.util.List<int[]> cards = new java.util.ArrayList<>(); // {x0,y0,x1,y1}
        int fusedL = -1, fusedT = -1, fusedR = -1, fusedB = -1;

        for (int i = startY * sw; i < sw * sh; i++) {
            if (!white[i] || seen[i]) continue;
            int sp = 0;
            stack[sp++] = i;
            seen[i] = true;
            int x0 = sw, y0 = sh, x1 = -1, y1 = -1, area = 0;
            while (sp > 0) {
                int idx = stack[--sp];
                int yy = idx / sw, xx = idx % sw;
                area++;
                if (xx < x0) x0 = xx;
                if (xx > x1) x1 = xx;
                if (yy < y0) y0 = yy;
                if (yy > y1) y1 = yy;
                // 4 邻接 (上下左右)
                if (xx > 0) {
                    int ni = idx - 1;
                    if (white[ni] && !seen[ni]) { seen[ni] = true; stack[sp++] = ni; }
                }
                if (xx < sw - 1) {
                    int ni = idx + 1;
                    if (white[ni] && !seen[ni]) { seen[ni] = true; stack[sp++] = ni; }
                }
                if (yy > 0) {
                    int ni = idx - sw;
                    if (white[ni] && !seen[ni]) { seen[ni] = true; stack[sp++] = ni; }
                }
                if (yy < sh - 1) {
                    int ni = idx + sw;
                    if (white[ni] && !seen[ni]) { seen[ni] = true; stack[sp++] = ni; }
                }
            }

            int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
            FileLogger.log(String.format(Locale.US,
                    "[数盘检测] 白色块 bbox=(%d,%d)-(%d,%d) %dx%d area=%d",
                    x0, y0, x1, y1, bw, bh, area));
            if (area < minArea || bw < minW) continue;

            float aspect = bw / (float) bh;
            if (aspect >= 0.7f && aspect <= 1.5f) {
                // 单张近方形卡片 (左填数盘 / 右候选盘)
                cards.add(new int[]{x0, y0, x1, y1});
            } else if (aspect >= 1.8f && aspect <= 2.8f) {
                // 两盘连成一块: 记录, 稍后对半切
                if (fusedT < 0 || y0 < fusedT) {
                    fusedL = x0; fusedT = y0; fusedR = x1; fusedB = y1;
                }
            }
        }

        // 只取最上面一排卡片 (功能条更扁已被排除), 按 x 排序 = 左盘, 右盘
        java.util.List<int[]> row = new java.util.ArrayList<>();
        if (!cards.isEmpty()) {
            int minY = Integer.MAX_VALUE;
            for (int[] cd : cards) minY = Math.min(minY, cd[1]);
            for (int[] cd : cards) if (cd[1] <= minY + 3) row.add(cd);
            row.sort((a, b) -> a[0] - b[0]);
        }

        int[] left = null, right = null;
        if (row.size() >= 2) {
            left = row.get(0);
            right = row.get(1);
        } else if (fusedT >= 0) {
            // 连体兜底: 整块对半切
            int half = (fusedR - fusedL + 1) / 2;
            left = new int[]{fusedL, fusedT, fusedL + half - 1, fusedB};
            right = new int[]{fusedL + half, fusedT, fusedR, fusedB};
        } else if (row.size() == 1) {
            left = row.get(0); // 只有左盘也能用 (隐式唯一), 右盘剔除将提示重新校准
        }

        if (left == null) {
            Log.w(TAG, "pad detect: no qualifying white blob (minW=" + minW
                    + " minArea=" + minArea + " startY=" + startY + ")");
            FileLogger.log("[数盘检测] 失败: 未找到近方形白色卡片");
            return null;
        }

        int ls = Math.min(left[2] - left[0] + 1, left[3] - left[1] + 1);
        if (ls < minW) {
            Log.w(TAG, "pad detect: square side too small " + ls);
            return null;
        }
        PadConfig cfg = new PadConfig(left[0] * scale, left[1] * scale,
                (left[0] + ls) * scale, (left[1] + ls) * scale);
        if (right != null) {
            int rs = Math.min(right[2] - right[0] + 1, right[3] - right[1] + 1);
            if (rs >= minW) {
                cfg.setRightPad(right[0] * scale, right[1] * scale,
                        (right[0] + rs) * scale, (right[1] + rs) * scale);
            }
        }
        Log.i(TAG, "pad detected: " + cfg.serialize());
        FileLogger.log("[数盘检测] 成功: 左盘=" + (ls * scale) + "px, 右盘="
                + (cfg.hasRightPad() ? "已定位" : "未找到"));
        return cfg;
    }
}
