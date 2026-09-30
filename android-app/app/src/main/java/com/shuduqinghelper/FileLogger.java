package com.shuduqinghelper;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 文件日志: UTF-8 追加写入应用私有外部目录 (无需存储权限):
 *   Android/data/com.shuduqinghelper/files/logs/sudoku_helper.log
 *
 * 解决 Windows 上 Logcat 中文乱码和刷屏问题.
 * 通过悬浮窗【分享日志】按钮可用微信/QQ 直接发送该文件.
 * 文件超过 1MB 自动清空重开, 避免无限增长.
 */
public class FileLogger {

    private static final String TAG = "ShuDuHelper";
    private static final String DIR = "logs";
    private static final String FILE_NAME = "sudoku_helper.log";
    private static final long MAX_SIZE = 1024 * 1024; // 1MB

    private static File logFile;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

    public static synchronized void init(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            Log.e(TAG, "FileLogger: external files dir unavailable");
            return;
        }
        File dir = new File(base, DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "FileLogger: mkdirs failed " + dir);
        }
        logFile = new File(dir, FILE_NAME);
        log("====== App 启动, 日志文件: " + logFile.getAbsolutePath() + " ======");
    }

    public static synchronized void log(String msg) {
        if (logFile == null) {
            Log.i(TAG, msg);
            return;
        }
        try {
            if (logFile.length() > MAX_SIZE) {
                // 超限: 清空重开 (保留最新日志)
                new FileOutputStream(logFile, false).close();
                raw("====== 日志超过 1MB, 已清空重开 ======");
            }
            raw(TS.format(new Date()) + "  " + msg);
        } catch (Exception e) {
            Log.e(TAG, "FileLogger write failed", e);
        }
    }

    private static void raw(String line) throws Exception {
        OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
        w.write(line);
        w.write("\n");
        w.flush();
        w.close();
    }

    public static File getLogFile() {
        return logFile;
    }

    /** 清空日志文件 (悬浮窗【删除日志】按钮调用) */
    public static synchronized void clear() {
        if (logFile == null) return;
        try {
            new FileOutputStream(logFile, false).close();
            raw("====== 日志已清空 ======");
        } catch (Exception e) {
            Log.e(TAG, "FileLogger clear failed", e);
        }
    }
}
