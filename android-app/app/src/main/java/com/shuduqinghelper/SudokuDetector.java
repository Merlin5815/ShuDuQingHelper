package com.shuduqinghelper;

import android.graphics.Bitmap;
import android.graphics.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * 数独识别器 (v7) —— 真机截图数据校准版.
 *
 * 游戏 UI 三种墨迹 (BGR/RGB 实测):
 *   1. 黑色已填数字:  亮度<100, 中性 (r≈g≈b), 粗体大字
 *   2. 青绿已填数字:  玩家填入, 亮青 RGB≈(80,180,180), b-r≈95, 粗体大字
 *   3. 灰色候选小字:  中性灰, 核心亮度≈148, 小号字 (~20px),
 *      严格按数字键盘的 3x3 固定位置摆放:
 *        1(左上) 2(上中) 3(右上)
 *        4(中左) 5(正中) 6(中右)
 *        7(左下) 8(下中) 9(右下)
 *      单候选也停留在对应位置, 不居中.
 *
 * 识别:
 *   A. 取单元格中央 86% 区域
 *   B. FILLED: 中央盒(0.25~0.76)内 中性黑>=5% 或 饱和青>=15%
 *      (候选小字在中央盒内占比恒为 0; 外部深青背景 br~96 且强饱和,
 *       但只出现在棋盘圆角处, 中央盒永远看不到)
 *   C. 候选: 中性灰(100<=br<195, 非饱和)像素 -> 3x3 膨胀 -> 连通域,
 *      每块按质心落入固定 3x3 分区判定数字 (面积>=0.6%区域, fy<0.90),
 *      天然避免宽字形(如9)溢入邻窗重复计数; 还能直接得到候选数字.
 *      注意必须用固定分区边界(0.35/0.585, 0.26/0.56), 不能用最近槽位中心:
 *      6 的质心系统性偏左到 fx~0.62, 最近邻会把它误判成 5 (幻影候选).
 */
public class SudokuDetector {

    // 采样区域 (单元格中央 86%, 避开网格线和圆角)
    private static final float REGION = 0.86f;
    private static final float REGION_OFFSET = (1 - REGION) / 2; // 0.07

    // 墨迹颜色
    private static final int BLACK_MAX = 100;   // 中性黑字
    private static final int GRAY_MIN = 100;    // 灰色候选字
    // 原值 195. 曾试 210 (让偏亮 2/3/4 进 mask), 但实测反而让行6 候选 5 消失
    // (连通域合并副作用), 回退 195. 行7 幻影9 根因是 8 字形分裂, 不是亮度.
    private static final int GRAY_MAX = 195;
    private static final int TEAL_MAX = 210;    // 亮青填字
    private static final int SAT_MIN = 40;      // b-r / g-r 饱和差

    // FILLED 中央盒
    private static final float BOX0 = 0.25f, BOX1 = 0.76f;
    private static final float FILLED_BLACK = 0.05f;
    // 青字阈值: 实测细笔画青字(1/7)中央盒青墨最低约12%(亮底高亮/压缩噪声会再压低),
    // 而黑字格/候选格中央盒青墨恒为0%(青色网格线被中央盒排除), 0.08 有充足安全边界
    private static final float FILLED_TEAL = 0.08f;

    // 候选数字按数字键盘 3x3 位置摆放. 用"固定分区"判定而非"最近槽位中心":
    // 实测各数字质心系统性偏左, 其中 6 的质心在 fx~0.62 (槽6中心 0.75),
    // 到槽5中心(0.50)反而更近, 最近邻会把 6 误判成 5 (幻影候选曾导致非法填入).
    // 实测聚类边界: 列1最大0.33 / 列2在0.39-0.55 / 列3最小0.62;
    //               行1最大0.20 / 行2在0.31-0.52 / 行3最小0.59
    private static final float PART_X1 = 0.35f;
    private static final float PART_X2 = 0.585f;
    // 原 0.26: 2/3 字形下部笔画使质心偏下到 fy≈0.27, 0.26 让它误归中行 → 2 归 5, 3 归 6 (行7 漏识2/3 根因).
    // 改 0.30 让 fy=0.27 归上行 → 2/3 正确归位. 验证不影响 4/5/6 (fy≥0.41) 和 1 (fy≤0.26).
    private static final float PART_Y1 = 0.30f;
    // 原 0.56: 6 字形下部圆圈使质心偏下到 fy≈0.58, 0.56 让它误归下行 row=2 → best=9 (行7 幻影9 根因).
    // 实测行3 聚类 ≥0.59, 改 0.59 让 fy=0.56-0.58 归中行 → best=6 (6 是这些格合法候选).
    private static final float PART_Y2 = 0.59f;
    // 原 0.006: 行7 候选 2/3 笔画细, 5x5 膨胀后面积仍 <0.6% 被过滤导致漏识.
    // 幻影9 已由 PART_Y2=0.59 修复, 降低 minArea 风险减小. 0.004 让细笔画域被识别.
    private static final float COMP_MIN_AREA = 0.004f;
    private static final float COMP_MAX_FY = 0.90f;    // 超过此 y 视为底边网格线
    private static final float COMP_MIN_FX = 0.05f;    // 过左碎块(网格线残留)不归属任何数字

    public enum CellState { EMPTY, FILLED, CANDIDATES }

    public static class CellResult {
        public CellState state;
        public int candidateCount;
        public boolean[] digits = new boolean[10];
        public float blackRatio;
        public float tealRatio;
        public java.util.List<float[]> centroids = new java.util.ArrayList<>(); // [fx, fy, digit]
        // 诊断: 灰像素总数 / 通过阈值的连通域数量, 用于定位漏识(像素少)或字形分裂(域数多于候选数)
        public int grayPixelCount;
        public int componentCount;
    }

    /** 从已加载的整块网格像素中分析一个格子, (ox,oy) 为网格左上, gw/gh 网格宽高.
     *  支持非正方形单元 (cellSizeX != cellSizeY) */
    private static CellResult analyzeCell(int[] px, int gw, int gh,
                                          int cellX0, int cellY0,
                                          int cellSizeX, int cellSizeY) {
        CellResult result = new CellResult();
        int mx = Math.max(20, Math.round(cellSizeX * REGION));
        int my = Math.max(20, Math.round(cellSizeY * REGION));
        int ox = cellX0 + Math.round(cellSizeX * REGION_OFFSET);
        int oy = cellY0 + Math.round(cellSizeY * REGION_OFFSET);

        byte[] mask = new byte[mx * my];   // 候选灰
        int total = mx * my;
        int blackCount = 0, tealCount = 0;
        int bx0 = (int) (mx * BOX0), bx1 = (int) (mx * BOX1);
        int by0 = (int) (my * BOX0), by1 = (int) (my * BOX1);
        int boxTotal = 0, boxBlack = 0, boxTeal = 0;

        for (int iy = 0; iy < my; iy++) {
            int sy = oy + iy;
            for (int ix = 0; ix < mx; ix++) {
                int sx = ox + ix;
                if (sx < 0 || sy < 0 || sx >= gw || sy >= gh) continue;
                int p = px[sy * gw + sx];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int br = (r + g + b) / 3;
                boolean sat = (b - r > SAT_MIN) || (g - r > SAT_MIN);

                if (!sat && br < BLACK_MAX) blackCount++;
                if (sat && br < TEAL_MAX) tealCount++;
                if (!sat && br >= GRAY_MIN && br < GRAY_MAX) {
                    mask[iy * mx + ix] = 1;
                    result.grayPixelCount++;
                }

                if (ix >= bx0 && ix < bx1 && iy >= by0 && iy < by1) {
                    boxTotal++;
                    if (!sat && br < BLACK_MAX) boxBlack++;
                    if (sat && br < TEAL_MAX) boxTeal++;
                }
            }
        }
        result.blackRatio = blackCount / (float) total;
        result.tealRatio = tealCount / (float) total;

        if (boxTotal > 0) {
            if (boxBlack / (float) boxTotal >= FILLED_BLACK
                    || boxTeal / (float) boxTotal >= FILLED_TEAL) {
                result.state = CellState.FILLED;
                return result;
            }
        }

        // 5x5 膨胀 (原 3x3 对细笔画候选 2/3 膨胀后面积不足 minArea 会漏识,
        // 改 5x5 让细笔画连成更大域达到阈值; minArea 不降低避免引入小噪声幻影.
        // 候选字按 3x3 摆放, 相邻中心距 ~28-30px, 5x5 半径 2px 不会让相邻候选连成一块.
        byte[] dil = new byte[mx * my];
        for (int iy = 0; iy < my; iy++) {
            for (int ix = 0; ix < mx; ix++) {
                if (mask[iy * mx + ix] == 0) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    int yy = iy + dy;
                    if (yy < 0 || yy >= my) continue;
                    for (int dx = -2; dx <= 2; dx++) {
                        int xx = ix + dx;
                        if (xx < 0 || xx >= mx) continue;
                        dil[yy * mx + xx] = 1;
                    }
                }
            }
        }

        // 8 连通域 (BFS), 收集面积与质心
        byte[] seen = new byte[mx * my];
        int[] stack = new int[total];
        int minArea = (int) (total * COMP_MIN_AREA);

        for (int i = 0; i < total; i++) {
            if (dil[i] == 0 || seen[i] != 0) continue;
            int sp = 0;
            stack[sp++] = i;
            seen[i] = 1;
            int area = 0;                          // 膨胀域面积 (用于阈值)
            int origArea = 0, origSumX = 0, origSumY = 0;  // 原始mask像素 (用于质心)
            while (sp > 0) {
                int idx = stack[--sp];
                int y = idx / mx, x = idx % mx;
                area++;
                if (mask[idx] != 0) {
                    origArea++; origSumX += x; origSumY += y;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= my) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int xx = x + dx;
                        if (xx < 0 || xx >= mx) continue;
                        int ni = yy * mx + xx;
                        if (dil[ni] != 0 && seen[ni] == 0) {
                            seen[ni] = 1;
                            stack[sp++] = ni;
                        }
                    }
                }
            }
            if (area < minArea) continue;
            if (origArea == 0) continue;
            float fx = (origSumX / (float) origArea) / mx;
            float fy = (origSumY / (float) origArea) / my;
            if (fy > COMP_MAX_FY) continue; // 底边网格线
            if (fx < COMP_MIN_FX || fx > 1 - COMP_MIN_FX) continue; // 两侧网格线残留
            result.componentCount++;
            // 固定 3x3 分区归属 (实测质心聚类边界, 见常量注释)
            int col = fx < PART_X1 ? 0 : (fx < PART_X2 ? 1 : 2);
            int row = fy < PART_Y1 ? 0 : (fy < PART_Y2 ? 1 : 2);
            int best = row * 3 + col + 1;
            // 记录质心用于诊断
            result.centroids.add(new float[]{fx, fy, best});
            if (!result.digits[best]) {
                result.digits[best] = true;
                result.candidateCount++;
            }
        }

        result.state = result.candidateCount == 0 ? CellState.EMPTY : CellState.CANDIDATES;
        return result;
    }

    public static CellResult[][] scanBoard(Bitmap bitmap, GridConfig config) {
        int gx = config.gridLeft, gy = config.gridTop;
        int gw = config.gridRight - gx, gh = config.gridBottom - gy;
        int[] px = new int[gw * gh];
        bitmap.getPixels(px, 0, gw, gx, gy, gw, gh);

        int csx = Math.round(config.cellSizeX);
        int csy = Math.round(config.cellSizeY);
        CellResult[][] board = new CellResult[9][9];
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                int x0 = Math.round(c * config.cellSizeX);
                int y0 = Math.round(r * config.cellSizeY);
                board[r][c] = analyzeCell(px, gw, gh, x0, y0, csx, csy);
            }
        }
        return board;
    }

    /** 双击后验证单格 */
    public static CellResult analyzeSingleCell(Bitmap bitmap, int cx, int cy, float cellSize) {
        int cs = Math.round(cellSize);
        int m = Math.max(20, Math.round(cellSize * REGION));
        int x0 = cx - m / 2, y0 = cy - m / 2;
        int[] px = new int[m * m];
        bitmap.getPixels(px, 0, m, Math.max(0, x0), Math.max(0, y0), m, m);
        // 以子图为坐标系: 格子边界 = 中央区域外扩 offset
        int full = Math.round(m / REGION);
        int off = (full - m) / 2;
        return analyzeCell(px, m, m, -off, -off, full, full);
    }

    public static List<int[]> findSingleCandidates(CellResult[][] board) {
        List<int[]> singles = new ArrayList<>();
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (board[r][c].state == CellState.CANDIDATES
                        && board[r][c].candidateCount == 1) {
                    singles.add(new int[]{r, c});
                }
            }
        }
        return singles;
    }

    public static String boardToString(CellResult[][] board) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                CellResult res = board[r][c];
                switch (res.state) {
                    case EMPTY: sb.append(". "); break;
                    case FILLED: sb.append("F "); break;
                    default: sb.append(res.candidateCount).append(" ");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 每格候选数字串, 如 "158" / "." / "F" */
    public static String digitsMatrixToString(CellResult[][] board) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                CellResult res = board[r][c];
                String s;
                if (res.state == CellState.FILLED) s = "F";
                else if (res.state == CellState.EMPTY) s = ".";
                else {
                    StringBuilder d = new StringBuilder();
                    for (int k = 1; k <= 9; k++) if (res.digits[k]) d.append(k);
                    s = d.toString();
                }
                sb.append(String.format("%-5s", s));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 每格候选质心诊断: 输出 (fx,fy)→匹配数字, 用于校准 SLOT_X/SLOT_Y.
     *  末尾附 [gray=N comp=M]: 灰像素总数/通过阈值的连通域数量.
     *  - gray高但comp少: 漏识(灰像素没形成有效连通域, 可能面积<minArea或被过滤)
     *  - comp > 候选数: 字形分裂(一个数字被分成多个连通域, 如 8 上下圆圈分离) */
    public static String centroidsToString(CellResult[][] board) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                CellResult res = board[r][c];
                if (res.state != CellState.CANDIDATES) continue;
                sb.append("(").append(r + 1).append(",").append(c + 1).append("):");
                for (float[] ct : res.centroids) {
                    int d = (int) ct[2];
                    sb.append(String.format(" (%.2f,%.2f)→%s", ct[0], ct[1], d > 0 ? d : "?"));
                }
                sb.append(" [gray=").append(res.grayPixelCount)
                  .append(" comp=").append(res.componentCount).append("]\n");
            }
        }
        return sb.toString();
    }

    private static String matrix(float[][] v) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                int pct = Math.round(v[r][c] * 100);
                sb.append(pct < 10 ? "0" : "").append(pct).append(" ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public static String blackMatrixToString(CellResult[][] b) {
        float[][] v = new float[9][9];
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++) v[r][c] = b[r][c].blackRatio;
        return matrix(v);
    }

    public static String tealMatrixToString(CellResult[][] b) {
        float[][] v = new float[9][9];
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++) v[r][c] = b[r][c].tealRatio;
        return matrix(v);
    }
}
