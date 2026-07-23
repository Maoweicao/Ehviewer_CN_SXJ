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

package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.BackgroundTaskManager;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.network.NetworkLogger;
import com.hippo.ehviewer.network.NetworkSecurityManager;
import com.hippo.ehviewer.network.NetworkStateManager;
import com.hippo.ehviewer.service.CaptureService;
import com.hippo.ehviewer.service.DiagnosticExporter;
import com.hippo.ehviewer.service.HealthWatchdog;
import com.hippo.ehviewer.service.PerformanceMonitorService;
import com.hippo.ehviewer.ui.DirPickerActivity;
import com.hippo.unifile.UniFile;
import com.hippo.ehviewer.ui.PerformanceLogActivity;
import com.hippo.ehviewer.ui.wifi.WiFiClientActivity;
import com.hippo.ehviewer.ui.wifi.WiFiServerActivity;
import com.hippo.ehviewer.ui.task.BackgroundTaskActivity;
import com.hippo.ehviewer.ui.transfer.TransferActivity;
import com.hippo.ehviewer.ui.NetworkDiagnosticActivity;
import com.hippo.ehviewer.ui.CaptureActivity;
import com.hippo.ehviewer.ui.local.LocalGalleryActivity;
import com.hippo.ehviewer.widget.ProgressHelper;
import com.hippo.util.LogCat;
import com.hippo.util.ReadableTime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class AdvancedFragment extends BasePreferenceFragmentCompat
        implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener,
        NetworkSecurityManager.VpnFeatureStateListener {
    public static final int DB_LOADING = 0;
    public static final int DB_LOAD_FINISH = 1;

    public static final String LOADING_STATUS = "loading_status";
    public static final String LOADING_PROGRESS = "loading_progress";

    private static final String KEY_DUMP_LOGCAT = "dump_logcat";
    private static final String KEY_EXPORT_DATABASE = "export_database";
    private static final String KEY_CLEAR_MEMORY_CACHE = "clear_memory_cache";
    private static final String KEY_EXPORT_PATH = "export_path";
    private static final String KEY_APP_LANGUAGE = "app_language";
    private static final String KEY_IMPORT_DATA = "import_data";
    private static final String KEY_WIFI_SERVER = "wifi_server";
    private static final String KEY_WIFI_CLIENT = "wifi_client";
    private static final String KEY_BACKGROUND_TASKS = "background_tasks";
    private static final String KEY_SCHEDULED_TASKS = "scheduled_tasks";
    private static final String KEY_TRANSFER_SERVICE = "transfer_service";
    private static final String KEY_NETWORK_DIAGNOSTIC = "network_diagnostic";
    private static final String KEY_USER_AGENT = "user_agent";
    private static final String KEY_LOCAL_GALLERY = "local_gallery";
    private static final String KEY_RECYCLE_BIN = "recycle_bin";
    private static final String KEY_NETWORK_LOG = "network_log_enabled";
    private static final String KEY_BACKGROUND_CONCURRENT_TASKS = "background_concurrent_tasks";
    private static final String KEY_PERFORMANCE_MONITOR = "performance_monitor_enabled";
    private static final String KEY_PERFORMANCE_MONITOR_LOG = "performance_monitor_log";
    private static final String KEY_PRE_ANR_DETECTION = "pre_anr_detection_enabled";
    private static final String KEY_EXPORT_DIAGNOSTIC = "export_diagnostic";
    private static final String KEY_TRAFFIC_CAPTURE = "traffic_capture_enabled";
    private static final String KEY_TRAFFIC_CAPTURE_PAGE = "traffic_capture_page";
    private static final String KEY_VPN_AWARE_MODE = "vpn_aware_mode";
    private static final String KEY_VPN_STATUS_INDICATOR = "vpn_status_indicator";

    public static final int REQUEST_CODE_PICK_EXPORT_DIR = 10;
    private static final String TAG = "AdvancedFragment";

    private final DbSyncHandle dbSyncHandle = new DbSyncHandle(Looper.getMainLooper());

    private Context context;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        context = getContext();
        addPreferencesFromResource(R.xml.advanced_settings);

        Preference dumpLogcat = findPreference(KEY_DUMP_LOGCAT);
        Preference exportDatabase = findPreference(KEY_EXPORT_DATABASE);
        Preference clearMemoryCache = findPreference(KEY_CLEAR_MEMORY_CACHE);
        Preference exportPath = findPreference(KEY_EXPORT_PATH);
        Preference appLanguage = findPreference(KEY_APP_LANGUAGE);
        Preference importData = findPreference(KEY_IMPORT_DATA);
        Preference socketData = findPreference(KEY_WIFI_SERVER);
        Preference clientData = findPreference(KEY_WIFI_CLIENT);
        Preference backgroundTasks = findPreference(KEY_BACKGROUND_TASKS);
        Preference transferService = findPreference(KEY_TRANSFER_SERVICE);
        Preference networkDiagnostic = findPreference(KEY_NETWORK_DIAGNOSTIC);
        Preference userAgent = findPreference(KEY_USER_AGENT);
        Preference localGallery = findPreference(KEY_LOCAL_GALLERY);
        Preference recycleBin = findPreference(KEY_RECYCLE_BIN);
        Preference networkLog = findPreference(KEY_NETWORK_LOG);
        Preference backgroundConcurrentTasks = findPreference(KEY_BACKGROUND_CONCURRENT_TASKS);
        Preference performanceMonitor = findPreference(KEY_PERFORMANCE_MONITOR);
        Preference performanceMonitorLog = findPreference(KEY_PERFORMANCE_MONITOR_LOG);
        Preference preAnrDetection = findPreference(KEY_PRE_ANR_DETECTION);
        Preference exportDiagnostic = findPreference(KEY_EXPORT_DIAGNOSTIC);
        Preference trafficCapture = findPreference(KEY_TRAFFIC_CAPTURE);
        Preference trafficCapturePage = findPreference(KEY_TRAFFIC_CAPTURE_PAGE);

        dumpLogcat.setOnPreferenceClickListener(this);
        if (exportDatabase != null) {
            exportDatabase.setOnPreferenceClickListener(this);
        }
        clearMemoryCache.setOnPreferenceClickListener(this);
        if (exportPath != null) {
            exportPath.setOnPreferenceClickListener(this);
            UniFile exportDir = Settings.getExportLocation();
            if (exportDir != null && exportDir.getUri() != null) {
                exportPath.setSummary(exportDir.getUri().toString());
            }
        }
        importData.setOnPreferenceClickListener(this);
        socketData.setOnPreferenceClickListener(this);
        clientData.setOnPreferenceClickListener(this);
        backgroundTasks.setOnPreferenceClickListener(this);
        Preference scheduledTasks = findPreference(KEY_SCHEDULED_TASKS);
        if (scheduledTasks != null) {
            scheduledTasks.setOnPreferenceClickListener(this);
        }
        transferService.setOnPreferenceClickListener(this);
        networkDiagnostic.setOnPreferenceClickListener(this);
        userAgent.setOnPreferenceClickListener(this);
        localGallery.setOnPreferenceClickListener(this);
        if (recycleBin != null) {
            recycleBin.setOnPreferenceClickListener(this);
        }

        appLanguage.setOnPreferenceChangeListener(this);
        if (networkLog != null) {
            networkLog.setOnPreferenceChangeListener(this);
        }
        if (backgroundConcurrentTasks != null) {
            backgroundConcurrentTasks.setOnPreferenceChangeListener(this);
        }

        if (performanceMonitor != null) {
            performanceMonitor.setOnPreferenceChangeListener(this);
        }
        if (performanceMonitorLog != null) {
            performanceMonitorLog.setOnPreferenceClickListener(this);
        }

        if (preAnrDetection != null) {
            preAnrDetection.setOnPreferenceChangeListener(this);
        }
        if (exportDiagnostic != null) {
            exportDiagnostic.setOnPreferenceClickListener(this);
        }

        if (trafficCapture != null) {
            trafficCapture.setOnPreferenceChangeListener(this);
        }
        if (trafficCapturePage != null) {
            trafficCapturePage.setOnPreferenceClickListener(this);
        }

        // VPN aware mode setting
        Preference vpnAwareMode = findPreference(KEY_VPN_AWARE_MODE);
        if (vpnAwareMode != null) {
            vpnAwareMode.setOnPreferenceChangeListener(this);
        }

        // Register for VPN state changes
        NetworkSecurityManager.INSTANCE.addListener(this);

        // Initial update of VPN status indicator
        updateVpnStatusIndicator();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Update VPN status when returning to this screen
        updateVpnStatusIndicator();
    }

    @Override
    public void onDestroyView() {
        // Unregister VPN state listener
        NetworkSecurityManager.INSTANCE.removeListener(this);
        super.onDestroyView();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        switch (key) {
            case KEY_DUMP_LOGCAT:
                return dumpLogcat();
            case KEY_EXPORT_DATABASE:
                return exportDatabase();
            case KEY_CLEAR_MEMORY_CACHE:
                return clearMemoryCache();
            case KEY_IMPORT_DATA:
                importData(getActivity());
                getActivity().setResult(Activity.RESULT_OK);
                return true;
            case KEY_WIFI_SERVER:
                return gotoWiFiServerActivity();
            case KEY_WIFI_CLIENT:
                return gotoWiFiClientActivity();
            case KEY_BACKGROUND_TASKS:
                return gotoBackgroundTaskActivity();
            case KEY_SCHEDULED_TASKS:
                return gotoScheduledTaskActivity();
            case KEY_TRANSFER_SERVICE:
                return gotoTransferActivity();
            case KEY_NETWORK_DIAGNOSTIC:
                return gotoNetworkDiagnosticActivity();
            case KEY_USER_AGENT:
                return showUserAgentDialog();
            case KEY_EXPORT_PATH:
                return openExportDirPicker();
            case KEY_LOCAL_GALLERY:
                return gotoLocalGalleryActivity();
            case KEY_RECYCLE_BIN:
                return gotoRecycleBinActivity();
            case KEY_PERFORMANCE_MONITOR_LOG:
                return gotoPerformanceLogActivity();
            case KEY_TRAFFIC_CAPTURE_PAGE:
                return gotoCaptureActivity();
            case KEY_EXPORT_DIAGNOSTIC:
                return exportDiagnostic();
            default:
                return false;
        }
    }

    private boolean gotoWiFiClientActivity() {
        Activity activity = getActivity();
        Intent intent = new Intent(activity, WiFiClientActivity.class);
        activity.startActivity(intent);
        return false;
    }

    private boolean gotoWiFiServerActivity() {
        Activity activity = getActivity();
        Intent intent = new Intent(activity, WiFiServerActivity.class);
        activity.startActivity(intent);
        return false;
    }

    private boolean gotoBackgroundTaskActivity() {
        Activity activity = getActivity();
        BackgroundTaskActivity.start(activity);
        return true;
    }

    private boolean gotoScheduledTaskActivity() {
        Activity activity = getActivity();
        com.hippo.ehviewer.ui.scheduled.ScheduledTaskActivity.start(activity);
        return true;
    }

    private boolean gotoTransferActivity() {
        Activity activity = getActivity();
        Intent intent = new Intent(activity, TransferActivity.class);
        activity.startActivity(intent);
        return true;
    }

    private boolean gotoNetworkDiagnosticActivity() {
        Activity activity = getActivity();
        Intent intent = new Intent(activity, NetworkDiagnosticActivity.class);
        activity.startActivity(intent);
        return true;
    }

    private boolean gotoLocalGalleryActivity() {
        Activity activity = getActivity();
        Intent intent = new Intent(activity, LocalGalleryActivity.class);
        activity.startActivity(intent);
        return true;
    }

    private boolean gotoRecycleBinActivity() {
        Activity activity = getActivity();
        LocalGalleryActivity.startRecycleBin(activity);
        return true;
    }

    private boolean gotoPerformanceLogActivity() {
        Activity activity = getActivity();
        Intent intent = new Intent(activity, PerformanceLogActivity.class);
        activity.startActivity(intent);
        return true;
    }

    private boolean gotoCaptureActivity() {
        Activity activity = getActivity();
        Intent intent = new Intent(activity, CaptureActivity.class);
        activity.startActivity(intent);
        return true;
    }

    private boolean exportDiagnostic() {
        Context ctx = getContext();
        if (ctx == null) {
            return true;
        }
        try {
            File file = DiagnosticExporter.INSTANCE.export(ctx);
            if (file != null) {
                Toast.makeText(ctx,
                        getString(R.string.settings_advanced_export_diagnostic_done, file.getAbsolutePath()),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(ctx, R.string.settings_advanced_export_diagnostic_empty, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable e) {
            Toast.makeText(ctx, R.string.settings_advanced_export_diagnostic_failed, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private boolean openExportDirPicker() {
        Activity activity = getActivity();
        if (activity == null) {
            return false;
        }

        UniFile uniFile = Settings.getExportLocation();
        Intent intent = new Intent(activity, DirPickerActivity.class);
        if (uniFile != null && uniFile.getUri() != null) {
            intent.putExtra(DirPickerActivity.KEY_FILE_URI, uniFile.getUri());
        }
        startActivityForResult(intent, REQUEST_CODE_PICK_EXPORT_DIR);
        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_EXPORT_DIR && resultCode == Activity.RESULT_OK && data != null) {
            UniFile uniFile = UniFile.fromUri(getContext(), data.getData());
            if (uniFile != null) {
                Settings.putExportLocation(uniFile);
                Preference exportPath = findPreference(KEY_EXPORT_PATH);
                if (exportPath != null && uniFile.getUri() != null) {
                    exportPath.setSummary(uniFile.getUri().toString());
                }
                String path = uniFile.getUri() != null ? uniFile.getUri().toString() : null;
                if (path != null) {
                    Toast.makeText(getContext(), getString(R.string.settings_advanced_export_path_set, path), Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private boolean showUserAgentDialog() {
        Context context = getContext();
        if (context == null) return false;
        
        // 创建输入框
        android.widget.EditText editText = new android.widget.EditText(context);
        editText.setText(com.hippo.ehviewer.Settings.getUserAgent());
        editText.setSingleLine(false);
        editText.setHorizontallyScrolling(false);
        editText.setMinLines(3);
        editText.setMaxLines(6);
        editText.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        editText.setScroller(new android.widget.Scroller(context));
        editText.setVerticalScrollBarEnabled(true);
        
        // 创建对话框
        new AlertDialog.Builder(context)
                .setTitle(R.string.settings_advanced_user_agent)
                .setView(editText)
                .setPositiveButton(R.string.settings_advanced_user_agent_restore_default, (dialog, which) -> {
                    // 恢复默认值
                    com.hippo.ehviewer.Settings.putUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
                    Toast.makeText(context, R.string.settings_advanced_user_agent_restored, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.settings_advanced_user_agent_save, (dialog, which) -> {
                    // 保存用户输入的值
                    String userAgent = editText.getText().toString().trim();
                    if (!userAgent.isEmpty()) {
                        com.hippo.ehviewer.Settings.putUserAgent(userAgent);
                        Toast.makeText(context, R.string.settings_advanced_user_agent_saved, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, R.string.settings_advanced_user_agent_empty, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
        
        return true;
    }

    private boolean clearMemoryCache() {
        ((EhApplication) getActivity().getApplication()).clearMemoryCache();
        Runtime.getRuntime().gc();
        return false;
    }

    private boolean dumpLogcat() {
        com.hippo.ehviewer.task.DumpLogcatTask task =
                new com.hippo.ehviewer.task.DumpLogcatTask(requireContext());
        com.hippo.ehviewer.BackgroundTaskManager.getInstance().submitBackgroundTask(task);
        Toast.makeText(requireContext(), R.string.settings_advanced_dump_logcat_started, Toast.LENGTH_SHORT).show();
        return true;
    }

    private boolean exportDatabase() {
        com.hippo.ehviewer.task.ExportDatabaseTask task =
                new com.hippo.ehviewer.task.ExportDatabaseTask(requireContext());
        com.hippo.ehviewer.BackgroundTaskManager.getInstance().submitBackgroundTask(task);
        Toast.makeText(requireContext(), R.string.settings_advanced_export_database_started, Toast.LENGTH_SHORT).show();
        return true;
    }

    private boolean importData(final Context context) {
        final File dir = AppConfig.getExternalDataDir();
        if (null == dir) {
            Toast.makeText(context, R.string.cant_get_data_dir, Toast.LENGTH_SHORT).show();
            return false;
        }
        final String[] allFiles = dir.list();
        if (null == allFiles || allFiles.length <= 0) {
            Toast.makeText(context, R.string.cant_find_any_data, Toast.LENGTH_SHORT).show();
            return false;
        }
        final String[] files = Arrays.stream(allFiles)
                .filter(f -> f.endsWith(".db") || f.endsWith(".csv"))
                .toArray(String[]::new);
        if (files.length <= 0) {
            Toast.makeText(context, R.string.cant_find_any_data, Toast.LENGTH_SHORT).show();
            return false;
        }
        Arrays.sort(files);
        new AlertDialog.Builder(context).setItems(files, (dialog, which) -> {
            dialog.dismiss();
            File file = new File(dir, files[which]);
            if (file.getName().endsWith(".db")) {
                com.hippo.ehviewer.task.impl.ImportDataTask task =
                        new com.hippo.ehviewer.task.impl.ImportDataTask(context, file);
                com.hippo.ehviewer.BackgroundTaskManager.getInstance().submitBackgroundTask(task);
            } else if (file.getName().endsWith(".csv")) {
                com.hippo.ehviewer.task.impl.ImportLegacyDataTask task =
                        new com.hippo.ehviewer.task.impl.ImportLegacyDataTask(context, file);
                com.hippo.ehviewer.BackgroundTaskManager.getInstance().submitBackgroundTask(task);
            }
            Toast.makeText(context, R.string.settings_advanced_import_data_started, Toast.LENGTH_SHORT).show();
        }).show();
        return false;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (KEY_APP_LANGUAGE.equals(key)) {
            ((EhApplication) getActivity().getApplication()).recreate();
            return true;
        }
        if (KEY_NETWORK_LOG.equals(key)) {
            NetworkLogger.INSTANCE.onSettingChanged();
            return true;
        }
        if (KEY_BACKGROUND_CONCURRENT_TASKS.equals(key)) {
            int concurrentTasks;
            try {
                concurrentTasks = Integer.parseInt(String.valueOf(newValue));
            } catch (Exception e) {
                return false;
            }

            Settings.putBackgroundConcurrentTasks(concurrentTasks);
            try {
                BackgroundTaskManager.getInstance().applyBackgroundConcurrentTaskSetting();
            } catch (IllegalStateException ignored) {
            }
            Toast.makeText(getContext(), R.string.settings_advanced_background_concurrent_tasks_applied, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (KEY_PERFORMANCE_MONITOR.equals(key)) {
            boolean enabled = Boolean.TRUE.equals(newValue);
            Settings.putPerformanceMonitorEnabled(enabled);
            if (enabled) {
                PerformanceMonitorService.start(requireContext());
                Toast.makeText(getContext(), R.string.settings_advanced_performance_monitor_summary, Toast.LENGTH_SHORT).show();
            } else {
                PerformanceMonitorService.stop(requireContext());
            }
            return true;
        }
        if (KEY_TRAFFIC_CAPTURE.equals(key)) {
            boolean enabled = Boolean.TRUE.equals(newValue);
            Settings.putTrafficCaptureEnabled(enabled);
            if (enabled) {
                CaptureService.start(requireContext());
                Toast.makeText(getContext(), R.string.capture_started, Toast.LENGTH_SHORT).show();
            } else {
                CaptureService.saveAndStop(requireContext());
                Toast.makeText(getContext(), R.string.capture_saved, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (KEY_PRE_ANR_DETECTION.equals(key)) {
            boolean enabled = Boolean.TRUE.equals(newValue);
            Settings.putPreAnrDetectionEnabled(enabled);
            if (enabled) {
                HealthWatchdog.INSTANCE.start();
                Toast.makeText(getContext(), R.string.settings_advanced_pre_anr_detection_enabled, Toast.LENGTH_SHORT).show();
            } else {
                HealthWatchdog.INSTANCE.stop();
                Toast.makeText(getContext(), R.string.settings_advanced_pre_anr_detection_disabled, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (KEY_VPN_AWARE_MODE.equals(key)) {
            int mode;
            try {
                mode = Integer.parseInt(String.valueOf(newValue));
            } catch (Exception e) {
                return false;
            }
            Settings.putVpnAwareMode(mode);
            // Update VPN status indicator immediately
            updateVpnStatusIndicator();
            return true;
        }
        return false;
    }

    /**
     * Update VPN status indicator with current feature states
     */
    private void updateVpnStatusIndicator() {
        Preference vpnStatus = findPreference(KEY_VPN_STATUS_INDICATOR);
        if (vpnStatus == null) {
            return;
        }

        NetworkSecurityManager.FeatureStates states = NetworkSecurityManager.INSTANCE.getFeatureStatesSummary();

        StringBuilder summary = new StringBuilder();

        // VPN connection status
        if (states.isVpnActive()) {
            summary.append(getString(R.string.settings_vpn_status_active));
        } else {
            summary.append(getString(R.string.settings_vpn_status_inactive));
        }

        // Feature states when VPN is active
        if (states.isVpnActive() && states.getVpnAwareMode() == Settings.VPN_MODE_AUTO_DISABLE) {
            summary.append("\n");

            if (states.getDohDisabledByVpn()) {
                summary.append("• ").append(getString(R.string.settings_vpn_status_doh_disabled)).append("\n");
            }
            if (states.getDfDisabledByVpn()) {
                summary.append("• ").append(getString(R.string.settings_vpn_status_df_disabled)).append("\n");
            }

            if (!states.getDohDisabledByVpn() && !states.getDfDisabledByVpn()) {
                summary.append("• ").append(getString(R.string.settings_vpn_status_all_enabled));
            }
        }

        // Remove trailing newline if present
        if (summary.length() > 0 && summary.charAt(summary.length() - 1) == '\n') {
            summary.setLength(summary.length() - 1);
        }

        vpnStatus.setSummary(summary.toString());
    }

    @Override
    public void onVpnFeatureStateChanged(boolean dohEnabled, boolean dfEnabled) {
        // Update UI on main thread
        if (getActivity() != null) {
            getActivity().runOnUiThread(this::updateVpnStatusIndicator);
        }
    }

    private class DbSyncHandle extends Handler {
        public DbSyncHandle(Looper mainLooper) {
            super(mainLooper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            Bundle data = msg.getData();
            int state = data.getInt(LOADING_STATUS);
            if (state == DB_LOAD_FINISH){
                ProgressHelper.dismissDialog();
                String error = data.getString("error");
                if (context == null) {
                    return;
                }
                if (null == error) {
                    error = context.getString(R.string.settings_advanced_import_data_successfully);
                }

                Toast.makeText(context, error, Toast.LENGTH_SHORT).show();
            } else if (state == DB_LOADING) {
                ProgressHelper.setProgress(data.getInt(LOADING_PROGRESS,0));
            }

        }
    }
}
