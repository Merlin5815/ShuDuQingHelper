package com.shuduqinghelper;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 主界面: 申请权限 + 启动悬浮窗 + 后台任务调度.
 *
 * 首次使用流程:
 *   1. 点"开启无障碍" -> 系统设置开启本服务
 *   2. 点"开启悬浮窗" -> 系统设置允许悬浮窗
 *   3. 点"开启截屏" -> 授权 MediaProjection
 *   4. 点"启动悬浮窗" -> 显示辅助悬浮窗
 *   5. 在数独游戏中点悬浮窗"校准" -> 依次点网格左上/右下角
 *   6. 点"识别"查看 9x9 状态
 *   7. 点"自动填充"开始单候选双击
 */
public class MainActivity extends AppCompatActivity implements FloatingWindow.Listener {

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_SCREEN_CAPTURE = 1002;
    private static final String PREFS = "sudoku_prefs";
    private static final String KEY_GRID = "grid_config";
    private static final String KEY_PAD = "pad_config";

    private FloatingWindow floatingWindow;
    private GridConfig gridConfig;
    private PadConfig padConfig;
    private volatile boolean autoFillRunning = false;
    /** 自动填充暂停态: true 时工作线程在动作边界挂起, 不执行任何点击 */
    private volatile boolean autoFillPaused = false;
    /** 当前运行的技巧模式 (位掩码, 见 MODE_*) */
    private volatile int autoFillMode = MODE_ONEKEY;

    // 技巧模式位掩码: 一键填充=全部 + 摒除/试推; 三个单项按钮只开放各自技巧
    private static final int MODE_NAKED  = 1; // 裸单候选双击
    private static final int MODE_HIDDEN = 2; // 隐式唯一 (点格+左盘)
    private static final int MODE_SUBSET = 4; // 显性数对/三链数/四链数剔除 (点格+右盘)
    private static final int MODE_ONEKEY = MODE_NAKED | MODE_HIDDEN | MODE_SUBSET;
    private int sameCellCount = 0;  // 连续填入无效次数 (点击后目标格未变黑字)
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLogger.init(getApplicationContext());
        setContentView(R.layout.activity_main);

        Button btnAccessibility = findViewById(R.id.btn_accessibility);
        Button btnOverlay = findViewById(R.id.btn_overlay);
        Button btnCapture = findViewById(R.id.btn_capture);
        Button btnStart = findViewById(R.id.btn_start);
        TextView info = findViewById(R.id.info_text);

        btnAccessibility.setOnClickListener(v -> openAccessibilitySettings());
        btnOverlay.setOnClickListener(v -> requestOverlayPermission());
        btnCapture.setOnClickListener(v -> requestScreenCapture());
        btnStart.setOnClickListener(v -> startFloatingWindow());

        // 加载已保存的网格配置
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        gridConfig = GridConfig.deserialize(sp.getString(KEY_GRID, ""));
        padConfig = PadConfig.deserialize(sp.getString(KEY_PAD, ""));
        if (gridConfig != null && gridConfig.isValid()) {
            info.setText("已加载网格配置: " + gridConfig.serialize());
        }

        // 使用教程 (主界面静态显示, 新用户上手用)
        TextView helpText = findViewById(R.id.help_text);
        helpText.setText(
            "═══════ 数独辅助 使用教程 ═══════\n"
            + "\n【一、首次使用准备 (仅首次)】\n"
            + "1. 点\"① 开启无障碍服务\" → 系统设置里找到\"数独辅助\"并开启 (用于自动点击)\n"
            + "2. 点\"② 开启悬浮窗权限\" → 允许显示悬浮窗 (辅助面板)\n"
            + "3. 点\"③ 开启截屏权限\" → 授权屏幕录制 (用于识别棋盘)\n"
            + "4. 点\"④ 启动悬浮窗\" → 切到后台, 露出数独游戏\n"
            + "   (三项权限只需授一次, 重启App后直接点④)\n"
            + "\n【二、校准 (换新棋盘/位置时做一次)】\n"
            + "1. 点\"校准\" → 自动检测数独网格边界 (红点=格子中心)\n"
            + "2. 点\"数盘\" → 自动检测下方左填数盘+右候选盘 (红=填数 蓝=候选)\n"
            + "   校准自动保存, 下次启动无需重做; 棋盘移动了才需重校准\n"
            + "\n【三、识别盘面】\n"
            + "点\"识别\" → 截屏识别当前9×9棋盘\n"
            + "日志输出: 每格状态 / 裸单候选 / 隐式唯一 / 候选剔除建议 / 自洽校验\n"
            + "\n【四、自动填充 (4个按钮)】\n"
            + "• 一键填充: 全自动, 依次 裸单双击→隐式唯一→数对剔除→试推反证, 到填满或无解\n"
            + "• 双击单候选: 只做\"格内只剩1候选\"的双击填入\n"
            + "• 隐式唯一: 只做\"某数在某行/列/宫只此一位\"的填入 (点格+左盘)\n"
            + "• 数对剔除: 只做\"显性数对/三链数/四链数\"的候选剔除 (点格+右盘)\n"
            + "\n运行中底部出现迷你控制栏:\n"
            + "  ‖ 暂停 → 暂停后变▶继续 (只在动作边界暂停, 不打断当前点击)\n"
            + "  ■ 停止 → 立即停止自动填充\n"
            + "81格全填满会自动结束, 无需手动停止.\n"
            + "\n【五、提示 (不知道下一步时)】\n"
            + "点\"提示 (下一步)\" → 分析当前盘面, 输出下一步建议:\n"
            + "  主建议(填什么/剔除什么) + 理由(为什么) + 操作指引(怎么操作)\n"
            + "不执行任何点击, 只给思路和答案.\n"
            + "\n【六、日志与反馈】\n"
            + "• 复制日志: 状态+日志复制到剪贴板, 可粘贴发送\n"
            + "• 分享日志文件: 发送UTF-8日志文件到微信/QQ (无乱码, 含完整截图分析)\n"
            + "• 删除日志: 清空日志区, 让悬浮窗回到紧凑状态\n"
            + "\n【七、悬浮窗操作】\n"
            + "• 按住标题栏\"数独辅助\"可拖动整个悬浮窗\n"
            + "• 点标题栏\"—\"收起悬浮窗 (只留标题栏), 再点\"+\"展开\n"
            + "\n【常见问题】\n"
            + "Q: 自动填充中途停了?\n"
            + "A: 看日志的[安全停止]原因: 可能候选识别有幻影/漏识, 重新\"识别\"核对异常单位\n"
            + "\nQ: 提示说\"候选自洽校验失败\"?\n"
            + "A: 候选识别有误, 点\"识别\"看哪些行/列/宫异常, 检查棋盘是否被遮挡\n"
            + "\nQ: 按钮没反应?\n"
            + "A: 确认无障碍服务已开启 (系统设置\"数独辅助\"开关)\n"
            + "\nQ: 双盘校准只检测到左盘?\n"
            + "A: 右候选盘被遮挡或未完整显示, 调整游戏界面后重新点\"数盘\"");
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
        Toast.makeText(this, "请在列表中找到\"数独辅助\"并开启", Toast.LENGTH_LONG).show();
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
        } else {
            Toast.makeText(this, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestScreenCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_SCREEN_CAPTURE && resultCode == RESULT_OK) {
            Intent svc = ScreenCaptureService.createIntent(this, resultCode, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            Toast.makeText(this, "截屏服务已启动", Toast.LENGTH_SHORT).show();
        }
    }

    private void startFloatingWindow() {
        if (!SudokuAccessibilityService.isReady()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show();
            return;
        }
        if (ScreenCaptureService.getInstance() == null) {
            Toast.makeText(this, "请先开启截屏权限", Toast.LENGTH_LONG).show();
            return;
        }
        if (floatingWindow == null) {
            floatingWindow = new FloatingWindow(this);
            floatingWindow.setListener(this);
        }
        floatingWindow.show();
        floatingWindow.setStatus(gridConfig != null ? "网格已校准" : "请先校准网格");
        moveTaskToBack(true); // 切到后台, 露出数独游戏
    }

    // ============ FloatingWindow.Listener ============

    @Override
    public void onCalibrate() {
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc == null) {
            floatingWindow.setStatus("截屏服务未启动");
            return;
        }
        floatingWindow.setStatus("自动检测网格中, 请确保棋盘完整可见...");
        // 暂时隐藏悬浮窗自身, 避免遮挡棋盘干扰白色区域检测
        floatingWindow.setSelfVisible(false);
        new Thread(() -> {
            sleep(400); // 等悬浮窗隐藏完成
            android.graphics.Bitmap bmp = svc.getBitmap();
            floatingWindow.setSelfVisible(true);
            if (bmp == null) {
                handler.post(() -> floatingWindow.setStatus("截屏失败, 请重试"));
                return;
            }
            GridConfig cfg = GridDetector.detect(bmp);
            bmp.recycle();
            if (cfg == null || !cfg.isValid()) {
                handler.post(() -> floatingWindow.setStatus(
                        "未检测到网格, 请确保数独棋盘完整显示且无遮挡"));
                return;
            }
            gridConfig = cfg;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_GRID, cfg.serialize()).apply();
            handler.post(() -> {
                floatingWindow.setStatus("检测完成! 格边长=" + (int) cfg.cellSize
                        + ", 请确认红点对准格子中心");
                floatingWindow.appendLog("网格: " + cfg.serialize());
                floatingWindow.showGridPreview(cfg);
            });
        }).start();
    }

    @Override
    public void onCalibratePad() {
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc == null) {
            floatingWindow.setStatus("截屏服务未启动");
            return;
        }
        floatingWindow.setStatus("自动检测数字盘中, 请确保操作区完整可见...");
        floatingWindow.setSelfVisible(false);
        new Thread(() -> {
            sleep(400);
            android.graphics.Bitmap bmp = svc.getBitmap();
            floatingWindow.setSelfVisible(true);
            if (bmp == null) {
                handler.post(() -> floatingWindow.setStatus("截屏失败, 请重试"));
                return;
            }
            // 从棋盘下方开始找; 未校准网格时取屏幕 45% 以下
            int belowY = gridConfig != null ? gridConfig.gridBottom
                    : (int) (bmp.getHeight() * 0.45f);
            PadConfig cfg = PadDetector.detect(bmp, belowY);
            bmp.recycle();
            if (cfg == null || !cfg.isValid()) {
                handler.post(() -> floatingWindow.setStatus(
                        "未检测到数字盘, 请确保下方两个数字盘完整显示且无遮挡"));
                return;
            }
            padConfig = cfg;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_PAD, cfg.serialize()).apply();
            handler.post(() -> {
                floatingWindow.setStatus(cfg.hasRightPad()
                        ? "双盘检测完成: 红=填数盘 蓝=候选盘, 请确认色点对准1-9"
                        : "仅检测到左填数盘, 数对剔除需右候选盘, 请两盘完整可见后重校准");
                floatingWindow.appendLog("数字盘: " + cfg.serialize());
                floatingWindow.showPadPreview(cfg);
            });
        }).start();
    }

    @Override
    public void onScan() {
        if (gridConfig == null || !gridConfig.isValid()) {
            floatingWindow.setStatus("请先校准网格");
            return;
        }
        new Thread(this::doScan).start();
    }

    private void doScan() {
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc == null) {
            handler.post(() -> floatingWindow.setStatus("截屏服务未启动"));
            return;
        }
        // 截屏前隐藏悬浮窗: 半透明面板会让其下的棋盘像素变暗, 污染识别
        floatingWindow.setSelfVisible(false);
        sleep(300); // 等隐藏生效
        android.graphics.Bitmap bmp = svc.getBitmap();
        floatingWindow.setSelfVisible(true);
        if (bmp == null) {
            handler.post(() -> floatingWindow.setStatus("截屏失败"));
            return;
        }
        SudokuDetector.CellResult[][] board = SudokuDetector.scanBoard(bmp, gridConfig);
        bmp.recycle();

        String boardStr = SudokuDetector.boardToString(board);
        String digitsStr = SudokuDetector.digitsMatrixToString(board);
        String centroidStr = SudokuDetector.centroidsToString(board);
        String blackStr = SudokuDetector.blackMatrixToString(board);
        String tealStr = SudokuDetector.tealMatrixToString(board);
        java.util.List<int[]> singles = SudokuDetector.findSingleCandidates(board);

        // 隐式唯一 (行/列/宫) + 显性子集剔除 供用户核对
        SudokuSolver solver = new SudokuSolver();
        solver.init(board);
        java.util.List<int[]> hidden = solver.findHiddenSingles();
        // 模拟整条自动链(填数+候选剔除: 宫摒除/行列摒除/显性子集/试推矛盾),
        // 仅记录剔除动作供用户核对; 与真机执行顺序一致
        java.util.List<SudokuSolver.Elimination> subsetElims = new java.util.ArrayList<>();
        for (int guard = 0; guard < 200; guard++) {
            int[] s1 = solver.findSingle();
            if (s1 != null) { solver.commit(s1[0], s1[1], s1[2]); continue; }
            java.util.List<int[]> hs = solver.findHiddenSingles();
            if (!hs.isEmpty()) {
                int[] h = hs.get(0);
                solver.commit(h[0], h[1], h[2]);
                continue;
            }
            java.util.List<SudokuSolver.Elimination> es = solver.findLockingCandidates();
            if (es.isEmpty()) es = solver.findNakedSubsets();
            if (es.isEmpty()) es = solver.findTrialEliminations();
            if (es.isEmpty()) break;
            for (SudokuSolver.Elimination e : es) {
                subsetElims.add(e);
                solver.eliminate(e.r, e.c, e.d);
            }
        }
        // 重新建一个干净模型给自洽校验用 (上面的模拟已剔除候选)
        SudokuSolver checker = new SudokuSolver();
        checker.init(board);
        java.util.List<int[]> unsafe = checker.findUnsafeUnits();
        String[] reasonText = {"行唯一", "列唯一", "宫唯一"};

        handler.post(() -> {
            floatingWindow.clearLog();
            floatingWindow.appendLog("网格: " + gridConfig.serialize()
                    + " cellX=" + (int) gridConfig.cellSizeX
                    + " cellY=" + (int) gridConfig.cellSizeY);
            floatingWindow.appendLog(boardStr);
            floatingWindow.appendLog("候选数字串:");
            floatingWindow.appendLog(digitsStr);
            floatingWindow.appendLog("候选质心 (fx,fy)→数字:");
            floatingWindow.appendLog(centroidStr);
            floatingWindow.appendLog("黑色墨% / 青色墨%:");
            floatingWindow.appendLog(blackStr);
            floatingWindow.appendLog(tealStr);
            floatingWindow.appendLog("裸单候选(双击): " + singles.size() + " 个");
            for (int[] s : singles) {
                String d = "";
                for (int k = 1; k <= 9; k++)
                    if (board[s[0]][s[1]].digits[k]) d = String.valueOf(k);
                floatingWindow.appendLog("  (" + (s[0]+1) + "," + (s[1]+1) + ") -> " + d);
            }
            floatingWindow.appendLog("隐式唯一(点格+点盘): " + hidden.size() + " 个");
            for (int[] s : hidden) {
                floatingWindow.appendLog("  (" + (s[0]+1) + "," + (s[1]+1) + ") -> "
                        + s[2] + " [" + reasonText[s[3]] + "]");
            }
            floatingWindow.appendLog("候选剔除(宫摒除/行列摒除/数对/试推反证, 点格+右盘): "
                    + subsetElims.size() + " 步");
            for (SudokuSolver.Elimination e : subsetElims) {
                floatingWindow.appendLog("  (" + (e.r + 1) + "," + (e.c + 1)
                        + ") 去" + e.d + " " + subsetLabel(e));
            }
            if (unsafe.isEmpty()) {
                floatingWindow.appendLog("自洽校验: 27 个行/列/宫全部通过, 可安全自动填充");
            } else {
                floatingWindow.appendLog("自洽校验: " + unsafe.size()
                        + " 个单位候选异常(幻影/漏识), 自动填充会被安全门拦截:");
                for (int[] u : unsafe) {
                    String uname = u[0] == 0 ? ("行" + (u[1] + 1))
                            : u[0] == 1 ? ("列" + (u[1] + 1))
                            : ("宫(" + (u[1] / 3 + 1) + "," + (u[1] % 3 + 1) + ")");
                    floatingWindow.appendLog("  " + uname + "  " + checker.unitDiagnose(u[0], u[1]));
                }
            }
            floatingWindow.setStatus("识别完成");
        });
    }

    /**
     * 提示: 截屏识别当前盘面, 按链式优先级找出第一个可用技巧,
     * 输出"下一步该怎么走"——主建议 + 理由 + 操作指引.
     * 不执行任何点击, 仅作分析提示.
     *
     * 优先级 (与 doAutoFillLoop 一致):
     *   裸单候选(双击) > 隐式唯一(点格+左盘) > 宫/行列摒除 > 显性子集 > 试推反证
     */
    private void doHint() {
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc == null) {
            handler.post(() -> floatingWindow.setStatus("截屏服务未启动"));
            return;
        }
        // 截屏前隐藏悬浮窗, 避免半透明面板污染棋盘像素
        floatingWindow.setSelfVisible(false);
        sleep(300);
        android.graphics.Bitmap bmp = svc.getBitmap();
        floatingWindow.setSelfVisible(true);
        if (bmp == null) {
            handler.post(() -> floatingWindow.setStatus("截屏失败, 请重试"));
            return;
        }
        SudokuDetector.CellResult[][] board = SudokuDetector.scanBoard(bmp, gridConfig);
        bmp.recycle();

        int filledOnBoard = 0;
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (board[r][c].state == SudokuDetector.CellState.FILLED) filledOnBoard++;

        SudokuSolver solver = new SudokuSolver();
        solver.init(board);

        final String[] reasonText = {"行唯一", "列唯一", "宫唯一"};
        StringBuilder sb = new StringBuilder();
        sb.append("[提示] 已填 ").append(filledOnBoard).append("/81");

        // 安全门: 不自洽说明候选识别有误, 给不出可信建议
        java.util.List<int[]> badUnits = solver.findUnsafeUnits();
        if (!badUnits.isEmpty()) {
            sb.append("\n[安全门] ").append(badUnits.size())
                    .append(" 个单位候选异常(幻影/漏识), 请重新【识别】核对:");
            for (int[] u : badUnits) {
                String uname = u[0] == 0 ? ("行" + (u[1] + 1))
                        : u[0] == 1 ? ("列" + (u[1] + 1))
                        : ("宫(" + (u[1] / 3 + 1) + "," + (u[1] % 3 + 1) + ")");
                sb.append("\n  ").append(uname).append("  ").append(solver.unitDiagnose(u[0], u[1]));
            }
            final String text = sb.toString();
            handler.post(() -> {
                floatingWindow.clearLog();
                floatingWindow.appendLog(text);
                floatingWindow.setStatus("候选自洽校验失败, 无法给出提示");
            });
            return;
        }
        sb.append(", 候选自洽校验通过");

        // ---- 1. 裸单候选 (双击) ----
        int[] naked = solver.findSingle();
        if (naked != null) {
            int r = naked[0], c = naked[1], d = naked[2];
            sb.append("\n\n▸ 双击 (").append(r + 1).append(",").append(c + 1)
                    .append(") 填入 ").append(d);
            sb.append("\n  理由: 裸单候选 — 该格候选只剩 {").append(d).append("}");
            // 列出其他裸单候选 (若有)
            java.util.List<int[]> allNaked = SudokuDetector.findSingleCandidates(board);
            if (allNaked.size() > 1) {
                sb.append("\n  其他裸单候选:");
                for (int[] s : allNaked) {
                    if (s[0] == r && s[1] == c) continue;
                    String dd = "";
                    for (int k = 1; k <= 9; k++)
                        if (board[s[0]][s[1]].digits[k]) dd = String.valueOf(k);
                    sb.append("\n    (").append(s[0] + 1).append(",").append(s[1] + 1)
                            .append(") -> ").append(dd);
                }
            }
            sb.append("\n  操作: 双击该格 (游戏自动填入)");
            final String text = sb.toString();
            final int rr = r, cc = c, dd = d;
            handler.post(() -> {
                floatingWindow.clearLog();
                floatingWindow.appendLog(text);
                floatingWindow.setStatus("提示: 双击 (" + (rr + 1) + "," + (cc + 1) + ") 填 " + dd);
            });
            return;
        }

        // ---- 2. 隐式唯一 (点格+左盘) ----
        java.util.List<int[]> hidden = solver.findHiddenSingles();
        if (!hidden.isEmpty()) {
            int[] h = hidden.get(0);
            int r = h[0], c = h[1], d = h[2];
            sb.append("\n\n▸ 在 (").append(r + 1).append(",").append(c + 1)
                    .append(") 填入 ").append(d);
            sb.append("\n  理由: 隐式唯一 — ").append(reasonText[h[3]])
                    .append(" 内数字 ").append(d).append(" 只有这一个候选位");
            if (hidden.size() > 1) {
                sb.append("\n  其他隐式唯一:");
                for (int i = 1; i < hidden.size(); i++) {
                    int[] x = hidden.get(i);
                    sb.append("\n    (").append(x[0] + 1).append(",").append(x[1] + 1)
                            .append(") -> ").append(x[2]).append(" [")
                            .append(reasonText[x[3]]).append("]");
                }
            }
            sb.append("\n  操作: 单击该格 → 点左填数盘 ").append(d);
            final String text = sb.toString();
            final int rr = r, cc = c, dd = d, rt = h[3];
            handler.post(() -> {
                floatingWindow.clearLog();
                floatingWindow.appendLog(text);
                floatingWindow.setStatus("提示: (" + (rr + 1) + "," + (cc + 1) + ") 填 "
                        + dd + " [" + reasonText[rt] + "]");
            });
            return;
        }

        // ---- 3. 宫摒除/行列摒除 (点格+右盘) ----
        java.util.List<SudokuSolver.Elimination> es = solver.findLockingCandidates();
        if (!es.isEmpty()) {
            if (appendEliminationHint(sb, es)) {
                final String text = sb.toString();
                final SudokuSolver.Elimination e0 = es.get(0);
                handler.post(() -> {
                    floatingWindow.clearLog();
                    floatingWindow.appendLog(text);
                    floatingWindow.setStatus("提示: 剔除 (" + (e0.r + 1) + "," + (e0.c + 1)
                            + ") 的候选 " + e0.d);
                });
                return;
            }
        }

        // ---- 4. 显性子集 (点格+右盘) ----
        es = solver.findNakedSubsets();
        if (!es.isEmpty()) {
            if (appendEliminationHint(sb, es)) {
                final String text = sb.toString();
                final SudokuSolver.Elimination e0 = es.get(0);
                handler.post(() -> {
                    floatingWindow.clearLog();
                    floatingWindow.appendLog(text);
                    floatingWindow.setStatus("提示: 剔除 (" + (e0.r + 1) + "," + (e0.c + 1)
                            + ") 的候选 " + e0.d);
                });
                return;
            }
        }

        // ---- 5. 试推反证 (点格+右盘) ----
        es = solver.findTrialEliminations();
        if (!es.isEmpty()) {
            if (appendEliminationHint(sb, es)) {
                final String text = sb.toString();
                final SudokuSolver.Elimination e0 = es.get(0);
                handler.post(() -> {
                    floatingWindow.clearLog();
                    floatingWindow.appendLog(text);
                    floatingWindow.setStatus("提示: 剔除 (" + (e0.r + 1) + "," + (e0.c + 1)
                            + ") 的候选 " + e0.d);
                });
                return;
            }
        }

        // ---- 无可用技巧 ----
        if (filledOnBoard == 81) {
            sb.append("\n\n▸ 盘面已填满, 数独已完成!");
        } else {
            sb.append("\n\n▸ 当前盘面无可用逻辑技巧");
            sb.append("\n  可能: 需要更高级技巧(X-Wing/XY-Wing 等) 或候选识别有误");
            sb.append("\n  建议: 重新【识别】核对候选, 或用【一键填充】让试推法兜底");
        }
        final String text = sb.toString();
        int finalFilledOnBoard = filledOnBoard;
        handler.post(() -> {
            floatingWindow.clearLog();
            floatingWindow.appendLog(text);
            floatingWindow.setStatus("提示: 无可用技巧 (已填 " + finalFilledOnBoard + "/81)");
        });
    }

    /** 构造候选剔除动作的提示文本; 返回 true 表示有可用动作 */
    private boolean appendEliminationHint(StringBuilder sb,
                                           java.util.List<SudokuSolver.Elimination> es) {
        if (es.isEmpty()) return false;
        SudokuSolver.Elimination e = es.get(0);
        sb.append("\n\n▸ 剔除 (").append(e.r + 1).append(",").append(e.c + 1)
                .append(") 的候选 ").append(e.d);
        sb.append("\n  理由: ").append(subsetLabel(e));
        if (es.size() > 1) {
            sb.append("\n  同批可剔除:");
            for (int i = 1; i < es.size(); i++) {
                SudokuSolver.Elimination x = es.get(i);
                sb.append("\n    (").append(x.r + 1).append(",").append(x.c + 1)
                        .append(") 去 ").append(x.d).append("  ").append(subsetLabel(x));
            }
        }
        sb.append("\n  操作: 单击该格 → 点右候选盘 ").append(e.d);
        return true;
    }

    @Override
    public void onAutoFill() {
        startAutoFill(MODE_ONEKEY, "一键填充");
    }

    @Override
    public void onNakedFill() {
        startAutoFill(MODE_NAKED, "双击单候选");
    }

    @Override
    public void onHiddenFill() {
        startAutoFill(MODE_HIDDEN, "隐式唯一");
    }

    @Override
    public void onSubsetElim() {
        startAutoFill(MODE_SUBSET, "数对剔除");
    }

    /** 提示: 不执行任何点击, 只分析当前盘面并输出下一步的解题思路与答案 */
    @Override
    public void onHint() {
        if (autoFillRunning) {
            floatingWindow.setStatus("自动填充运行中, 请先停止再点提示");
            return;
        }
        if (gridConfig == null || !gridConfig.isValid()) {
            floatingWindow.setStatus("请先校准网格");
            return;
        }
        new Thread(this::doHint).start();
    }

    /** 启动指定技巧模式的链式自动填充; 运行中再触发 = 停止 */
    private void startAutoFill(int mode, String modeName) {
        if (autoFillRunning) {
            // 运行中再点 = 停止监控
            autoFillRunning = false;
            autoFillPaused = false;
            floatingWindow.setStatus("正在停止...");
            return;
        }
        if (gridConfig == null || !gridConfig.isValid()) {
            floatingWindow.setStatus("请先校准网格");
            return;
        }
        if (!SudokuAccessibilityService.isReady()) {
            floatingWindow.setStatus("无障碍服务未开启");
            return;
        }
        autoFillRunning = true;
        autoFillPaused = false;
        autoFillMode = mode;
        floatingWindow.setStatus(modeName + "运行中... (底部按钮可暂停/停止)");
        new Thread(this::doAutoFillLoop).start();
    }

    @Override
    public void onPauseAutoFill() {
        if (!autoFillRunning) return;
        autoFillPaused = !autoFillPaused;
        floatingWindow.setMiniPaused(autoFillPaused);
        floatingWindow.setStatus(autoFillPaused
                ? "已暂停 (点▶继续恢复)" : "继续自动填充...");
    }

    /** 暂停态下在动作边界挂起; 停止或恢复时退出. 不会打断点格+点盘的原子序列 */
    private void waitIfPaused() {
        while (autoFillRunning && autoFillPaused) sleep(200);
    }

    /** 双击后等待游戏填字动画的时间 (ms), 然后只截单格快速验证 */
    private static final long CLICK_SETTLE_MS = 350;
    /** 链条耗尽、屏幕上暂无单候选时的轮询间隔 (ms) */
    private static final long IDLE_POLL_MS = 800;
    /** 链条结束重新全屏校正前, 等游戏候选级联动画的时间 (ms) */
    private static final long RESYNC_SETTLE_MS = 500;
    /** 隐式唯一: 单击格子与点数字盘之间的间隔 (ms) */
    private static final long PAD_CLICK_GAP_MS = 150;
    /** 右盘剔除: 单击格子与点右候选盘之间的间隔 (ms), 实测 150ms 偶尔不响应, 加长 */
    private static final long RIGHT_PAD_GAP_MS = 300;
    /** 右盘剔除点击后等候选消失动画的时间 (ms), 比 CLICK_SETTLE_MS 长 */
    private static final long RIGHT_PAD_SETTLE_MS = 450;
    /** 右盘剔除最多重试次数 (含首次), 仍失败才跳出链条重扫 */
    private static final int RIGHT_PAD_MAX_ATTEMPTS = 2;
    /** 右盘剔除验证前, 点已填格取消目标格选中态后的等待时间 (ms).
     *  实测右盘操作后选中高亮+候选消失动画会让中央盒墨迹阈值把"候选减少"
     *  误判成 FILLED (假阳性导致凭空 commit 模型失步), 必须先取消选中再截图 */
    private static final long DESELECT_SETTLE_MS = 250;

    /**
     * 链式自动填充 (内存推演, 高速).
     *
     * 每一步按优先级:
     *   A. 裸单候选 (格内仅 1 个候选): 双击格子直接填入
     *   B. 隐式唯一 (数字在某行/列/宫只此一个候选位): 单击格子 -> 点左盘数字
     * 填入后本地按行/列/宫级联剔除候选, 立刻找下一步, 链条中间不全屏识别.
     * 本地无招后再全屏截一次校正 (拿游戏更新后的最新候选);
     * 仍没有就降速轮询等待, 直到用户停止.
     *
     * 安全: 每步后截该格小图验证是否真的变成已填, 连续 2 次无效立即停止.
     * 循环期间隐藏主悬浮窗, 仅显示迷你停止按钮.
     */
    private void doAutoFillLoop() {
        final int mode = autoFillMode;
        final String modeName = mode == MODE_ONEKEY ? "一键填充"
                : mode == MODE_NAKED ? "双击单候选"
                : mode == MODE_HIDDEN ? "隐式唯一" : "数对剔除";
        final boolean oneKey = mode == MODE_ONEKEY;
        SudokuAccessibilityService acc = SudokuAccessibilityService.getInstance();
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        SudokuSolver solver = new SudokuSolver();
        String[] reasonText = {"行唯一", "列唯一", "宫唯一"};
        int total = 0;
        int consecutiveFails = 0;
        int failedRounds = 0; // 单项模式: 连续"找到动作但执行失败"的轮次
        boolean completed = false; // 81 格全部已填, 自动结束
        String exhaustedMsg = null; // 单项模式无招时的结束提示

        // 隐藏主面板, 显示迷你停止按钮; 等隐藏生效后再开始截屏
        floatingWindow.setSelfVisible(false);
        floatingWindow.showMiniStopButton();
        sleep(400);

        while (autoFillRunning) {
            waitIfPaused();
            // ---- 全屏识别一帧, 重建内存模型 (每轮链条只做一次) ----
            android.graphics.Bitmap bmp = svc != null ? svc.getBitmap() : null;
            if (bmp == null) {
                consecutiveFails++;
                if (consecutiveFails > 10) {
                    handler.post(() -> floatingWindow.setStatus("连续截屏失败, 已停止"));
                    break;
                }
                sleep(500);
                continue;
            }
            consecutiveFails = 0;

            SudokuDetector.CellResult[][] board = SudokuDetector.scanBoard(bmp, gridConfig);
            bmp.recycle();
            solver.init(board);

            // ---- 填满自动结束: 81 格全部已填 (黑字+青字), 无需玩家点停止 ----
            int filledOnBoard = 0;
            for (int rr = 0; rr < 9; rr++)
                for (int cc = 0; cc < 9; cc++)
                    if (board[rr][cc].state == SudokuDetector.CellState.FILLED) filledOnBoard++;
            if (filledOnBoard == 81) {
                completed = true;
                break;
            }

            // ---- 帧级安全门: 只信任"刚全屏识别"的候选 ----
            // 链条中我们会主动剔除候选(数对), 剔除后任何单位必然不再自洽,
            // 所以自洽校验只能在这一帧做, 链条中间不做.
            java.util.List<int[]> badUnits = solver.findUnsafeUnits();
            if (!badUnits.isEmpty()) {
                // 尝试修复: 链条中游戏可能自动填入某格但不级联刷新候选,
                // 导致屏幕候选残留 (幻影), 安全门误判. 用试推法推断该格数字并修复.
                if (solver.repairAutoFilled()) {
                    handler.post(() -> floatingWindow.appendLog(
                            "[修复] 检测到游戏自动填入格幻影, 已推断数字并修复模型"));
                    badUnits = solver.findUnsafeUnits();
                }
            }
            if (!badUnits.isEmpty()) {
                java.util.List<int[]> finalBadUnits = badUnits;
                handler.post(() -> {
                    floatingWindow.setStatus("安全停止: 本帧候选自洽校验失败, 未执行任何点击");
                    floatingWindow.appendLog("[安全停止] 候选识别存在幻影/漏识, 请重新【识别】核对异常单位:");
                    for (int[] u : finalBadUnits) {
                    String uname = u[0] == 0 ? ("行" + (u[1] + 1))
                            : u[0] == 1 ? ("列" + (u[1] + 1))
                            : ("宫(" + (u[1] / 3 + 1) + "," + (u[1] % 3 + 1) + ")");
                    floatingWindow.appendLog("  " + uname + "  " + solver.unitDiagnose(u[0], u[1]));
                }
                });
                break;
            }

            // ---- 链式推演: 纯内存, 中间不全屏识别 ----
            // 优先级: 裸单候选(双击) > 隐式唯一(点格+左盘) > 显性子集剔除(点格+右盘)
            int fillCount = 0, elimCount = 0;
            boolean aborted = false;
            boolean moveFound = false; // 本轮链条是否找到过可用技巧 (区别于执行失败)
            int needPad = 0; // 0=无 1=缺左盘(隐式唯一) 2=缺右盘(候选剔除)
            final java.util.List<String> pendingLines = new java.util.ArrayList<>();
            while (autoFillRunning) {
                waitIfPaused();
                int[] naked = (mode & MODE_NAKED) != 0 ? solver.findSingle() : null;

                if (naked != null) {
                    // ===== A. 裸单候选: 双击 =====
                    moveFound = true;
                    int r = naked[0], c = naked[1], d = naked[2];
                    android.graphics.Point p = gridConfig.cells[r][c];
                    final int row = r + 1, col = c + 1, digit = d, n = total + fillCount + 1;
                    handler.post(() -> floatingWindow.appendLog(
                            "[填入#" + n + "] (" + row + "," + col + ") -> " + digit + " [裸候选]"));
                    acc.doubleClick(p.x, p.y);
                    sleep(CLICK_SETTLE_MS);

                    if (!verifyCellFilled(svc, p)) {
                        sameCellCount++;
                        handler.post(() -> floatingWindow.appendLog(
                                "[警告] (" + row + "," + col + ") 双击后未变成已填, 疑似误判"));
                        if (sameCellCount >= 2) {
                            aborted = true;
                            handler.post(() -> floatingWindow.setStatus(
                                    "连续 2 次填入无效, 已停止. 请发日志排查"));
                        }
                        break;
                    }
                    sameCellCount = 0;
                    solver.commit(r, c, d);
                    fillCount++;
                    continue;
                }

                java.util.List<int[]> hs = (mode & MODE_HIDDEN) != 0
                        ? solver.findHiddenSingles()
                        : java.util.Collections.emptyList();
                if (!hs.isEmpty()) {
                    // ===== B. 隐式唯一: 点格 + 左盘数字 =====
                    moveFound = true;
                    if (padConfig == null || !padConfig.isValid()) {
                        needPad = 1;
                        pendingLines.add("[暂停] 下一步是隐式唯一(点格+左盘), 请点【数盘】校准后重新开始:");
                        for (int[] s : hs) {
                            pendingLines.add("  (" + (s[0] + 1) + "," + (s[1] + 1)
                                    + ") -> " + s[2] + " [" + reasonText[s[3]] + "]");
                        }
                        break;
                    }
                    int[] act = hs.get(0);
                    int r = act[0], c = act[1], d = act[2];
                    android.graphics.Point p = gridConfig.cells[r][c];
                    final int row = r + 1, col = c + 1, digit = d, n = total + fillCount + 1;
                    final String why = reasonText[act[3]];
                    handler.post(() -> floatingWindow.appendLog(
                            "[填入#" + n + "] (" + row + "," + col + ") -> " + digit + " [" + why + "]"));
                    acc.click(p.x, p.y);
                    sleep(PAD_CLICK_GAP_MS);
                    android.graphics.Point kp = padConfig.keys[d];
                    acc.click(kp.x, kp.y);
                    sleep(CLICK_SETTLE_MS);

                    if (!verifyCellFilled(svc, p)) {
                        sameCellCount++;
                        handler.post(() -> floatingWindow.appendLog(
                                "[警告] (" + row + "," + col + ") 点格+点盘后未变成已填, 疑似误判"));
                        if (sameCellCount >= 2) {
                            aborted = true;
                            handler.post(() -> floatingWindow.setStatus(
                                    "连续 2 次填入无效, 已停止. 请发日志排查"));
                        }
                        break;
                    }
                    sameCellCount = 0;
                    solver.commit(r, c, d);
                    fillCount++;
                    continue;
                }

                // C 候选剔除 (点格+右盘):
                // 一键模式按 宫/行列摒除 → 显性子集 → 试推反证 完整优先级;
                // 数对剔除单项模式严格只做显性数对/三链数/四链数.
                java.util.List<SudokuSolver.Elimination> es = new java.util.ArrayList<>();
                if ((mode & MODE_SUBSET) != 0) {
                    if (oneKey) es = solver.findLockingCandidates();
                    if (es.isEmpty()) es = solver.findNakedSubsets();
                    if (es.isEmpty() && oneKey) es = solver.findTrialEliminations();
                }
                if (!es.isEmpty()) {
                    moveFound = true;
                    SudokuSolver.Elimination e = es.get(0);
                    int r = e.r, c = e.c, d = e.d;
                    android.graphics.Point p = gridConfig.cells[r][c];
                    final int row = r + 1, col = c + 1;
                    final String label = subsetLabel(e);

                    // ===== C0. 剔除倒逼唯余: 改用左盘直接填入 =====
                    // 游戏会把只剩单候选的格自动填入, 但不级联刷新其他格候选,
                    // 屏幕候选变脏, 下一帧安全门会反复拦截. 若本剔除会把该格
                    // 逼成单候选, 改用左盘填入那个数: 游戏正常级联, 效果等价.
                    int forced = solver.soleOtherCandidate(r, c, d);
                    if (forced != 0) {
                        if (padConfig == null || !padConfig.isValid()) {
                            needPad = 1;
                            pendingLines.add("[暂停] (" + row + "," + col + ") 剔除" + d
                                    + "后只剩" + forced + ", 需改用左盘填入; 请点【数盘】校准后重新开始");
                            break;
                        }
                        final int fd = forced, nf = total + fillCount + 1;
                        handler.post(() -> floatingWindow.appendLog(
                                "[填入#" + nf + "] (" + row + "," + col + ") -> " + fd
                                        + " [剔除倒逼: 去" + d + "后唯余] " + label));
                        acc.click(p.x, p.y);
                        sleep(PAD_CLICK_GAP_MS);
                        android.graphics.Point kp = padConfig.keys[fd];
                        acc.click(kp.x, kp.y);
                        sleep(CLICK_SETTLE_MS);

                        if (!verifyCellFilled(svc, p)) {
                            sameCellCount++;
                            handler.post(() -> floatingWindow.appendLog(
                                    "[警告] (" + row + "," + col + ") 点格+左盘后未变成已填, 疑似误判"));
                            if (sameCellCount >= 2) {
                                aborted = true;
                                handler.post(() -> floatingWindow.setStatus(
                                        "连续 2 次填入无效, 已停止. 请发日志排查"));
                            }
                            break;
                        }
                        sameCellCount = 0;
                        solver.commit(r, c, fd);
                        fillCount++;
                        continue;
                    }

                    // ===== C. 候选剔除: 点格 + 右盘数字 =====
                    if (padConfig == null || !padConfig.hasRightPad()) {
                        needPad = 2;
                        pendingLines.add("[暂停] 下一步需要候选剔除(点格+右盘), 请重新点【数盘】校准(需同时框住左右两盘):");
                        for (SudokuSolver.Elimination e2 : es) {
                            pendingLines.add("  (" + (e2.r + 1) + "," + (e2.c + 1)
                                    + ") 去" + e2.d + " " + subsetLabel(e2));
                        }
                        break;
                    }
                    final int digit = d, n = elimCount + 1;
                    handler.post(() -> floatingWindow.appendLog(
                            "[剔除#" + n + "] (" + row + "," + col + ") 去掉候选 "
                                    + digit + "  " + label));
                    android.graphics.Point rp = padConfig.rightKeys[d];
                    FileLogger.log("[剔除坐标] 格(" + row + "," + col + ") 点击("
                            + p.x + "," + p.y + ") 右盘" + digit + "("
                            + rp.x + "," + rp.y + ")");

                    // 重试循环: 实测右盘点击偶尔不响应 (选中态丢失/动画未完成),
                    // 第一次失败后重新点格+点右盘再验证, 仍失败才跳出链条重扫
                    boolean gone = false;
                    boolean becameFilled = false;
                    // 找一个已填格用于转移选中焦点 (点已填格仅高亮, 不改盘面)
                    android.graphics.Point defocus = null;
                    for (int rr2 = 0; rr2 < 9 && defocus == null; rr2++) {
                        for (int cc2 = 0; cc2 < 9; cc2++) {
                            if ((rr2 != r || cc2 != c) && solver.value[rr2][cc2] != 0) {
                                defocus = gridConfig.cells[rr2][cc2];
                                break;
                            }
                        }
                    }
                    for (int attempt = 1; attempt <= RIGHT_PAD_MAX_ATTEMPTS; attempt++) {
                        acc.click(p.x, p.y);
                        sleep(RIGHT_PAD_GAP_MS);
                        acc.click(rp.x, rp.y);
                        sleep(RIGHT_PAD_SETTLE_MS);

                        // 取消目标格选中态再验证: 右盘操作后选中高亮+候选消失动画
                        // 会让中央盒墨迹阈值把"候选减少"误判成 FILLED (实测假阳性),
                        // 点一个已填格把焦点移走即可消除; 真被自动填入时大字不受影响.
                        if (defocus != null) {
                            acc.click(defocus.x, defocus.y);
                            sleep(DESELECT_SETTLE_MS);
                        }

                        // 验证: 该格仍未填且候选 d 已消失
                        // 另追踪 becameFilled: 游戏自身逻辑把该格自动填入 (变 FILLED)
                        android.graphics.Bitmap vbmp = svc.getBitmap();
                        if (vbmp != null) {
                            SudokuDetector.CellResult after = SudokuDetector
                                    .analyzeSingleCell(vbmp, p.x, p.y, gridConfig.cellSize);
                            becameFilled = after.state == SudokuDetector.CellState.FILLED;
                            gone = !becameFilled && !after.digits[d];
                            vbmp.recycle();
                        }
                        // FILLED 双帧确认: 再等一拍重截, 排除动画中间帧假阳性
                        if (becameFilled) {
                            sleep(DESELECT_SETTLE_MS);
                            android.graphics.Bitmap vb2 = svc.getBitmap();
                            if (vb2 != null) {
                                SudokuDetector.CellResult a2 = SudokuDetector
                                        .analyzeSingleCell(vb2, p.x, p.y, gridConfig.cellSize);
                                if (a2.state != SudokuDetector.CellState.FILLED) {
                                    becameFilled = false;
                                    gone = !a2.digits[d];
                                }
                                vb2.recycle();
                            }
                        }
                        if (gone || becameFilled) break;
                        if (attempt < RIGHT_PAD_MAX_ATTEMPTS) {
                            final int att = attempt;
                            handler.post(() -> floatingWindow.appendLog(
                                    "[重试] (" + row + "," + col + ") 候选 " + digit
                                            + " 第" + att + "次未消失, 重新点格+右盘"));
                        }
                    }
                    if (becameFilled) {
                        // 游戏自动填入该格: 用试推法确定填的是哪个数字, 再正常级联提交.
                        // 不走 eliminate (只改本格候选), 而走 commit (级联剔同行/列/宫),
                        // 这样 Solver 模型与游戏真实状态一致, 下次全屏校正不会幻影.
                        boolean[] oldCand = new boolean[10];
                        for (int k = 1; k <= 9; k++) oldCand[k] = solver.cand[r][c][k];
                        int inferred = solver.inferFilledDigit(r, c, oldCand);
                        if (inferred != 0) {
                            final int fd = inferred, nf = total + fillCount + 1;
                            handler.post(() -> floatingWindow.appendLog(
                                    "[填入#" + nf + "] (" + row + "," + col + ") -> " + fd
                                            + " [游戏自动填入, 试推确认] " + label));
                            solver.commit(r, c, inferred);
                            fillCount++;
                            continue;
                        }
                        // 推断失败: 跳出链条重扫
                        handler.post(() -> floatingWindow.appendLog(
                                "[警告] (" + row + "," + col + ") 被游戏自动填入"
                                        + ", 试推无法确定数字, 重新全屏校正"));
                        break;
                    }
                    if (!gone) {
                        // 剔除失败不致命: 跳出链条, 全屏重扫以屏幕真实状态重建模型
                        handler.post(() -> floatingWindow.appendLog(
                                "[警告] (" + row + "," + col + ") 候选 " + digit
                                        + " 经" + RIGHT_PAD_MAX_ATTEMPTS
                                        + "次重试仍未消失, 重新全屏校正"));
                        break;
                    }
                    solver.eliminate(r, c, d);
                    elimCount++;
                    continue;
                }

                break; // 三种技巧都无招, 链条结束
            }

            total += fillCount;
            if (aborted || needPad != 0) {
                if (needPad != 0) {
                    final int np = needPad;
                    final java.util.List<String> pl = new java.util.ArrayList<>(pendingLines);
                    handler.post(() -> {
                        floatingWindow.setStatus(np == 1
                                ? "已暂停: 需要左填数盘" : "已暂停: 需要右候选盘");
                        for (String line : pl) floatingWindow.appendLog(line);
                    });
                }
                break;
            }

            // ---- 链条结束 ----
            if (fillCount > 0 || elimCount > 0) {
                // 本轮有动作: 等游戏动画, 回外层全屏校正后继续下一条链
                failedRounds = 0;
                final int t = total, f = fillCount, e2 = elimCount;
                handler.post(() -> floatingWindow.setStatus(
                        "链条: 填入 " + f + " / 剔除 " + e2
                                + ", 重新校正中... (累计填入 " + t + ")"));
                sleep(RESYNC_SETTLE_MS);
            } else if (oneKey) {
                // 一键模式: 暂无招也不退出, 降速轮询 (玩家可能再点灯泡/手动改候选)
                final int t = total;
                handler.post(() -> floatingWindow.setStatus(
                        "监控中... 暂无可用技巧 (已填 " + t + " 个)"));
                sleep(IDLE_POLL_MS);
            } else if (!moveFound) {
                // 单项模式: 全屏重扫后该技巧确实无可用动作 → 自动结束
                exhaustedMsg = modeName + "暂无可用动作, 已结束 (本次填入 " + total + " 个)";
                break;
            } else if (++failedRounds >= 2) {
                // 找到动作但连续 2 轮执行失败 (右盘不响应/双击未变已填), 停止
                exhaustedMsg = modeName + "动作连续执行失败, 已停止 (本次填入 " + total + " 个)";
                break;
            } else {
                // 找到动作但本轮执行失败: 全屏重扫再给一次机会
                handler.post(() -> floatingWindow.setStatus(
                        modeName + ": 动作未生效, 重新校正后重试..."));
                sleep(RESYNC_SETTLE_MS);
            }
        }

        autoFillRunning = false;
        autoFillPaused = false;
        final int t = total;
        final boolean done = completed;
        final String exMsg = exhaustedMsg;
        // 恢复主面板, 移除迷你控制栏
        floatingWindow.setMiniPaused(false);
        floatingWindow.hideMiniStopButton();
        floatingWindow.setSelfVisible(true);
        handler.post(() -> {
            if (done) {
                floatingWindow.setStatus("已完成: 全盘 81 格填满, 自动结束");
                floatingWindow.appendLog("[完成] 全盘填满, 自动结束 (本次共填入 " + t + " 个)");
            } else if (exMsg != null) {
                floatingWindow.setStatus(exMsg);
                floatingWindow.appendLog("[结束] " + exMsg);
            } else {
                floatingWindow.setStatus("已停止");
                floatingWindow.appendLog("[结束] 共填入 " + t + " 个");
            }
        });
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 截该格小图验证是否已变成已填状态 (黑/青大字) */
    private boolean verifyCellFilled(ScreenCaptureService svc, android.graphics.Point p) {
        android.graphics.Bitmap vbmp = svc.getBitmap();
        if (vbmp == null) return false;
        SudokuDetector.CellResult after = SudokuDetector
                .analyzeSingleCell(vbmp, p.x, p.y, gridConfig.cellSize);
        vbmp.recycle();
        return after.state == SudokuDetector.CellState.FILLED;
    }

    private static final String[] SUBSET_NAMES = {"", "", "数对", "三链数", "四链数"};

    /** 剔除动作的人类可读来源标签 */
    private static String subsetLabel(SudokuSolver.Elimination e) {
        if (e.tech == 3) {
            return "[试推反证: 假设(" + (e.r + 1) + "," + (e.c + 1) + ")填"
                    + e.d + "会导致全盘矛盾]";
        }
        if (e.tech == 1) {
            // 宫摒除: 宫内 d 锁定在某行/列
            String box = "宫(" + (e.unitIdx / 3 + 1) + "," + (e.unitIdx % 3 + 1) + ")";
            String line = e.lockType == 0
                    ? "第" + (e.lockIdx + 1) + "行" : "第" + (e.lockIdx + 1) + "列";
            return "[宫摒除 " + box + "的" + e.d + "锁定" + line + "]";
        }
        if (e.tech == 2) {
            // 行列摒除: 行/列的 d 全在某宫
            String src = e.unitType == 0
                    ? "第" + (e.unitIdx + 1) + "行" : "第" + (e.unitIdx + 1) + "列";
            String box = "宫(" + (e.lockIdx / 3 + 1) + "," + (e.lockIdx % 3 + 1) + ")";
            return "[行列摒除 " + src + "的" + e.d + "全在" + box + "]";
        }
        String unit;
        if (e.unitType == 0) unit = "第" + (e.unitIdx + 1) + "行";
        else if (e.unitType == 1) unit = "第" + (e.unitIdx + 1) + "列";
        else unit = "宫(" + (e.unitIdx / 3 + 1) + "," + (e.unitIdx % 3 + 1) + ")";
        StringBuilder ds = new StringBuilder();
        for (int x : e.subsetDigits) ds.append(x);
        return "[" + unit + " " + SUBSET_NAMES[e.subsetSize] + "{" + ds + "}]";
    }

    @Override
    public void onHighlightFill(int number) {
        // 宫内唯一填充: 高亮检测 + 按宫分组 + 唯一格填入
        // TODO: v3 实现, 依赖高亮颜色检测
        floatingWindow.setStatus("宫内唯一填充功能待实现 (v3)");
    }

    @Override
    public void onExit() {
        autoFillRunning = false;
        autoFillPaused = false;
        if (floatingWindow != null) floatingWindow.hide();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (floatingWindow != null) floatingWindow.hide();
    }
}
