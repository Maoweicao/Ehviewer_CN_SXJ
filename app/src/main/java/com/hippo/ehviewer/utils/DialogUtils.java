package com.hippo.ehviewer.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;

import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;

/**
 * 对话框工具类，提供与当前主题兼容的对话框上下文
 */
public class DialogUtils {

    private DialogUtils() {}

    /**
     * 获取与当前主题兼容的对话框上下文
     * 使用应用自定义的 AlertDialogTheme，避免小米等设备上
     * abc_dialog_material_background 被误解析为 ColorStateList 导致崩溃
     */
    public static Context getDialogContext(Context baseContext) {
        if (baseContext == null) return null;

        boolean isDarkMode;
        boolean isBlackTheme;
        if (Settings.isThemeAutoSwitchAvailable()) {
            int nightModeFlags = baseContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
            isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
            isBlackTheme = false;
        } else {
            int theme = Settings.getTheme();
            isDarkMode = (theme == Settings.THEME_DARK || theme == Settings.THEME_BLACK);
            isBlackTheme = (theme == Settings.THEME_BLACK);
        }

        int dialogTheme;
        if (isBlackTheme) {
            dialogTheme = R.style.AlertDialogTheme_Black;
        } else if (isDarkMode) {
            dialogTheme = R.style.AlertDialogTheme_Dark;
        } else {
            dialogTheme = R.style.AlertDialogTheme_Light;
        }

        return new ContextThemeWrapper(baseContext, dialogTheme);
    }

    /**
     * 创建与当前主题兼容的 AlertDialog.Builder
     */
    public static AlertDialog.Builder createDialogBuilder(Context baseContext) {
        return new AlertDialog.Builder(getDialogContext(baseContext));
    }
}
