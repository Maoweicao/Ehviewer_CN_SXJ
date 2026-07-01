/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.R;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.hippo.content.ContextLocalWrapper;
import com.hippo.ehviewer.Analytics;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import java.util.Locale;

public abstract class EhActivity extends AppCompatActivity {

    /**
     * 获取主题资源ID，支持自适应模式切换
     * 子类可以重写此方法以自定义主题行为
     */
    @StyleRes
    protected int getThemeResId(int theme) {
        Log.d("EhActivity","Current Activity:"+this.getLocalClassName());
        Log.d("EhActivity", "getThemeResId: theme=" + theme);
        Log.d("EhActivity", "Settings.getTheme()=" + Settings.getTheme());
        Log.d("EhActivity", "Settings.isThemeAutoSwitchAvailable()=" + Settings.isThemeAutoSwitchAvailable());
        // 检查是否启用自动主题切换
        if (Settings.isThemeAutoSwitchAvailable()) {
            // 根据系统当前模式自动选择主题
            boolean isNightMode = (getResources().getConfiguration().uiMode & 
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            
            if (isNightMode) {
                // 夜间模式使用深色主题
                return R.style.AppTheme_Gallery_Dark;
            } else {
                // 日间模式使用浅色主题
                return R.style.AppTheme_Gallery_Light;
            }
        }
        
        // 手动主题选择
        switch (theme) {
            case Settings.THEME_LIGHT:
                return R.style.AppTheme_Gallery_Light;
            case Settings.THEME_DARK:
                return R.style.AppTheme_Gallery_Dark;
            case Settings.THEME_BLACK:
                return R.style.AppTheme_Gallery_Black;
            default:
                return R.style.AppTheme_Gallery;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {

//        setTheme(getThemeResId(Settings.getTheme(context)));
        setTheme(getThemeResId(Settings.getTheme()));
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 使用边缘到边缘布局，确保内容不被系统栏遮挡
            // 移除 SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION 以适配三键导航模式
            getWindow().getDecorView().setSystemUiVisibility(
                    getWindow().getDecorView().getSystemUiVisibility()
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
            // 设置导航栏透明以支持全屏沉浸式布局，配合 fitsSystemWindows 处理内边距
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        ((EhApplication) getApplication()).registerActivity(this);

        if (Analytics.isEnabled()) {
            FirebaseAnalytics.getInstance(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        ((EhApplication) getApplication()).unregisterActivity(this);
    }

    @Override
    public void onContentChanged() {
        super.onContentChanged();
        applyNavigationBarPaddingIfNeeded();
    }

    /**
     * MainActivity uses {@link com.hippo.ehviewer.widget.EhStageLayout} for window insets.
     * GalleryActivity is fullscreen and handles bottom controls separately.
     */
    protected boolean shouldApplyNavigationBarPadding() {
        return !(this instanceof MainActivity) && !(this instanceof GalleryActivity);
    }

    private void applyNavigationBarPaddingIfNeeded() {
        if (!shouldApplyNavigationBarPadding()) {
            return;
        }
        View target = findViewById(R.id.content_panel);
        if (target == null) {
            View content = findViewById(android.R.id.content);
            if (content instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) content;
                if (group.getChildCount() > 0) {
                    target = group.getChildAt(0);
                }
            }
        }
        if (target == null || target.getTag(R.id.navigation_bar_padding_applied) != null) {
            return;
        }
        target.setTag(R.id.navigation_bar_padding_applied, Boolean.TRUE);
        target.setTag(R.id.navigation_bar_padding_origin_bottom, target.getPaddingBottom());
        ViewCompat.setOnApplyWindowInsetsListener(target, (v, windowInsets) -> {
            Insets nav = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars());
            Object origin = v.getTag(R.id.navigation_bar_padding_origin_bottom);
            int originBottom = origin instanceof Integer ? (Integer) origin : 0;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    originBottom + nav.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(target);
    }

    private void applyEdgeToEdgeSystemBars() {
        int flags = android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
                && AttrResources.getAttrBoolean(this, android.R.attr.windowLightNavigationBar)) {
            flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
        syncNavigationBarWithTheme();
    }

    private void syncNavigationBarWithTheme() {
        getWindow().setNavigationBarColor(
                AttrResources.getAttrColor(this, android.R.attr.windowBackground));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            syncNavigationBarWithTheme();
        }
        if(Settings.getEnabledSecurity()){
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        }else{
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        Locale locale = null;
        String language = Settings.getAppLanguage();
        if (language != null && !language.equals("system")) {
            String[] split = language.split("-");
            if (split.length == 1) {
                locale = new Locale(split[0]);
            } else if (split.length == 2) {
                locale = new Locale(split[0], split[1]);
            } else if (split.length == 3) {
                locale = new Locale(split[0], split[1], split[2]);
            }
        }

        if (locale == null) {
            locale = Resources.getSystem().getConfiguration().locale;
        }
        newBase = ContextLocalWrapper.wrap(newBase, locale);
        super.attachBaseContext(newBase);
        Context context = newBase;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (Settings.isThemeAutoSwitchAvailable()) {
            boolean is_dark = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            if ((Settings.getTheme() == 0) == is_dark) {
                if (is_dark) {
                    Settings.putTheme(Settings.THEME_DARK);
                } else {
                    Settings.putTheme(Settings.THEME_LIGHT);
                }
                ((EhApplication) getApplication()).recreate();
            }
        }
    }
}
