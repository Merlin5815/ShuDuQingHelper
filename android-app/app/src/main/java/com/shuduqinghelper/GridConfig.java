package com.shuduqinghelper;

import android.graphics.Point;

/**
 * 数独网格坐标配置.
 * 由用户手动校准: 点网格左上角和右下角, 自动计算 9x9 格子中心.
 * 坐标存在 SharedPreferences 中, 下次启动直接用.
 */
public class GridConfig {

    public int gridLeft, gridTop, gridRight, gridBottom;
    public float cellSize;       // min(cellSizeX, cellSizeY), 用于单格验证等
    public float cellSizeX;      // 水平方向每格宽度
    public float cellSizeY;      // 垂直方向每格高度
    // cells[r][c] = 格子中心坐标
    public Point[][] cells = new Point[9][9];

    public GridConfig(int left, int top, int right, int bottom) {
        this.gridLeft = left;
        this.gridTop = top;
        this.gridRight = right;
        this.gridBottom = bottom;
        compute();
    }

    private void compute() {
        float dx = gridRight - gridLeft;
        float dy = gridBottom - gridTop;
        cellSizeX = dx / 9f;
        cellSizeY = dy / 9f;
        cellSize = Math.min(cellSizeX, cellSizeY);
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                cells[r][c] = new Point(
                        (int) (gridLeft + (c + 0.5f) * cellSizeX),
                        (int) (gridTop + (r + 0.5f) * cellSizeY));
            }
        }
    }

    public boolean isValid() {
        return cellSize > 20;
    }

    /** 序列化: "left,top,right,bottom" */
    public String serialize() {
        return gridLeft + "," + gridTop + "," + gridRight + "," + gridBottom;
    }

    public static GridConfig deserialize(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] parts = s.split(",");
        if (parts.length != 4) return null;
        try {
            return new GridConfig(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
