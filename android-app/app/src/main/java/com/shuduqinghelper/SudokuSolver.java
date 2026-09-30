package com.shuduqinghelper;

/**
 * 数独内存推演模型 (链式裸候选).
 *
 * 候选数字直接来自屏幕灯泡的灰色候选字 (由 SudokuDetector 识别),
 * 无需 OCR 已填格的具体数字 —— 初始候选集已经包含全部已填数字的约束.
 *
 * 当某格只有一个候选 d 并双击填入后, 按标准数独规则在本地级联:
 *   同一行 / 同一列 / 同一 3x3 宫内所有候选格移除 d.
 * 这与游戏灯泡自动维护候选的规则一致, 因此链条中间无需重新截屏识别,
 * 直到本地再也找不到单候选, 再全屏截一次校正.
 */
public class SudokuSolver {

    // value[r][c]:
    //   0  = 未填空格 (候选格或纯空格)
    //  -1  = 已填, 但具体数字未知 (黑字固有 / 之前填入的青字, 不参与候选)
    // 1..9 = 本次自动填充链条中由本程序填入的数字
    public final int[][] value = new int[9][9];
    public final boolean[][][] cand = new boolean[9][9][10];

    /** 由一帧识别结果重建模型 */
    public void init(SudokuDetector.CellResult[][] board) {
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                SudokuDetector.CellResult cell = board[r][c];
                if (cell.state == SudokuDetector.CellState.FILLED) {
                    value[r][c] = -1;
                    for (int d = 1; d <= 9; d++) cand[r][c][d] = false;
                } else {
                    value[r][c] = 0;
                    for (int d = 1; d <= 9; d++) {
                        cand[r][c][d] = cell.digits[d];
                    }
                }
            }
        }
    }

    /** 找一个裸单候选格. @return {row, col, digit}, 没有返回 null */
    public int[] findSingle() {
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (value[r][c] != 0) continue;
                int d = 0, cnt = 0;
                for (int k = 1; k <= 9; k++) {
                    if (cand[r][c][k]) { d = k; cnt++; }
                }
                if (cnt == 1) return new int[]{r, c, d};
            }
        }
        return null;
    }

    /** 提交 (r,c) 填入 d: 标记已填并级联剔除同行/列/宫的候选 d */
    public void commit(int r, int c, int d) {
        value[r][c] = d;
        for (int k = 1; k <= 9; k++) cand[r][c][k] = false;
        for (int i = 0; i < 9; i++) {
            removeCandidate(r, i, d);
            removeCandidate(i, c, d);
        }
        int br = (r / 3) * 3, bc = (c / 3) * 3;
        for (int dr = 0; dr < 3; dr++) {
            for (int dc = 0; dc < 3; dc++) {
                removeCandidate(br + dr, bc + dc, d);
            }
        }
    }

    private void removeCandidate(int r, int c, int d) {
        if (value[r][c] == 0) cand[r][c][d] = false;
    }

    /** 剔除 (r,c) 的候选 d (数对/三链数推导后, 通过右侧候选盘操作) */
    public void eliminate(int r, int c, int d) {
        if (value[r][c] == 0) cand[r][c][d] = false;
    }

    /**
     * 若从 (r,c) 去掉候选 d 后恰好只剩 1 个候选, 返回那个被迫填入的数字; 否则返回 0.
     * 用途: 游戏会把只剩单候选的格自动填入, 且不级联刷新其他格候选,
     * 导致屏幕候选与已填数不自洽(安全门会误判为幻影). 检测到这种情况时,
     * 调用方应改为直接用左盘填入该数字(游戏正常级联), 效果与剔除等价.
     */
    public int soleOtherCandidate(int r, int c, int d) {
        if (value[r][c] != 0 || !cand[r][c][d]) return 0;
        int cnt = 0, other = 0;
        for (int k = 1; k <= 9; k++) {
            if (cand[r][c][k]) { cnt++; if (k != d) other = k; }
        }
        return cnt == 2 ? other : 0;
    }

    /**
     * 候选剔除动作: 从 (r,c) 删除候选 d.
     * tech: 0=显性子集 1=宫摒除(pointing) 2=行列摒除(claiming)
     * 显性子集: unitType/unitIdx=所在单位, subsetSize=2/3/4, subsetDigits=子集数字
     * 宫摒除:   unitType=2, unitIdx=宫号; lockType=0/1 锁定行/列, lockIdx=行/列号, d=数字
     * 行列摒除: unitType=0/1 来源行/列; lockType=2, lockIdx=宫号, d=数字
     */
    public static class Elimination {
        public final int r, c, d, unitType, unitIdx;
        public final int tech, subsetSize, lockType, lockIdx;
        public final int[] subsetDigits;
        Elimination(int r, int c, int d, int unitType, int unitIdx,
                    int subsetSize, int[] subsetDigits) {
            this.r = r; this.c = c; this.d = d;
            this.unitType = unitType; this.unitIdx = unitIdx;
            this.tech = 0; this.subsetSize = subsetSize;
            this.lockType = -1; this.lockIdx = -1;
            this.subsetDigits = subsetDigits;
        }
        Elimination(int r, int c, int d, int tech, int unitType, int unitIdx,
                    int lockType, int lockIdx) {
            this.r = r; this.c = c; this.d = d;
            this.tech = tech;
            this.unitType = unitType; this.unitIdx = unitIdx;
            this.subsetSize = 0; this.lockType = lockType; this.lockIdx = lockIdx;
            this.subsetDigits = new int[]{d};
        }
    }

    /**
     * 锁定候选 (Locked Candidates):
     *
     * 宫摒除 Pointing: 某宫内数字 d 的所有候选格都在同一行(列),
     *   则 d 必落在宫内, 该行(列)宫内以外的格删除 d.
     *
     * 行列摒除 Claiming: 某行(列)内数字 d 的所有候选格都在同一个宫,
     *   则 d 必落在该宫的这行(列)上, 宫内该行(列)以外的格删除 d.
     *
     * 返回第一批剔除动作.
     */
    public java.util.List<Elimination> findLockingCandidates() {
        // ---- 宫摒除 ----
        for (int boxIdx = 0; boxIdx < 9; boxIdx++) {
            int br = (boxIdx / 3) * 3, bc = (boxIdx % 3) * 3;
            for (int d = 1; d <= 9; d++) {
                int rows = 0, cols = 0, cnt = 0;
                for (int dr = 0; dr < 3; dr++) {
                    for (int dc = 0; dc < 3; dc++) {
                        int r = br + dr, c = bc + dc;
                        if (value[r][c] == 0 && cand[r][c][d]) {
                            rows |= 1 << r; cols |= 1 << c; cnt++;
                        }
                    }
                }
                if (cnt < 2) continue;
                if (Integer.bitCount(rows) == 1) {
                    int r0 = Integer.numberOfTrailingZeros(rows);
                    java.util.List<Elimination> out = new java.util.ArrayList<>();
                    for (int c = 0; c < 9; c++) {
                        if (c / 3 == bc / 3) continue; // 同宫的列段跳过
                        if (value[r0][c] == 0 && cand[r0][c][d]) {
                            out.add(new Elimination(r0, c, d, 1, 2, boxIdx, 0, r0));
                        }
                    }
                    if (!out.isEmpty()) return out;
                }
                if (Integer.bitCount(cols) == 1) {
                    int c0 = Integer.numberOfTrailingZeros(cols);
                    java.util.List<Elimination> out = new java.util.ArrayList<>();
                    for (int r = 0; r < 9; r++) {
                        if (r / 3 == br / 3) continue; // 同宫的行段跳过
                        if (value[r][c0] == 0 && cand[r][c0][d]) {
                            out.add(new Elimination(r, c0, d, 1, 2, boxIdx, 1, c0));
                        }
                    }
                    if (!out.isEmpty()) return out;
                }
            }
        }
        // ---- 行列摒除 ----
        for (int type = 0; type < 2; type++) {
            for (int idx = 0; idx < 9; idx++) {
                for (int d = 1; d <= 9; d++) {
                    int boxes = 0, cnt = 0;
                    int[] rc = new int[2];
                    for (int i = 0; i < 9; i++) {
                        unitCell(type, idx, i, rc);
                        if (value[rc[0]][rc[1]] == 0 && cand[rc[0]][rc[1]][d]) {
                            boxes |= 1 << ((rc[0] / 3) * 3 + rc[1] / 3);
                            cnt++;
                        }
                    }
                    if (cnt < 2 || Integer.bitCount(boxes) != 1) continue;
                    int boxIdx = Integer.numberOfTrailingZeros(boxes);
                    int br = (boxIdx / 3) * 3, bc = (boxIdx % 3) * 3;
                    java.util.List<Elimination> out = new java.util.ArrayList<>();
                    for (int dr = 0; dr < 3; dr++) {
                        for (int dc = 0; dc < 3; dc++) {
                            int r = br + dr, c = bc + dc;
                            boolean onLine = (type == 0) ? r == idx : c == idx;
                            if (onLine) continue;
                            if (value[r][c] == 0 && cand[r][c][d]) {
                                out.add(new Elimination(r, c, d, 2, type, idx, 2, boxIdx));
                            }
                        }
                    }
                    if (!out.isEmpty()) return out;
                }
            }
        }
        return new java.util.ArrayList<>();
    }

    /**
     * 显性子集 (Naked Subsets): 某行/列/宫内 k 个未填格 (k=2,3,4)
     * 的候选并集恰好是 k 个数字, 则这 k 个数字必落在这 k 格,
     * 该单位其余格的这些候选一律剔除.
     *   k=2 数对: 两格都是 {6,8}
     *   k=3 三链数: 如 {1,2},{2,3},{1,2,3}, 并集恰为 {1,2,3}
     * 返回当前找到的第一批剔除动作 (来自同一个子集), 执行后重新推导.
     */
    public java.util.List<Elimination> findNakedSubsets() {
        for (int k = 2; k <= 4; k++) {
            for (int type = 0; type < 3; type++) {
                for (int idx = 0; idx < 9; idx++) {
                    java.util.List<Elimination> elims = scanNakedUnit(type, idx, k);
                    if (!elims.isEmpty()) return elims;
                }
            }
        }
        return new java.util.ArrayList<>();
    }

    private java.util.List<Elimination> scanNakedUnit(int type, int idx, int k) {
        // 单位内未填且有候选的格
        java.util.List<Integer> cells = new java.util.ArrayList<>();
        int[] rc = new int[2];
        for (int i = 0; i < 9; i++) {
            unitCell(type, idx, i, rc);
            if (value[rc[0]][rc[1]] == 0 && hasAny(rc[0], rc[1])) cells.add(i);
        }
        int n = cells.size();
        if (n <= k) return new java.util.ArrayList<>(); // 全占满无需剔除

        java.util.List<Elimination> result = new java.util.ArrayList<>();
        // 枚举 k 格组合 (n<=9, C(9,4)=126, 开销可忽略)
        int[] combo = new int[k];
        if (findCombo(cells, combo, 0, 0, k, type, idx, result)) return result;
        return result;
    }

    private boolean findCombo(java.util.List<Integer> cells, int[] combo,
                              int start, int depth, int k,
                              int type, int idx, java.util.List<Elimination> out) {
        if (depth == k) {
            // 并集
            boolean[] union = new boolean[10];
            int[] rc = new int[2];
            for (int i : combo) {
                unitCell(type, idx, cells.get(i), rc);
                for (int d = 1; d <= 9; d++) if (cand[rc[0]][rc[1]][d]) union[d] = true;
            }
            int cnt = 0;
            for (int d = 1; d <= 9; d++) if (union[d]) cnt++;
            if (cnt != k) return false;
            int[] digits = new int[k];
            int p = 0;
            for (int d = 1; d <= 9; d++) if (union[d]) digits[p++] = d;

            java.util.List<Integer> members = new java.util.ArrayList<>();
            for (int i : combo) members.add(cells.get(i));

            // 单位内非成员格, 删除并集中的候选
            for (int i = 0; i < 9; i++) {
                if (members.contains(i)) continue;
                unitCell(type, idx, i, rc);
                int r = rc[0], c = rc[1];
                if (value[r][c] != 0) continue;
                for (int d = 1; d <= 9; d++) {
                    if (union[d] && cand[r][c][d]) {
                        out.add(new Elimination(r, c, d, type, idx, k, digits));
                    }
                }
            }
            return !out.isEmpty();
        }
        for (int i = start; i <= cells.size() - (k - depth); i++) {
            combo[depth] = i;
            if (findCombo(cells, combo, i + 1, depth + 1, k, type, idx, out)) return true;
        }
        return false;
    }

    private boolean hasAny(int r, int c) {
        for (int d = 1; d <= 9; d++) if (cand[r][c][d]) return true;
        return false;
    }

    /**
     * 试推矛盾法 (Nishio / 反证法):
     * 假设未填格 (r,c) 填入候选 d, 在假设模型上施加全部逻辑规则
     * (裸候选/隐式唯一/宫摒除/行列摒除/显性数对~四链数) 传播,
     * 再用回溯穷举检验该假设是否"全盘无解".
     * 若假设导致无解, 则 d 必为假候选, 严格剔除.
     * 这是 X-Wing/XY-Wing/单数链等一切高级技巧的通用兜底, 可保证最终破局.
     */
    public java.util.List<Elimination> findTrialEliminations() {
        java.util.List<Elimination> out = new java.util.ArrayList<>();
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (value[r][c] != 0) continue;
                for (int d = 1; d <= 9; d++) {
                    if (!cand[r][c][d]) continue;
                    int[][] v = copyValue();
                    boolean[][][] cd = copyCand();
                    if (trialPlace(v, cd, r, c, d) && !trialHasSolution(v, cd)) {
                        out.add(new Elimination(r, c, d, 3, -1, -1, -1, -1));
                        return out; // 一次只返回一个, 执行后链式重新推导
                    }
                }
            }
        }
        return out;
    }

    /**
     * 推断游戏自动填入格的数字.
     *
     * 场景: 链条中用右盘剔除某格候选后, 游戏自身逻辑把该格自动填入 (变 FILLED),
     * 但不级联刷新其他格候选, 导致下一帧安全门误判幻影. 此时需要确定该格填的
     * 到底是哪个数字, 才能正确级联剔除同行/列/宫的候选.
     *
     * 方法: 对该格旧候选集的每个数字 d, 假设 (r,c)=d 并级联剔除, 用 DFS 检验
     * 全盘是否有解. 数独有唯一解, 恰好一个 d 有解即为答案; 多个有解 (歧义) 或
     * 零个有解 (模型有误) 则返回 0.
     *
     * @param r,c 目标格坐标 (当前模型中 value=-1)
     * @param oldCandidates 该格被自动填入前的候选 (来自链条中 Solver 模型)
     * @return 推断出的数字 1-9, 或 0 表示无法确定
     */
    public int inferFilledDigit(int r, int c, boolean[] oldCandidates) {
        int answer = 0;
        for (int d = 1; d <= 9; d++) {
            if (!oldCandidates[d]) continue;
            int[][] v = copyValue();
            boolean[][][] cd = copyCand();
            // 假设 (r,c)=d 并级联剔除同行/列/宫
            v[r][c] = d;
            for (int k = 1; k <= 9; k++) cd[r][c][k] = false;
            for (int i = 0; i < 9; i++) {
                cd[r][i][d] = false;
                cd[i][c][d] = false;
            }
            int br = (r / 3) * 3, bc = (c / 3) * 3;
            for (int a = br; a < br + 3; a++)
                for (int b = bc; b < bc + 3; b++)
                    cd[a][b][d] = false;
            if (trialHasSolution(v, cd)) {
                if (answer != 0) return 0; // 多个有解, 无法确定
                answer = d;
            }
        }
        return answer;
    }

    /**
     * 全屏校正后安全门失败时, 尝试推断游戏自动填入格的数字并修复模型.
     *
     * 游戏自动填入某格后不级联刷新其他格候选, 屏幕候选残留该数字 (幻影),
     * 导致该格所在的行/列/宫三个单位同时不自洽 (并集各多 1 个候选).
     * 本方法定位三个失败单位的交集格 (value=-1), 从并集交集推断可能数字,
     * 用 inferFilledDigit 试推确定, 提交并级联剔除幻影, 使模型恢复自洽.
     *
     * @return true 表示已修复且模型自洽; false 表示无法修复 (真识别错误或无法确定).
     */
    public boolean repairAutoFilled() {
        java.util.List<int[]> badUnits = findUnsafeUnits();
        if (badUnits.isEmpty()) return true; // 已自洽

        // 定位三个失败单位 (1 行 + 1 列 + 1 宫) 的交集格
        int badRow = -1, badCol = -1, badBox = -1;
        int rowCount = 0, colCount = 0, boxCount = 0;
        for (int[] u : badUnits) {
            if (u[0] == 0) { badRow = u[1]; rowCount++; }
            else if (u[0] == 1) { badCol = u[1]; colCount++; }
            else { badBox = u[1]; boxCount++; }
        }
        if (rowCount != 1 || colCount != 1 || boxCount != 1) return false;

        int r = badRow, c = badCol;
        if (value[r][c] != -1) return false; // 交集格必须是"已填但数字未知"
        if ((r / 3) * 3 + c / 3 != badBox) return false; // 必须在失败宫内

        // 从三个失败单位的候选并集取交集, 得到可能数字集
        boolean[] possible = new boolean[10];
        for (int d = 1; d <= 9; d++) possible[d] = true;
        int[] rc = new int[2];
        for (int[] u : badUnits) {
            boolean[] inUnion = new boolean[10];
            for (int i = 0; i < 9; i++) {
                unitCell(u[0], u[1], i, rc);
                if (value[rc[0]][rc[1]] == 0) {
                    for (int d = 1; d <= 9; d++) if (cand[rc[0]][rc[1]][d]) inUnion[d] = true;
                }
            }
            for (int d = 1; d <= 9; d++) if (!inUnion[d]) possible[d] = false;
        }

        int inferred = inferFilledDigit(r, c, possible);
        if (inferred == 0) return false;

        commit(r, c, inferred);
        return findUnsafeUnits().isEmpty();
    }

    private int[][] copyValue() {
        int[][] v = new int[9][9];
        for (int r = 0; r < 9; r++)
            System.arraycopy(value[r], 0, v[r], 0, 9);
        return v;
    }

    private boolean[][][] copyCand() {
        boolean[][][] cd = new boolean[9][9][10];
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                System.arraycopy(cand[r][c], 0, cd[r][c], 0, 10);
        return cd;
    }

    /** 在假设模型中填入 d; 与已填数字冲突返回 false */
    private boolean trialPlace(int[][] v, boolean[][][] cd, int r, int c, int d) {
        for (int i = 0; i < 9; i++) {
            if (i != c && v[r][i] == d) return false;
            if (i != r && v[i][c] == d) return false;
        }
        int br = (r / 3) * 3, bc = (c / 3) * 3;
        for (int a = br; a < br + 3; a++)
            for (int b = bc; b < bc + 3; b++)
                if ((a != r || b != c) && v[a][b] == d) return false;
        v[r][c] = d;
        for (int x = 1; x <= 9; x++) cd[r][c][x] = false;
        for (int i = 0; i < 9; i++) {
            cd[r][i][d] = false;
            cd[i][c][d] = false;
        }
        for (int a = br; a < br + 3; a++)
            for (int b = bc; b < bc + 3; b++)
                cd[a][b][d] = false;
        return true;
    }

    /** 假设模型是否存在解: 先逻辑传播, 再分支穷举 */
    private boolean trialHasSolution(int[][] v, boolean[][][] cd) {
        // 把 v=-1 (已填但数字未知的固有格/游戏自动填入格) 转换为带候选集的 v=0 格.
        // 候选集 = 该格所在行/列/宫中已确定数字的补集.
        // 原因: 若保留 v=-1, trialPropagate 的"数字无处可放"检查会因
        // unknownFilled=true 而跳过矛盾判定, 导致试推法对任何候选都判"有解",
        // 从而 findTrialEliminations 永远找不到可剔除的候选, 残局无法推进.
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (v[r][c] != -1) continue;
                boolean[] used = new boolean[10];
                for (int i = 0; i < 9; i++) {
                    int vr = v[r][i], vc = v[i][c];
                    if (vr >= 1) used[vr] = true;
                    if (vc >= 1) used[vc] = true;
                }
                int br = (r / 3) * 3, bc = (c / 3) * 3;
                for (int a = br; a < br + 3; a++)
                    for (int b = bc; b < bc + 3; b++) {
                        int vb = v[a][b];
                        if (vb >= 1) used[vb] = true;
                    }
                v[r][c] = 0;
                for (int d = 1; d <= 9; d++) cd[r][c][d] = !used[d];
            }
        }
        if (!trialPropagate(v, cd)) return false;
        int br = -1, bc = -1, best = 10;
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (v[r][c] != 0) continue;
                int n = 0;
                for (int d = 1; d <= 9; d++) if (cd[r][c][d]) n++;
                if (n == 0) return false;
                if (n < best) { best = n; br = r; bc = c; }
            }
        }
        if (br < 0) return true; // 全盘填满
        for (int d = 1; d <= 9; d++) {
            if (!cd[br][bc][d]) continue;
            int[][] v2 = new int[9][9];
            boolean[][][] cd2 = new boolean[9][9][10];
            for (int r = 0; r < 9; r++) {
                System.arraycopy(v[r], 0, v2[r], 0, 9);
                for (int c = 0; c < 9; c++)
                    System.arraycopy(cd[r][c], 0, cd2[r][c], 0, 10);
            }
            if (trialPlace(v2, cd2, br, bc, d) && trialHasSolution(v2, cd2)) return true;
        }
        return false;
    }

    /**
     * 假设模型上的全部逻辑传播, 返回 false 表示推出矛盾:
     * 裸候选 -> 隐式唯一 -> 宫摒除/行列摒除 -> 显性子集 2/3/4, 循环至无动作.
     */
    private boolean trialPropagate(int[][] v, boolean[][][] cd) {
        while (true) {
            // 1. 裸候选
            int sr = -1, sc = -1, sd = 0;
            for (int r = 0; r < 9 && sr < 0; r++)
                for (int c = 0; c < 9; c++) {
                    if (v[r][c] != 0) continue;
                    int only = 0, n = 0;
                    for (int d = 1; d <= 9; d++) if (cd[r][c][d]) { n++; only = d; }
                    if (n == 0) return false;
                    if (n == 1) { sr = r; sc = c; sd = only; break; }
                }
            if (sr >= 0) {
                if (!trialPlace(v, cd, sr, sc, sd)) return false;
                continue;
            }
            // 2. 隐式唯一 + "数字无处可放"矛盾
            // 注意: v=-1 (已填但数字未知的固有格/游戏自动填入格) 不参与约束检查,
            // 其数字已通过候选集的缺失隐式编码. 某数字若 0 候选位但单位内有 v=-1 格,
            // 不能判矛盾——该数字可能就在那个未知已填格中.
            int hr = -1, hc = -1, hd = 0;
            for (int type = 0; type < 3; type++) {
                for (int idx = 0; idx < 9; idx++) {
                    for (int d = 1; d <= 9; d++) {
                        boolean placed = false;
                        boolean unknownFilled = false;
                        int onlyR = -1, onlyC = -1, cnt = 0;
                        int[] rc = new int[2];
                        for (int i = 0; i < 9; i++) {
                            unitCell(type, idx, i, rc);
                            int vv = v[rc[0]][rc[1]];
                            if (vv == d) { placed = true; }
                            else if (vv == -1) { unknownFilled = true; }
                            else if (vv == 0 && cd[rc[0]][rc[1]][d]) {
                                cnt++; onlyR = rc[0]; onlyC = rc[1];
                            }
                        }
                        if (placed) continue;
                        if (cnt == 0) {
                            if (unknownFilled) continue; // d 可能在此单位某个未知已填格中
                            return false;
                        }
                        if (cnt == 1 && hr < 0) { hr = onlyR; hc = onlyC; hd = d; }
                    }
                }
            }
            if (hr >= 0) {
                if (!trialPlace(v, cd, hr, hc, hd)) return false;
                continue;
            }
            // 3. 宫摒除/行列摒除
            if (trialLocking(v, cd)) continue;
            // 4. 显性子集 2/3/4
            if (trialNaked(v, cd)) continue;
            return true;
        }
    }

    /** 假设模型上的宫摒除/行列摒除; 执行了剔除返回 true */
    private boolean trialLocking(int[][] v, boolean[][][] cd) {
        // 宫 -> 行/列
        for (int boxIdx = 0; boxIdx < 9; boxIdx++) {
            int br = (boxIdx / 3) * 3, bc = (boxIdx % 3) * 3;
            for (int d = 1; d <= 9; d++) {
                int rows = 0, cols = 0, cnt = 0;
                for (int a = 0; a < 3; a++)
                    for (int b = 0; b < 3; b++) {
                        int r = br + a, c = bc + b;
                        if (v[r][c] == 0 && cd[r][c][d]) {
                            rows |= 1 << r; cols |= 1 << c; cnt++;
                        }
                    }
                if (cnt >= 2 && Integer.bitCount(rows) == 1) {
                    int r0 = Integer.numberOfTrailingZeros(rows);
                    boolean act = false;
                    for (int c = 0; c < 9; c++)
                        if (c / 3 != bc / 3 && v[r0][c] == 0 && cd[r0][c][d]) {
                            cd[r0][c][d] = false; act = true;
                        }
                    if (act) return true;
                }
                if (cnt >= 2 && Integer.bitCount(cols) == 1) {
                    int c0 = Integer.numberOfTrailingZeros(cols);
                    boolean act = false;
                    for (int r = 0; r < 9; r++)
                        if (r / 3 != br / 3 && v[r][c0] == 0 && cd[r][c0][d]) {
                            cd[r][c0][d] = false; act = true;
                        }
                    if (act) return true;
                }
            }
        }
        // 行/列 -> 宫
        for (int type = 0; type < 2; type++) {
            for (int idx = 0; idx < 9; idx++) {
                for (int d = 1; d <= 9; d++) {
                    int boxes = 0, cnt = 0;
                    int[] rc = new int[2];
                    for (int i = 0; i < 9; i++) {
                        unitCell(type, idx, i, rc);
                        if (v[rc[0]][rc[1]] == 0 && cd[rc[0]][rc[1]][d]) {
                            boxes |= 1 << ((rc[0] / 3) * 3 + rc[1] / 3);
                            cnt++;
                        }
                    }
                    if (cnt < 2 || Integer.bitCount(boxes) != 1) continue;
                    int boxIdx = Integer.numberOfTrailingZeros(boxes);
                    int br = (boxIdx / 3) * 3, bc = (boxIdx % 3) * 3;
                    boolean act = false;
                    for (int a = 0; a < 3; a++)
                        for (int b = 0; b < 3; b++) {
                            int r = br + a, c = bc + b;
                            boolean onLine = (type == 0) ? r == idx : c == idx;
                            if (!onLine && v[r][c] == 0 && cd[r][c][d]) {
                                cd[r][c][d] = false; act = true;
                            }
                        }
                    if (act) return true;
                }
            }
        }
        return false;
    }

    /** 假设模型上的显性子集 k=2..4; 执行了剔除返回 true */
    private boolean trialNaked(int[][] v, boolean[][][] cd) {
        for (int k = 2; k <= 4; k++) {
            for (int type = 0; type < 3; type++) {
                for (int idx = 0; idx < 9; idx++) {
                    java.util.List<Integer> cells = new java.util.ArrayList<>();
                    int[] rc = new int[2];
                    for (int i = 0; i < 9; i++) {
                        unitCell(type, idx, i, rc);
                        if (v[rc[0]][rc[1]] == 0) {
                            for (int d = 1; d <= 9; d++)
                                if (cd[rc[0]][rc[1]][d]) { cells.add(i); break; }
                        }
                    }
                    int n = cells.size();
                    if (n <= k) continue;
                    int[] combo = new int[k];
                    for (int mask = 0; mask < (1 << n); mask++) {
                        if (Integer.bitCount(mask) != k) continue;
                        int p = 0;
                        for (int i = 0; i < n; i++)
                            if ((mask & (1 << i)) != 0) combo[p++] = i;
                        boolean[] union = new boolean[10];
                        int uc = 0;
                        for (int i : combo) {
                            unitCell(type, idx, cells.get(i), rc);
                            for (int d = 1; d <= 9; d++)
                                if (cd[rc[0]][rc[1]][d] && !union[d]) { union[d] = true; uc++; }
                        }
                        if (uc != k) continue;
                        java.util.Set<Integer> members = new java.util.HashSet<>();
                        for (int i : combo) members.add(cells.get(i));
                        boolean act = false;
                        for (int i = 0; i < 9; i++) {
                            if (members.contains(i)) continue;
                            unitCell(type, idx, i, rc);
                            if (v[rc[0]][rc[1]] != 0) continue;
                            for (int d = 1; d <= 9; d++)
                                if (union[d] && cd[rc[0]][rc[1]][d]) {
                                    cd[rc[0]][rc[1]][d] = false; act = true;
                                }
                        }
                        if (act) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 隐式唯一 (Hidden Single):
     * 某个数字 d 在某行/某列/某宫内, 只出现在唯一一个未填格的候选集中.
     * 该格即使还有别的候选, 也必须填 d.
     *
     * 候选集来自屏幕灯泡, 已排除所有已填数字, 所以只需统计候选出现次数.
     * 依据经验教训: 必须返回"唯一落点坐标", 不能只返回布尔.
     *
     * @return 每项 {row, col, digit, reason}, reason: 0=行唯一 1=列唯一 2=宫唯一;
     *         同一格按行/列/宫的自然顺序只保留第一个原因.
     */
    public java.util.List<int[]> findHiddenSingles() {
        java.util.List<int[]> result = new java.util.ArrayList<>();
        boolean[][] claimed = new boolean[9][9]; // 同格只报一次

        // 9 行
        for (int r = 0; r < 9; r++) {
            scanUnit(result, claimed, unitRow(r), 0);
        }
        // 9 列
        for (int c = 0; c < 9; c++) {
            scanUnit(result, claimed, unitCol(c), 1);
        }
        // 9 宫
        for (int br = 0; br < 3; br++) {
            for (int bc = 0; bc < 3; bc++) {
                scanUnit(result, claimed, unitBox(br, bc), 2);
            }
        }
        return result;
    }

    private void scanUnit(java.util.List<int[]> out, boolean[][] claimed, int[][] cells, int reason) {
        for (int d = 1; d <= 9; d++) {
            int onlyR = -1, onlyC = -1, cnt = 0;
            for (int[] rc : cells) {
                int r = rc[0], c = rc[1];
                if (value[r][c] == 0 && cand[r][c][d]) {
                    onlyR = r; onlyC = c; cnt++;
                }
            }
            if (cnt == 1 && !claimed[onlyR][onlyC]) {
                claimed[onlyR][onlyC] = true;
                out.add(new int[]{onlyR, onlyC, d, reason});
            }
        }
    }

    private static int[][] unitRow(int r) {
        int[][] u = new int[9][2];
        for (int c = 0; c < 9; c++) { u[c][0] = r; u[c][1] = c; }
        return u;
    }

    private static int[][] unitCol(int c) {
        int[][] u = new int[9][2];
        for (int r = 0; r < 9; r++) { u[r][0] = r; u[r][1] = c; }
        return u;
    }

    private static int[][] unitBox(int br, int bc) {
        int[][] u = new int[9][2];
        int i = 0;
        for (int dr = 0; dr < 3; dr++) {
            for (int dc = 0; dc < 3; dc++) {
                u[i][0] = br * 3 + dr;
                u[i][1] = bc * 3 + dc;
                i++;
            }
        }
        return u;
    }

    // ================= 单位自洽性安全门 =================
    //
    // 正确盘面下, 对任意单位(行/列/宫):
    //   候选并集 U = 该单位所有未填格候选数字的合集
    //   已填数字集合 FilledSet = {1..9} - U  (游戏灯泡候选已排除所有已填数字)
    // 所以必有: |FilledSet| == 该单位已填格数, 即 9 - |U| == filledCount.
    //
    // 识别错误会双向破坏等式:
    //   幻影候选(如把 6 误识成 5) -> U 变大 -> 补集变小 -> 补集大小 < 已填格数
    //   漏识候选                  -> U 变小 -> 补集变大 -> 补集大小 > 已填格数
    // 任何一个相关单位不自洽, 就拒绝填入 —— 幻影候选制造的假隐式唯一会被
    // 它所在行/列的等式失配当场拦截, 不可能再发生非法填入.
    // (本方法无需 OCR 已填格数字, 完全由候选集合结构推出)

    /** 取单位内 9 格坐标; type: 0=行(idx=r) 1=列(idx=c) 2=宫(idx=br*3+bc) */
    private void unitCell(int type, int idx, int i, int[] rc) {
        if (type == 0) { rc[0] = idx; rc[1] = i; }
        else if (type == 1) { rc[0] = i; rc[1] = idx; }
        else {
            int br = (idx / 3) * 3, bc = (idx % 3) * 3;
            rc[0] = br + i / 3;
            rc[1] = bc + i % 3;
        }
    }

    /** 单位是否自洽: 候选并集补集大小 == 已填格数 */
    public boolean isUnitConsistent(int type, int idx) {
        boolean[] inUnion = new boolean[10];
        int filled = 0;
        int[] rc = new int[2];
        for (int i = 0; i < 9; i++) {
            unitCell(type, idx, i, rc);
            int r = rc[0], c = rc[1];
            if (value[r][c] != 0) {
                filled++;
            } else {
                for (int d = 1; d <= 9; d++) if (cand[r][c][d]) inUnion[d] = true;
            }
        }
        int unionSize = 0;
        for (int d = 1; d <= 9; d++) if (inUnion[d]) unionSize++;
        return (9 - unionSize) == filled;
    }

    /**
     * 填入 (r,c)=d 是否安全: 该行/列/宫三个单位都必须自洽.
     * 不自洽说明候选识别有误(幻影或漏识), 任何链式推理都不可信, 必须停止重扫.
     */
    public boolean isFillSafe(int r, int c, int d) {
        return isUnitConsistent(0, r)
                && isUnitConsistent(1, c)
                && isUnitConsistent(2, (r / 3) * 3 + c / 3);
    }

    /** 列出所有不自洽单位, 每项 {type, idx}; type 0行/1列/2宫, idx 0-based */
    public java.util.List<int[]> findUnsafeUnits() {
        java.util.List<int[]> bad = new java.util.ArrayList<>();
        for (int type = 0; type < 3; type++) {
            for (int idx = 0; idx < 9; idx++) {
                if (!isUnitConsistent(type, idx)) bad.add(new int[]{type, idx});
            }
        }
        return bad;
    }

    /**
     * 诊断一个单位: 输出候选并集 / 缺失数字(补集) / 多余候选(理论上不该有).
     * 用于自洽校验失败时精确定位是哪个数字被漏识(并集缺)或幻影(并集多).
     * 约定: 已填格的数字不进并集; 缺失=补集里9-并集大小应等于已填格数, 不等则异常.
     */
    public String unitDiagnose(int type, int idx) {
        boolean[] inUnion = new boolean[10];
        int filled = 0;
        int[] rc = new int[2];
        // 同时记录每格候选, 方便定位漏识位置
        java.util.List<int[]> cells = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            unitCell(type, idx, i, rc);
            int r = rc[0], c = rc[1];
            if (value[r][c] != 0) {
                filled++;
            } else {
                int cnt = 0;
                for (int d = 1; d <= 9; d++) {
                    if (cand[r][c][d]) { inUnion[d] = true; cnt++; }
                }
                cells.add(new int[]{r, c, cnt});
            }
        }
        StringBuilder union = new StringBuilder();
        StringBuilder missing = new StringBuilder();
        for (int d = 1; d <= 9; d++) {
            if (inUnion[d]) union.append(d);
            else missing.append(d);
        }
        int unionSize = union.length();
        int missingSize = missing.length();
        // 等式: missingSize == filled 才自洽. 不等时:
        //   missingSize > filled: 漏识(missing 里应有 filled 个已填数字, 多出来的就是漏识候选)
        //   missingSize < filled: 幻影(并集里多了不该有的数字)
        StringBuilder cellInfo = new StringBuilder();
        for (int[] cc : cells) {
            cellInfo.append("(").append(cc[0]+1).append(",").append(cc[1]+1).append(")")
                    .append(cc[2]).append(" ");
        }
        return "并集[" + union + "] 缺[" + missing + "] "
                + "union=" + unionSize + " filled=" + filled
                + " (应 union=9-filled=" + (9-filled) + ")"
                + " | 格: " + cellInfo;
    }
}
