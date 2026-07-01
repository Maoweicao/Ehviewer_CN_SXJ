package com.hippo.ehviewer.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;

import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.Settings;

/**
 * 对话框工具类，提供与当前主题兼容的对话框上下文
 */
public class DialogUtils {

    private DialogUtils() {}

    /**
     * 获取与当前主题兼容的对话框上下文
     * 解决深色/黑色主题下 AlertDialog 崩溃的问题
     */
    public static Context getDialogContext(Context baseContext) {
        if (baseContext == null) return null;
        
        boolean isDarkMode;
        if (Settings.isThemeAutoSwitchAvailable()) {
            int nightModeFlags = baseContext.getResources().getConfiguration().uiMode 
                & Configuration.UI_MODE_NIGHT_MASK;
            isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
        } else {
            int theme = Settings.getTheme();
            isDarkMode = (theme == Settings.THEME_DARK || theme == Settings.THEME_BLACK);
        }
        
        int dialogTheme = isDarkMode 
            ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert 
            : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert;
        
        return new ContextThemeWrapper(baseContext, dialogTheme);
    }

    /**
     * 创建与当前主题兼容的 AlertDialog.Builder
     */
    public static AlertDialog.Builder createDialogBuilder(Context baseContext) {
        return new AlertDialog.Builder(getDialogContext(baseContext));
    }
}
