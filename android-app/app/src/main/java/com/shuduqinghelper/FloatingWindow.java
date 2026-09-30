package com.shuduqinghelper;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 悬浮窗: 校准 / 截屏识别 / 自动填充 / 退出.
 * 可拖动(标题栏) / 可收起(折叠按钮).
 *
 * 校准采用"一张常驻全屏透明层吃两次点击"的方式:
 * 第一次点击记录左上角并显示红点标记, 第二次点击完成并回调.
 * (不要每次点击都移除再重建窗口 —— 部分机型会在触摸分发过程中丢失新窗口的事件)
 */
public class FloatingWindow {

    /** Logcat 统一 TAG, Android Studio Logcat 过滤 "ShuDuHelper" 即可看全部日志 */
    public static final String LOG_TAG = "ShuDuHelper";

    public interface Listener {
        void onCalibrate();
        void onCalibratePad();
        void onScan();
        void onAutoFill();
        void onNakedFill();
        void onHiddenFill();
        void onSubsetElim();
        void onHint();
        void onPauseAutoFill();
        void onHighlightFill(int number);
        void onExit();
    }

    private final Context context;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View rootView;
    private WindowManager.LayoutParams params;
    private View panelBody;
    private TextView statusText;
    private TextView logText;
    private ScrollView logScroll;
    private boolean collapsed = false;
    private Listener listener;

    // 网格预览层
    private FrameLayout calibrationOverlay;

    public FloatingWindow(Context context) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void show() {
        if (rootView != null) return;

        rootView = LayoutInflater.from(context).inflate(R.layout.floating_window, null);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;

        statusText = rootView.findViewById(R.id.status_text);
        logText = rootView.findViewById(R.id.log_text);
        logScroll = rootView.findViewById(R.id.log_scroll);
        panelBody = rootView.findViewById(R.id.panel_body);

        Button btnCalib = rootView.findViewById(R.id.btn_calibrate);
        Button btnCalibPad = rootView.findViewById(R.id.btn_calibrate_pad);
        Button btnScan = rootView.findViewById(R.id.btn_scan);
        Button btnAuto = rootView.findViewById(R.id.btn_auto_fill);
        Button btnNaked = rootView.findViewById(R.id.btn_naked_fill);
        Button btnHidden = rootView.findViewById(R.id.btn_hidden_fill);
        Button btnSubset = rootView.findViewById(R.id.btn_subset_elim);
        Button btnHint = rootView.findViewById(R.id.btn_hint);
        Button btnExit = rootView.findViewById(R.id.btn_exit);
        Button btnCollapse = rootView.findViewById(R.id.btn_collapse);

        btnCalib.setOnClickListener(v -> { if (listener != null) listener.onCalibrate(); });
        btnCalibPad.setOnClickListener(v -> { if (listener != null) listener.onCalibratePad(); });
        btnScan.setOnClickListener(v -> { if (listener != null) listener.onScan(); });
        btnAuto.setOnClickListener(v -> { if (listener != null) listener.onAutoFill(); });
        btnNaked.setOnClickListener(v -> { if (listener != null) listener.onNakedFill(); });
        btnHidden.setOnClickListener(v -> { if (listener != null) listener.onHiddenFill(); });
        btnSubset.setOnClickListener(v -> { if (listener != null) listener.onSubsetElim(); });
        btnHint.setOnClickListener(v -> { if (listener != null) listener.onHint(); });
        btnExit.setOnClickListener(v -> { if (listener != null) listener.onExit(); });

        // 复制日志到剪贴板, 方便粘贴发送
        Button btnCopy = rootView.findViewById(R.id.btn_copy_log);
        btnCopy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            String text = statusText.getText() + "\n" + logText.getText().toString();
            cm.setPrimaryClip(android.content.ClipData.newPlainText("sudoku_log", text));
            setStatus("日志已复制, 粘贴到微信发送即可");
        });

        // 分享日志文件 (UTF-8, 无乱码), 可直接发微信/QQ
        Button btnShare = rootView.findViewById(R.id.btn_share_log);
        btnShare.setOnClickListener(v -> shareLogFile());

        // 删除日志: 清空文件 + 屏幕日志区, 让悬浮窗回到紧凑状态
        Button btnDelete = rootView.findViewById(R.id.btn_delete_log);
        btnDelete.setOnClickListener(v -> {
            FileLogger.clear();
            if (logText != null) logText.setText("");
            if (logScroll != null) logScroll.setVisibility(View.GONE);
            setStatus("日志已删除");
        });

        // 收起 / 展开: 只留标题栏
        btnCollapse.setOnClickListener(v -> {
            collapsed = !collapsed;
            panelBody.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            btnCollapse.setText(collapsed ? "+" : "—");
        });

        // 按住标题栏拖动
        View title = rootView.findViewById(R.id.title_bar);
        title.setOnTouchListener(new View.OnTouchListener() {
            int initialX, initialY;
            float initialTouchX, initialTouchY;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        wm.updateViewLayout(rootView, params);
                        return true;
                }
                return false;
            }
        });

        wm.addView(rootView, params);
    }

    public void hide() {
        if (rootView != null) {
            wm.removeView(rootView);
            rootView = null;
        }
        removeCalibrationOverlay();
    }

    public void setStatus(String msg) {
        if (statusText != null) statusText.setText(msg);
        FileLogger.log("[状态] " + msg);
    }

    public void appendLog(String msg) {
        FileLogger.log(msg);
        if (logText != null) {
            // 首次写入日志时展开 ScrollView (之前为 GONE 让悬浮窗保持紧凑)
            if (logScroll != null && logScroll.getVisibility() != View.VISIBLE) {
                logScroll.setVisibility(View.VISIBLE);
            }
            logText.append(msg + "\n");
            if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    public void clearLog() {
        FileLogger.log("====== 新一次识别 ======");
        if (logText != null) logText.setText("");
        // 清屏后日志区为空, 收起 ScrollView 保持悬浮窗紧凑
        if (logScroll != null) logScroll.setVisibility(View.GONE);
    }

    /** 通过系统分享面板发送日志文件 (微信/QQ/邮件等), UTF-8 无乱码 */
    private void shareLogFile() {
        java.io.File f = FileLogger.getLogFile();
        if (f == null || !f.exists()) {
            setStatus("日志文件不存在");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", f);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(Intent.createChooser(share, "发送数独辅助日志")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.e(LOG_TAG, "share log failed", e);
            setStatus("分享失败: " + e.getMessage());
        }
    }

    /**
     * 显示网格检测预览: 外框 + 每个格子中心的红点 (红点才是实际采样位置).
     * 2.5 秒后自动消失. 期间吞掉触摸, 防止误点游戏.
     */
    public void showGridPreview(final GridConfig cfg) {
        ui.post(() -> {
            removeCalibrationOverlay();
            // 隐藏悬浮窗自身, 让预览无遮挡
            setSelfVisible(false);

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);

            calibrationOverlay = new FrameLayout(context);
            // 沉浸式布局, 让覆盖层原点与物理屏原点一致 (避免状态栏造成的显示偏移)
            calibrationOverlay.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            // 顶部提示
            TextView hint = new TextView(context);
            hint.setText("红点应对准每个格子的中心, 没对准请重新点【校准】");
            hint.setTextColor(Color.WHITE);
            hint.setTextSize(15);
            hint.setBackgroundColor(0xAA000000);
            hint.setPadding(30, 14, 30, 14);
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            hp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            hp.topMargin = 120;
            calibrationOverlay.addView(hint, hp);

            // 画外框 + 格子中心红点
            View preview = new View(context) {
                @Override
                protected void onDraw(android.graphics.Canvas canvas) {
                    // 关键: 补偿窗口原点与物理屏原点的偏移.
                    // 部分 ROM 的覆盖层原点从状态栏下方开始, 直接按物理坐标画会整体偏下.
                    int[] loc = new int[2];
                    getLocationOnScreen(loc);
                    canvas.save();
                    canvas.translate(-loc[0], -loc[1]);

                    android.graphics.Paint stroke = new android.graphics.Paint();
                    stroke.setColor(0xFFFF4444);
                    stroke.setStrokeWidth(4);
                    stroke.setStyle(android.graphics.Paint.Style.STROKE);
                    canvas.drawRect(cfg.gridLeft, cfg.gridTop, cfg.gridRight, cfg.gridBottom, stroke);

                    android.graphics.Paint fill = new android.graphics.Paint();
                    fill.setColor(0xFFFF4444);
                    float radius = Math.max(6, cfg.cellSize * 0.06f);
                    for (int r = 0; r < 9; r++) {
                        for (int c = 0; c < 9; c++) {
                            android.graphics.Point p = cfg.cells[r][c];
                            canvas.drawCircle(p.x, p.y, radius, fill);
                        }
                    }
                    canvas.restore();
                }
            };
            calibrationOverlay.addView(preview, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            // 预览期间吞掉触摸, 防止误点游戏
            calibrationOverlay.setOnTouchListener((v, e) -> true);

            wm.addView(calibrationOverlay, lp);
            ui.postDelayed(() -> {
                removeCalibrationOverlay();
                setSelfVisible(true);
            }, 2500);
        });
    }

    /**
     * 数字盘检测预览: 框出左侧填数盘 + 9 个数字键红点及数字标注.
     * 2.5 秒后自动消失.
     */
    public void showPadPreview(final PadConfig cfg) {
        ui.post(() -> {
            removeCalibrationOverlay();
            setSelfVisible(false);

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);

            calibrationOverlay = new FrameLayout(context);
            calibrationOverlay.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            TextView hint = new TextView(context);
            hint.setText(cfg.hasRightPad()
                    ? "红框=左填数盘, 蓝框=右候选盘, 色点应对准1-9中心, 不对请重点【数盘】"
                    : "只找到左【填数盘】(红框)! 数对剔除还需右候选盘, 请让两个盘都完整可见后重点【数盘】");
            hint.setTextColor(Color.WHITE);
            hint.setTextSize(14);
            hint.setBackgroundColor(0xAA000000);
            hint.setPadding(30, 14, 30, 14);
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            hp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            hp.topMargin = 120;
            calibrationOverlay.addView(hint, hp);

            View preview = new View(context) {
                @Override
                protected void onDraw(android.graphics.Canvas canvas) {
                    int[] loc = new int[2];
                    getLocationOnScreen(loc);
                    canvas.save();
                    canvas.translate(-loc[0], -loc[1]);

                    android.graphics.Paint stroke = new android.graphics.Paint();
                    stroke.setColor(0xFFFF4444);
                    stroke.setStrokeWidth(4);
                    stroke.setStyle(android.graphics.Paint.Style.STROKE);
                    canvas.drawRect(cfg.padLeft, cfg.padTop, cfg.padRight, cfg.padBottom, stroke);

                    android.graphics.Paint fill = new android.graphics.Paint();
                    fill.setColor(0xFFFF4444);
                    float radius = Math.max(8, (cfg.padRight - cfg.padLeft) * 0.03f);
                    for (int d = 1; d <= 9; d++) {
                        Point p = cfg.keys[d];
                        canvas.drawCircle(p.x, p.y, radius, fill);
                    }

                    android.graphics.Paint txt = new android.graphics.Paint();
                    txt.setColor(0xFFFFFF00);
                    txt.setTextSize(radius * 2.2f);
                    txt.setTextAlign(android.graphics.Paint.Align.CENTER);
                    for (int d = 1; d <= 9; d++) {
                        Point p = cfg.keys[d];
                        canvas.drawText(String.valueOf(d), p.x,
                                p.y - radius - 6, txt);
                    }

                    // 右侧候选修改盘: 蓝框 + 蓝点
                    if (cfg.hasRightPad()) {
                        android.graphics.Paint strokeR = new android.graphics.Paint();
                        strokeR.setColor(0xFF4488FF);
                        strokeR.setStrokeWidth(4);
                        strokeR.setStyle(android.graphics.Paint.Style.STROKE);
                        canvas.drawRect(cfg.rightLeft, cfg.rightTop,
                                cfg.rightRight, cfg.rightBottom, strokeR);
                        android.graphics.Paint fillR = new android.graphics.Paint();
                        fillR.setColor(0xFF4488FF);
                        for (int d = 1; d <= 9; d++) {
                            Point p = cfg.rightKeys[d];
                            canvas.drawCircle(p.x, p.y, radius, fillR);
                        }
                    }
                    canvas.restore();
                }
            };
            calibrationOverlay.addView(preview, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            calibrationOverlay.setOnTouchListener((v, e) -> true);

            wm.addView(calibrationOverlay, lp);
            ui.postDelayed(() -> {
                removeCalibrationOverlay();
                setSelfVisible(true);
            }, 2800);
        });
    }

    /** 截屏前临时隐藏悬浮窗自身, 避免遮挡棋盘干扰检测 */
    public void setSelfVisible(boolean visible) {
        ui.post(() -> {
            if (rootView != null) {
                rootView.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        });
    }

    // 自动填充期间的迷你控制栏 (放在底部空白处, 不遮挡棋盘):
    // 左=暂停/继续 (橙), 右=停止 (红)
    private LinearLayout miniBar;
    private TextView miniPauseBtn;

    /** 显示迷你控制栏 (自动填充期间主面板隐藏, 用它暂停/停止) */
    public void showMiniStopButton() {
        ui.post(() -> {
            if (miniBar != null) return;
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = 350; // 底部工具栏下方的空白区域

            miniBar = new LinearLayout(context);
            miniBar.setOrientation(LinearLayout.HORIZONTAL);

            miniPauseBtn = new TextView(context);
            miniPauseBtn.setText("‖ 暂停");
            miniPauseBtn.setTextColor(Color.WHITE);
            miniPauseBtn.setBackgroundColor(0xCCFF9933);
            miniPauseBtn.setPadding(40, 20, 40, 20);
            miniPauseBtn.setTextSize(14);
            miniPauseBtn.setOnClickListener(v -> {
                if (listener != null) listener.onPauseAutoFill();
            });

            TextView stopBtn = new TextView(context);
            stopBtn.setText("■ 停止");
            stopBtn.setTextColor(Color.WHITE);
            stopBtn.setBackgroundColor(0xCCFF4444);
            stopBtn.setPadding(40, 20, 40, 20);
            stopBtn.setTextSize(14);
            stopBtn.setOnClickListener(v -> {
                if (listener != null) listener.onAutoFill();
            });

            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.setMarginEnd(24);
            miniBar.addView(miniPauseBtn, plp);
            miniBar.addView(stopBtn);
            wm.addView(miniBar, lp);
        });
    }

    /** 切换迷你暂停按钮文案: 暂停态显示"继续", 运行态显示"暂停" */
    public void setMiniPaused(boolean paused) {
        ui.post(() -> {
            if (miniPauseBtn != null) {
                miniPauseBtn.setText(paused ? "▶ 继续" : "‖ 暂停");
            }
        });
    }

    public void hideMiniStopButton() {
        ui.post(() -> {
            if (miniBar != null) {
                wm.removeView(miniBar);
                miniBar = null;
                miniPauseBtn = null;
            }
        });
    }

    public void removeCalibrationOverlay() {
        if (calibrationOverlay != null) {
            wm.removeView(calibrationOverlay);
            calibrationOverlay = null;
        }
    }
}
