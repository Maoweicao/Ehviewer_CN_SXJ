/*
 * Copyright 2025 EhViewer Contributors
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

package com.hippo.ehviewer.ui.transfer;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.transfer.TransferService;
import com.hippo.ehviewer.transfer.core.DeviceDiscoveryManager;
import com.hippo.ehviewer.transfer.core.NetworkUtils;
import com.hippo.ehviewer.transfer.data.ClientInfo;
import com.hippo.ehviewer.transfer.data.ConnectedDevice;
import com.hippo.ehviewer.transfer.data.DiscoveredDevice;
import com.hippo.ehviewer.transfer.data.NetworkAddress;
import com.hippo.ehviewer.transfer.core.TransferClientManager;
import com.hippo.ehviewer.transfer.log.LogAdapter;
import com.hippo.ehviewer.transfer.log.LogEntry;
import com.hippo.ehviewer.transfer.log.LogLevel;
import com.hippo.ehviewer.transfer.log.TransferLogger;
import com.hippo.ehviewer.ui.ToolbarActivity;
import com.hippo.ehviewer.ui.transfer.adapter.AddressListAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据传输服务界面
 */
public class TransferActivity extends ToolbarActivity implements TransferService.ServiceCallback {

    private static final String TAG = "TransferActivity";

    private TransferService transferService;
    private ServiceConnection serviceConnection;
    private boolean isBound = false;

    private DeviceDiscoveryManager deviceDiscoveryManager;
    private TransferClientManager transferClientManager;
    
    // ActivityResultLauncher for DataSelector
    private final ActivityResultLauncher<Intent> dataSelectorLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String type = result.getData().getStringExtra(DataSelectorActivity.EXTRA_TYPE);
                    List<Long> selectedGids = (List<Long>) result.getData().getSerializableExtra(DataSelectorActivity.EXTRA_SELECTED_GIDS);
                    if (type != null && selectedGids != null && !selectedGids.isEmpty()) {
                        handleSelectedData(type, selectedGids);
                    }
                }
            }
    );

    // UI components
    private Button startButton;
    private Button stopButton;
    private ProgressBar loadingBar;
    private TextView statusText;
    private TextView clientCountText;
    private RecyclerView clientListView;
    private ClientListAdapter clientAdapter;
    private List<ClientInfo> clientList;

    // Address list
    private RecyclerView addressListView;
    private AddressListAdapter addressAdapter;

    // Remote management
    private ImageButton remoteSettingsButton;
    private TextView remoteAccessInfo;
    private Button remoteOpenBrowser;
    private Button remoteCopyUrl;
    private com.google.android.material.switchmaterial.SwitchMaterial remoteDeleteSwitch;
    private com.google.android.material.switchmaterial.SwitchMaterial remoteManagementSwitch;
    private View remoteManagementContent;

    // Send data
    private Button sendBookmarksAll;
    private Button sendBookmarksSelect;
    private Button sendDownloadsAll;
    private Button sendDownloadsSelect;
    private Button sendFavoritesAll;
    private Button sendFavoritesSelect;
    private Button sendAllData;

    // Connect to
    private EditText addressInput;
    private Button connectButton;
    private Button refreshDevicesButton;
    private Spinner refreshIntervalSpinner;
    private RecyclerView discoveredListView;
    private RecyclerView connectedListView;

    // Log section
    private View logHeader;
    private TextView logExpandIcon;
    private Spinner logLevelSpinner;
    private Button logClearButton;
    private Button logExportButton;
    private RecyclerView logListView;
    private LogAdapter logAdapter;
    private boolean isLogExpanded = false;
    private TransferLogger transferLogger;

    // Relay task section
    private RecyclerView relayTaskListView;
    private RelayTaskAdapter relayTaskAdapter;
    private TextView relayEmptyText;
    private Button relayTabIncoming;
    private Button relayTabOutgoing;
    private Button relayTabAll;
    private Button relayRefreshButton;
    private Button relayClearCompletedButton;
    private com.hippo.ehviewer.transfer.core.RelayTaskManager relayTaskManager;
    private String relayFilterDirection = "all";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);

        initializeUI();
        setupServiceConnection();
        bindTransferService();

        String openTab = getIntent().getStringExtra("open_tab");
        if ("relay".equals(openTab)) {
            findViewById(R.id.relay_task_list).post(() -> {
                findViewById(R.id.relay_task_list).requestFocus();
            });
        }
    }

    private void initializeUI() {
        // Service control
        startButton = findViewById(R.id.transfer_start_button);
        stopButton = findViewById(R.id.transfer_stop_button);
        loadingBar = findViewById(R.id.transfer_loading_bar);
        statusText = findViewById(R.id.transfer_status_text);
        clientCountText = findViewById(R.id.transfer_client_count);
        clientListView = findViewById(R.id.transfer_client_list);

        // Address list
        addressListView = findViewById(R.id.address_list);
        addressAdapter = new AddressListAdapter(this);
        addressListView.setLayoutManager(new LinearLayoutManager(this));
        addressListView.setAdapter(addressAdapter);

        // Remote management
        remoteSettingsButton = findViewById(R.id.remote_settings_button);
        remoteAccessInfo = findViewById(R.id.remote_access_info);
        remoteOpenBrowser = findViewById(R.id.remote_open_browser);
        remoteCopyUrl = findViewById(R.id.remote_copy_url);
        remoteDeleteSwitch = findViewById(R.id.remote_delete_switch);
        remoteManagementSwitch = findViewById(R.id.remote_management_switch);
        remoteManagementContent = findViewById(R.id.remote_management_content);

        // Initialize remote management switch
        remoteManagementSwitch.setChecked(Settings.isRemoteManagementEnabled());
        updateRemoteManagementUI(Settings.isRemoteManagementEnabled());
        remoteManagementSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Settings.putRemoteManagementEnabled(isChecked);
            updateRemoteManagementUI(isChecked);
            // Invalidate system info cache so remote clients see the change immediately
            com.hippo.ehviewer.transfer.core.ResponseCache.getInstance().invalidateSettings();
            Toast.makeText(this, isChecked ? R.string.remote_management_enabled_label : R.string.remote_management_disabled_label, Toast.LENGTH_SHORT).show();
        });

        // Initialize remote delete switch
        remoteDeleteSwitch.setChecked(Settings.isRemoteDeleteEnabled());
        remoteDeleteSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Settings.putRemoteDeleteEnabled(isChecked);
            // Invalidate system info cache so remote clients see the change immediately
            com.hippo.ehviewer.transfer.core.ResponseCache.getInstance().invalidateSettings();
            Toast.makeText(this, isChecked ? R.string.remote_delete_enabled : R.string.remote_delete_disabled, Toast.LENGTH_SHORT).show();
        });

        // Send data
        sendBookmarksAll = findViewById(R.id.send_bookmarks_all);
        sendBookmarksSelect = findViewById(R.id.send_bookmarks_select);
        sendDownloadsAll = findViewById(R.id.send_downloads_all);
        sendDownloadsSelect = findViewById(R.id.send_downloads_select);
        sendFavoritesAll = findViewById(R.id.send_favorites_all);
        sendFavoritesSelect = findViewById(R.id.send_favorites_select);
        sendAllData = findViewById(R.id.send_all_data);

        // Connect to
        addressInput = findViewById(R.id.address_input);
        connectButton = findViewById(R.id.connect_button);
        refreshDevicesButton = findViewById(R.id.refresh_devices);
        refreshIntervalSpinner = findViewById(R.id.refresh_interval_spinner);
        discoveredListView = findViewById(R.id.discovered_list);
        connectedListView = findViewById(R.id.connected_list);

        // Setup client list
        clientList = new ArrayList<>();
        clientAdapter = new ClientListAdapter(this, clientList);
        clientListView.setLayoutManager(new LinearLayoutManager(this));
        clientListView.setAdapter(clientAdapter);

        // Setup discovered list
        discoveredListView.setLayoutManager(new LinearLayoutManager(this));

        // Setup connected list
        connectedListView.setLayoutManager(new LinearLayoutManager(this));

        // Setup refresh interval spinner
        setupRefreshIntervalSpinner();

        // Set click listeners
        startButton.setOnClickListener(v -> startTransferService());
        stopButton.setOnClickListener(v -> stopTransferService());
        remoteSettingsButton.setOnClickListener(v -> showRemoteSettingsDialog());
        remoteOpenBrowser.setOnClickListener(v -> openRemoteInBrowser());
        remoteCopyUrl.setOnClickListener(v -> copyRemoteUrl());

        sendBookmarksAll.setOnClickListener(v -> sendAll("bookmarks"));
        sendBookmarksSelect.setOnClickListener(v -> openDataSelector("bookmarks"));
        sendDownloadsAll.setOnClickListener(v -> sendAll("downloads"));
        sendDownloadsSelect.setOnClickListener(v -> openDataSelector("downloads"));
        sendFavoritesAll.setOnClickListener(v -> sendAll("favorites"));
        sendFavoritesSelect.setOnClickListener(v -> openDataSelector("favorites"));
        sendAllData.setOnClickListener(v -> sendAllData());

        connectButton.setOnClickListener(v -> connectToDevice());
        refreshDevicesButton.setOnClickListener(v -> refreshDevices());

        // Initialize device discovery
        deviceDiscoveryManager = new DeviceDiscoveryManager(this);
        deviceDiscoveryManager.addListener(devices -> {
            runOnUiThread(() -> updateDiscoveredDevices(devices));
        });

        // Initialize transfer client manager
        transferClientManager = TransferClientManager.getInstance(this);

        // Initialize relay task section
        setupRelayTaskSection();

        // Initialize log section
        setupLogSection();

        updateUI();
    }

    /**
     * 设置日志区域
     */
    private void setupLogSection() {
        transferLogger = TransferLogger.getInstance();

        logHeader = findViewById(R.id.log_header);
        logExpandIcon = findViewById(R.id.log_expand_icon);
        logLevelSpinner = findViewById(R.id.log_level_spinner);
        logClearButton = findViewById(R.id.log_clear_button);
        logExportButton = findViewById(R.id.log_export_button);
        logListView = findViewById(R.id.log_list);

        // Setup log adapter
        logAdapter = new LogAdapter();
        logListView.setAdapter(logAdapter);

        // Setup log level spinner
        String[] logLevels = {"全部", "Debug", "Info", "Warn", "Error"};
        ArrayAdapter<String> logLevelAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, logLevels);
        logLevelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        logLevelSpinner.setAdapter(logLevelAdapter);
        logLevelSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                filterLogs(position);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        // Setup click listeners
        logHeader.setOnClickListener(v -> toggleLogSection());
        logClearButton.setOnClickListener(v -> clearLogs());
        logExportButton.setOnClickListener(v -> exportLogs());

        // Register log listener
        transferLogger.addListener(new TransferLogger.LogListener() {
            @Override
            public void onLogAdded(LogEntry entry) {
                runOnUiThread(() -> {
                    if (shouldShowLog(entry)) {
                        logAdapter.addLog(entry);
                    }
                });
            }

            @Override
            public void onLogsCleared() {
                runOnUiThread(() -> logAdapter.clear());
            }
        });

        // Load existing logs
        List<LogEntry> existingLogs = transferLogger.getLogs();
        logAdapter.setLogs(existingLogs);
    }

    /**
     * 设置接力任务区域
     */
    private void setupRelayTaskSection() {
        relayTaskManager = com.hippo.ehviewer.transfer.core.RelayTaskManager.getInstance(this);

        relayTaskListView = findViewById(R.id.relay_task_list);
        relayEmptyText = findViewById(R.id.relay_empty_text);
        relayTabIncoming = findViewById(R.id.relay_tab_incoming);
        relayTabOutgoing = findViewById(R.id.relay_tab_outgoing);
        relayTabAll = findViewById(R.id.relay_tab_all);
        relayRefreshButton = findViewById(R.id.relay_refresh);
        relayClearCompletedButton = findViewById(R.id.relay_clear_completed);

        // Setup RecyclerView
        relayTaskAdapter = new RelayTaskAdapter();
        relayTaskListView.setLayoutManager(new LinearLayoutManager(this));
        relayTaskListView.setAdapter(relayTaskAdapter);

        // Setup action listener
        relayTaskAdapter.setOnRelayActionListener(new RelayTaskAdapter.OnRelayActionListener() {
            @Override
            public void onAccept(com.hippo.ehviewer.transfer.data.RelayTask task) {
                handleRelayAccept(task);
            }

            @Override
            public void onReject(com.hippo.ehviewer.transfer.data.RelayTask task) {
                handleRelayReject(task);
            }

            @Override
            public void onCancel(com.hippo.ehviewer.transfer.data.RelayTask task) {
                handleRelayCancel(task);
            }

            @Override
            public void onRetrieve(com.hippo.ehviewer.transfer.data.RelayTask task) {
                handleRelayRetrieve(task);
            }

            @Override
            public void onDelete(com.hippo.ehviewer.transfer.data.RelayTask task) {
                handleRelayDelete(task);
            }
        });

        // Setup tab buttons
        relayTabIncoming.setOnClickListener(v -> {
            relayFilterDirection = "incoming";
            refreshRelayTaskList();
        });
        relayTabOutgoing.setOnClickListener(v -> {
            relayFilterDirection = "outgoing";
            refreshRelayTaskList();
        });
        relayTabAll.setOnClickListener(v -> {
            relayFilterDirection = "all";
            refreshRelayTaskList();
        });
        relayRefreshButton.setOnClickListener(v -> refreshRelayTaskList());
        relayClearCompletedButton.setOnClickListener(v -> handleClearCompleted());

        // Register relay task listener
        relayTaskManager.addListener(new com.hippo.ehviewer.transfer.core.RelayTaskManager.RelayTaskListener() {
            @Override
            public void onTaskCreated(com.hippo.ehviewer.transfer.data.RelayTask task) {
                runOnUiThread(() -> refreshRelayTaskList());
            }

            @Override
            public void onTaskUpdated(com.hippo.ehviewer.transfer.data.RelayTask task) {
                runOnUiThread(() -> refreshRelayTaskList());
            }

            @Override
            public void onTaskDeleted(com.hippo.ehviewer.transfer.data.RelayTask task) {
                runOnUiThread(() -> refreshRelayTaskList());
            }
        });

        // Initial load
        refreshRelayTaskList();
    }

    /**
     * 刷新接力任务列表
     */
    private void refreshRelayTaskList() {
        List<com.hippo.ehviewer.transfer.data.RelayTask> tasks =
                relayTaskManager.getTasks("all", relayFilterDirection);
        relayTaskAdapter.setTasks(tasks);

        if (tasks.isEmpty()) {
            relayEmptyText.setVisibility(View.VISIBLE);
            relayTaskListView.setVisibility(View.GONE);
        } else {
            relayEmptyText.setVisibility(View.GONE);
            relayTaskListView.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 接受接力任务
     */
    private void handleRelayAccept(com.hippo.ehviewer.transfer.data.RelayTask task) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.relay_accept)
                .setMessage("接受接力任务并开始下载？\n" + (task.getTitle() != null ? task.getTitle() : "GID: " + task.getGid()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    relayTaskManager.acceptTask(task.getTaskId());
                    relayTaskManager.startDownload(task.getTaskId());
                    Toast.makeText(this, R.string.relay_accept, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 拒绝接力任务
     */
    private void handleRelayReject(com.hippo.ehviewer.transfer.data.RelayTask task) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.relay_reject)
                .setMessage("拒绝此接力任务？")
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    relayTaskManager.rejectTask(task.getTaskId());
                    Toast.makeText(this, R.string.relay_reject, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 取消接力任务
     */
    private void handleRelayCancel(com.hippo.ehviewer.transfer.data.RelayTask task) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.relay_cancel)
                .setMessage("取消此接力任务？")
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    relayTaskManager.cancelTask(task.getTaskId());
                    Toast.makeText(this, R.string.relay_cancel, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 取回接力文件（从执行端下载ZIP并解压到本地下载目录）
     */
    private void handleRelayRetrieve(com.hippo.ehviewer.transfer.data.RelayTask task) {
        if (transferClientManager == null || transferClientManager.getConnectedDevices().isEmpty()) {
            Toast.makeText(this, "没有已连接的设备", Toast.LENGTH_SHORT).show();
            return;
        }

        ConnectedDevice executorDevice = findExecutorDevice(task);
        if (executorDevice == null) {
            Toast.makeText(this, "找不到执行端设备。请确保已连接", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "开始取回文件...", Toast.LENGTH_SHORT).show();

        transferClientManager.downloadRelayZip(executorDevice, task.getTaskId(),
                new com.hippo.ehviewer.transfer.core.TransferClientManager.RelayZipCallback() {
                    @Override
                    public void onSuccess(String zipFilePath) {
                        java.io.File zipFile = new java.io.File(zipFilePath);
                        if (!zipFile.exists()) {
                            Toast.makeText(TransferActivity.this, "下载的文件不存在", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        try {
                            long gid = task.getGid();
                            com.hippo.ehviewer.dao.DownloadInfo downloadInfo =
                                    com.hippo.ehviewer.EhApplication.getDownloadManager(TransferActivity.this).getDownloadInfo(gid);
                            if (downloadInfo == null) {
                                Toast.makeText(TransferActivity.this, "找不到下载记录", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            com.hippo.unifile.UniFile downloadDir =
                                    com.hippo.ehviewer.spider.SpiderDen.getGalleryDownloadDir(downloadInfo);
                            if (downloadDir == null || !downloadDir.isDirectory()) {
                                Toast.makeText(TransferActivity.this, "下载目录不存在", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            java.io.File extractTmpDir = new java.io.File(
                                    getCacheDir(),
                                    "relay_retrieve_extract_" + gid + "_" + System.currentTimeMillis());
                            extractTmpDir.mkdirs();

                            boolean extracted = com.hippo.ehviewer.util.GZIPUtils.UnZipFolder(
                                    zipFile.getAbsolutePath(), extractTmpDir.getAbsolutePath());
                            zipFile.delete();

                            if (!extracted) {
                                deleteDir(extractTmpDir);
                                Toast.makeText(TransferActivity.this, "解压失败", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            int fileCount = copyFiles(extractTmpDir, downloadDir);
                            deleteDir(extractTmpDir);

                            downloadInfo.state = com.hippo.ehviewer.dao.DownloadInfo.STATE_FINISH;
                            downloadInfo.speed = 0;
                            downloadInfo.remaining = 0;
                            com.hippo.ehviewer.EhDB.putDownloadInfo(downloadInfo);

                            relayTaskManager.deleteTask(task.getTaskId());

                            Toast.makeText(TransferActivity.this,
                                    "取回成功！共 " + fileCount + " 个文件", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(TransferActivity.this,
                                    "取回失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onError(String error) {
                        Toast.makeText(TransferActivity.this,
                                "取回失败: " + error, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private ConnectedDevice findExecutorDevice(com.hippo.ehviewer.transfer.data.RelayTask task) {
        for (ConnectedDevice device : transferClientManager.getConnectedDevices()) {
            if (task.getTargetDeviceId() != null
                    && task.getTargetDeviceId().equals(device.getDeviceId())) {
                return device;
            }
            if (task.getTargetDevice() != null
                    && task.getTargetDevice().equals(device.getName())) {
                return device;
            }
        }
        return null;
    }

    private void deleteDir(java.io.File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            java.io.File[] children = file.listFiles();
            if (children != null) {
                for (java.io.File child : children) {
                    deleteDir(child);
                }
            }
        }
        file.delete();
    }

    private int copyFiles(java.io.File srcDir, com.hippo.unifile.UniFile destDir) throws java.io.IOException {
        int count = 0;
        java.io.File[] files = srcDir.listFiles();
        if (files == null) return 0;
        for (java.io.File srcFile : files) {
            if (srcFile.isFile()) {
                com.hippo.unifile.UniFile destFile = destDir.createFile("application/octet-stream");
                if (destFile != null) {
                    try (java.io.InputStream is = new java.io.FileInputStream(srcFile);
                         java.io.OutputStream os = destFile.openOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 删除接力任务
     */
    private void handleRelayDelete(com.hippo.ehviewer.transfer.data.RelayTask task) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.relay_delete_task)
                .setMessage("删除此接力任务？")
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    relayTaskManager.deleteTask(task.getTaskId());
                    Toast.makeText(this, R.string.relay_delete_task, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 清空已完成（终态）的接力任务
     */
    private void handleClearCompleted() {
        List<com.hippo.ehviewer.transfer.data.RelayTask> allTasks = relayTaskManager.getAllTasks();
        List<com.hippo.ehviewer.transfer.data.RelayTask> terminalTasks = new ArrayList<>();
        for (com.hippo.ehviewer.transfer.data.RelayTask task : allTasks) {
            if (task.isTerminal()) {
                terminalTasks.add(task);
            }
        }

        if (terminalTasks.isEmpty()) {
            Toast.makeText(this, "没有可清空的任务", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("清空已完成任务")
                .setMessage("确定要清空 " + terminalTasks.size() + " 个已完成的任务吗？")
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    for (com.hippo.ehviewer.transfer.data.RelayTask task : terminalTasks) {
                        relayTaskManager.deleteTask(task.getTaskId());
                    }
                    Toast.makeText(this, "已清空 " + terminalTasks.size() + " 个任务", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 切换日志区域展开/折叠
     */
    private void toggleLogSection() {
        isLogExpanded = !isLogExpanded;
        logListView.setVisibility(isLogExpanded ? View.VISIBLE : View.GONE);
        logExpandIcon.setText(isLogExpanded ? "▼" : "▶");
    }

    /**
     * 过滤日志
     */
    private void filterLogs(int position) {
        LogLevel minLevel;
        switch (position) {
            case 1: minLevel = LogLevel.DEBUG; break;
            case 2: minLevel = LogLevel.INFO; break;
            case 3: minLevel = LogLevel.WARN; break;
            case 4: minLevel = LogLevel.ERROR; break;
            default: minLevel = LogLevel.VERBOSE; break;
        }

        List<LogEntry> filteredLogs = transferLogger.getLogs(minLevel);
        logAdapter.setLogs(filteredLogs);
    }

    /**
     * 检查日志是否应该显示
     */
    private boolean shouldShowLog(LogEntry entry) {
        int position = logLevelSpinner.getSelectedItemPosition();
        LogLevel minLevel;
        switch (position) {
            case 1: minLevel = LogLevel.DEBUG; break;
            case 2: minLevel = LogLevel.INFO; break;
            case 3: minLevel = LogLevel.WARN; break;
            case 4: minLevel = LogLevel.ERROR; break;
            default: minLevel = LogLevel.VERBOSE; break;
        }
        return entry.getLevel().getPriority() >= minLevel.getPriority();
    }

    /**
     * 清空日志
     */
    private void clearLogs() {
        transferLogger.clear();
    }

    /**
     * 导出日志
     */
    private void exportLogs() {
        java.io.File logDir = new java.io.File("/sdcard/EhViewer/logs/");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault());
        String filename = "transfer_log_" + sdf.format(new java.util.Date()) + ".txt";
        java.io.File logFile = new java.io.File(logDir, filename);

        boolean success = transferLogger.exportToFile(logFile);

        if (success) {
            Toast.makeText(this, getString(R.string.log_exported, logFile.getAbsolutePath()), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.log_export_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void setupRefreshIntervalSpinner() {
        String[] intervals = {"30秒", "60秒", "120秒", "300秒"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, intervals);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        refreshIntervalSpinner.setAdapter(adapter);
        refreshIntervalSpinner.setSelection(1); // 默认60秒
    }

    private void setupServiceConnection() {
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                TransferService.TransferBinder binder = (TransferService.TransferBinder) service;
                transferService = binder.getService();
                isBound = true;
                transferService.registerCallback(TransferActivity.this);

                Log.d(TAG, "Service bound");
                updateUI();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                isBound = false;
                Log.d(TAG, "Service disconnected");
            }
        };
    }

    private void bindTransferService() {
        Intent intent = new Intent(this, TransferService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void startTransferService() {
        TransferLogger.getInstance().i(TAG, "startTransferService() 开始");

        Intent intent = new Intent(this, TransferService.class);
        startService(intent);

        if (isBound && transferService != null) {
            transferService.registerCallback(this);
        }

        Toast.makeText(this, R.string.transfer_service_started, Toast.LENGTH_SHORT).show();

        // Start device discovery
        deviceDiscoveryManager.startDiscovery();
        startAutoRefresh();

        // 首次启动时引导用户豁免电池优化（HyperOS/MIUI 后台限制严格）
        maybePromptBatteryOptimization();

        // 延迟更新UI，等待服务启动完成
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            updateUI();
        }, 500);
    }

    /**
     * 电池优化豁免引导（每次安装只自动提示一次）
     * HyperOS/MIUI 默认限制后台应用，未豁免时退后台后传输服务可能被限制
     */
    private void maybePromptBatteryOptimization() {
        try {
            android.content.SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
            boolean prompted = prefs.getBoolean("battery_optimization_prompted", false);
            if (prompted) {
                return;
            }
            if (!com.hippo.ehviewer.util.MiuiOptimizationHelper.INSTANCE.needsAggressiveOptimization()) {
                return;
            }
            if (com.hippo.ehviewer.util.MiuiOptimizationHelper.INSTANCE.isBatteryOptimizationExempted(this)) {
                return;
            }

            prefs.edit().putBoolean("battery_optimization_prompted", true).apply();

            new AlertDialog.Builder(this)
                    .setTitle(R.string.battery_optimization_title)
                    .setMessage(R.string.battery_optimization_message)
                    .setPositiveButton(R.string.battery_optimization_go, (dialog, which) ->
                            com.hippo.ehviewer.util.MiuiOptimizationHelper.INSTANCE
                                    .requestBatteryOptimizationExemption(this))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "电池优化引导失败", e);
        }
    }

    private void stopTransferService() {
        TransferLogger.getInstance().i(TAG, "stopTransferService() 开始");

        if (isBound && transferService != null) {
            // 通过Binder直接停止服务器：stopService()不会销毁仍有绑定客户端的服务，
            // 导致服务器实际仍在运行、启动按钮无法恢复
            transferService.stopTransferServer();
        } else {
            Intent intent = new Intent(this, TransferService.class);
            stopService(intent);
        }

        // Stop device discovery
        deviceDiscoveryManager.stopDiscovery();

        Toast.makeText(this, R.string.transfer_service_stopped, Toast.LENGTH_SHORT).show();
        updateUI();
    }

    private void startAutoRefresh() {
        int[] intervals = {30000, 60000, 120000, 300000};
        int selectedIndex = refreshIntervalSpinner.getSelectedItemPosition();
        if (selectedIndex >= 0 && selectedIndex < intervals.length) {
            deviceDiscoveryManager.startAutoRefresh(intervals[selectedIndex]);
        }
    }

    private void showRemoteSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_remote_settings, null);

        // Initialize dialog components
        android.widget.RadioGroup authModeGroup = dialogView.findViewById(R.id.auth_mode_group);
        android.widget.Switch localBypassSwitch = dialogView.findViewById(R.id.local_bypass_switch);

        // Read current settings
        String currentMode = Settings.getRemoteAuthMode();
        boolean localBypass = Settings.isLocalBypassEnabled();

        // Set initial values
        switch (currentMode) {
            case "none":
                authModeGroup.check(R.id.auth_none);
                break;
            case "password":
                authModeGroup.check(R.id.auth_password);
                break;
            case "token":
                authModeGroup.check(R.id.auth_token);
                break;
        }
        localBypassSwitch.setChecked(localBypass);

        builder.setView(dialogView)
                .setTitle(R.string.remote_settings)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    // Save settings
                    String newMode = getSelectedAuthMode(authModeGroup);
                    boolean newBypass = localBypassSwitch.isChecked();

                    Settings.putRemoteAuthMode(newMode);
                    Settings.putLocalBypassEnabled(newBypass);

                    // Update UI
                    updateRemoteAccessInfo();

                    Toast.makeText(this, R.string.settings_restart_required, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String getSelectedAuthMode(android.widget.RadioGroup group) {
        int checkedId = group.getCheckedRadioButtonId();
        if (checkedId == R.id.auth_none) return "none";
        if (checkedId == R.id.auth_password) return "password";
        if (checkedId == R.id.auth_token) return "token";
        return "none";
    }

    private void updateUI() {
        TransferLogger.getInstance().d(TAG, "updateUI() 开始");
        boolean isServiceRunning = isServiceRunning();
        TransferLogger.getInstance().d(TAG, "服务状态: " + (isServiceRunning ? "运行中" : "未运行"));

        startButton.setEnabled(!isServiceRunning);
        stopButton.setEnabled(isServiceRunning);

        if (isServiceRunning && isBound && transferService != null) {
            List<ClientInfo> clients = transferService.getConnectedClients();
            updateClientList(clients);
            clientCountText.setText(getString(R.string.connected_clients_count, clients.size()));
            statusText.setText(R.string.transfer_service_running);
            loadingBar.setVisibility(View.GONE);

            // Update address list
            updateAddressList();

            // Update remote access info
            updateRemoteAccessInfo();
        } else {
            clientCountText.setText(getString(R.string.connected_clients_count, 0));
            statusText.setText(R.string.transfer_service_not_running);
            loadingBar.setVisibility(View.VISIBLE);
            remoteAccessInfo.setText(R.string.remote_access_info_default);
        }
        TransferLogger.getInstance().d(TAG, "updateUI() 完成");
    }

    private void updateAddressList() {
        TransferLogger.getInstance().d(TAG, "updateAddressList() 开始");
        
        List<NetworkAddress> addresses = NetworkUtils.getAllNetworkAddresses();
        TransferLogger.getInstance().d(TAG, "获取到 " + addresses.size() + " 个地址");
        
        for (NetworkAddress addr : addresses) {
            TransferLogger.getInstance().d(TAG, "  " + addr.getDisplayName() + ": " + addr.getIpAddress());
        }
        
        int port = transferService != null ? transferService.getServerManager().getPort() : 8080;
        TransferLogger.getInstance().d(TAG, "端口: " + port);
        
        addressAdapter.setPort(port);
        addressAdapter.setAddresses(addresses);
        
        TransferLogger.getInstance().d(TAG, "updateAddressList() 完成");
    }

    private void updateRemoteAccessInfo() {
        if (!isBound || transferService == null) {
            return;
        }

        String ipAddress = NetworkUtils.getWifiIpAddress();
        int port = transferService.getServerManager().getPort();
        String authMode = Settings.getRemoteAuthMode();

        StringBuilder info = new StringBuilder();
        info.append(getString(R.string.access_address)).append(" http://").append(ipAddress).append(":").append(port).append("\n");
        info.append(getString(R.string.auth_mode)).append(": ").append(getAuthModeName(authMode)).append("\n");

        if ("password".equals(authMode)) {
            String password = transferService.getServerManager().getGeneratedPassword();
            info.append(getString(R.string.password)).append(": ").append(password).append("\n");
        } else if ("token".equals(authMode)) {
            String token = transferService.getServerManager().getGeneratedToken();
            info.append(getString(R.string.token_label)).append(": ").append(token).append("\n");
        }

        info.append("\n").append(getString(R.string.remote_access_hint));

        remoteAccessInfo.setText(info.toString());
    }

    private void updateRemoteManagementUI(boolean enabled) {
        if (remoteManagementContent != null) {
            remoteManagementContent.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        if (remoteOpenBrowser != null) {
            remoteOpenBrowser.setEnabled(enabled);
        }
        if (remoteCopyUrl != null) {
            remoteCopyUrl.setEnabled(enabled);
        }
    }

    private String getAuthModeName(String mode) {
        switch (mode) {
            case "password":
                return getString(R.string.auth_mode_password);
            case "token":
                return getString(R.string.auth_mode_token);
            default:
                return getString(R.string.auth_mode_none);
        }
    }

    private void openRemoteInBrowser() {
        String ipAddress = NetworkUtils.getWifiIpAddress();
        int port = transferService != null ? transferService.getServerManager().getPort() : 8080;
        String url = "http://" + ipAddress + ":" + port;

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.cannot_open_browser, Toast.LENGTH_SHORT).show();
        }
    }

    private void copyRemoteUrl() {
        String ipAddress = NetworkUtils.getWifiIpAddress();
        int port = transferService != null ? transferService.getServerManager().getPort() : 8080;
        String url = "http://" + ipAddress + ":" + port;

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Remote URL", url);
        clipboard.setPrimaryClip(clip);

        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void sendAll(String type) {
        TransferLogger.getInstance().i(TAG, "sendAll: " + type);
        
        // 检查是否有已连接设备
        if (transferClientManager == null || transferClientManager.getConnectedDevices().isEmpty()) {
            Toast.makeText(this, R.string.no_connected_devices, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // TODO: 实现发送全部逻辑
        Toast.makeText(this, "发送全部: " + type, Toast.LENGTH_SHORT).show();
    }

    private void openDataSelector(String type) {
        TransferLogger.getInstance().i(TAG, "openDataSelector: " + type);
        
        // 检查是否有已连接设备
        if (transferClientManager == null || transferClientManager.getConnectedDevices().isEmpty()) {
            Toast.makeText(this, R.string.no_connected_devices, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 打开DataSelectorActivity
        Intent intent = DataSelectorActivity.createIntent(this, type);
        dataSelectorLauncher.launch(intent);
    }

    /**
     * 处理从DataSelectorActivity返回的选择结果
     */
    private void handleSelectedData(String type, List<Long> selectedGids) {
        TransferLogger.getInstance().i(TAG, 
                String.format("handleSelectedData: type=%s, count=%d", type, selectedGids.size()));
        
        // 获取已连接设备列表
        List<ConnectedDevice> devices = transferClientManager.getConnectedDevices();
        if (devices.isEmpty()) {
            Toast.makeText(this, R.string.no_connected_devices, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 选择目标设备（使用第一个已连接设备）
        ConnectedDevice targetDevice = devices.get(0);
        TransferLogger.getInstance().d(TAG, "目标设备: " + targetDevice.getName());
        
        // 创建推送任务
        // TODO: 调用DataPushManager创建推送任务
        Toast.makeText(this, 
                String.format("发送 %d 个%s到 %s", selectedGids.size(), type, targetDevice.getName()), 
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 全部传输：导出数据库并推送到目标设备，接收端按导入数据处理
     */
    private void sendAllData() {
        TransferLogger.getInstance().i(TAG, "sendAllData");

        // 检查是否有已连接设备
        if (transferClientManager == null || transferClientManager.getConnectedDevices().isEmpty()) {
            Toast.makeText(this, R.string.no_connected_devices, Toast.LENGTH_SHORT).show();
            return;
        }

        List<ConnectedDevice> devices = transferClientManager.getConnectedDevices();
        if (devices.size() == 1) {
            confirmSendAllData(devices.get(0));
        } else {
            // 多台设备时弹选择对话框
            String[] names = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                names[i] = devices.get(i).getName()
                        + " (" + devices.get(i).getHost() + ":" + devices.get(i).getPort() + ")";
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.select_target_device)
                    .setItems(names, (dialog, which) -> confirmSendAllData(devices.get(which)))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    /**
     * 发送前确认：接收端将执行数据库导入（合并）
     */
    private void confirmSendAllData(ConnectedDevice device) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.send_all_data)
                .setMessage(getString(R.string.send_all_data_confirm, device.getName()))
                .setPositiveButton(R.string.send_all_data, (dialog, which) -> doSendAllData(device))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 执行全部传输：后台线程导出DB -> 上传 -> 清理临时文件
     */
    private void doSendAllData(ConnectedDevice device) {
        sendAllData.setEnabled(false);
        Toast.makeText(this, R.string.send_all_data_exporting, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            java.io.File exportFile = new java.io.File(getCacheDir(),
                    "transfer_export_" + System.currentTimeMillis() + ".db");
            boolean exported;
            try {
                exported = EhDB.exportDB(this, exportFile);
            } catch (Exception e) {
                TransferLogger.getInstance().e(TAG, "导出数据库失败", e);
                exported = false;
            }

            if (!exported || !exportFile.exists()) {
                exportFile.delete();
                runOnUiThread(() -> {
                    sendAllData.setEnabled(true);
                    Toast.makeText(this, getString(R.string.send_all_data_failed, "export failed"),
                            Toast.LENGTH_LONG).show();
                });
                return;
            }

            runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.send_all_data_sending, device.getName()),
                    Toast.LENGTH_SHORT).show());

            transferClientManager.pushDatabase(device, exportFile,
                    new TransferClientManager.PushDatabaseCallback() {
                        @Override
                        public void onSuccess(String message) {
                            exportFile.delete();
                            TransferLogger.getInstance().i(TAG, "全部传输成功: " + message);
                            runOnUiThread(() -> {
                                sendAllData.setEnabled(true);
                                Toast.makeText(TransferActivity.this,
                                        R.string.send_all_data_success, Toast.LENGTH_LONG).show();
                            });
                        }

                        @Override
                        public void onError(String error) {
                            exportFile.delete();
                            TransferLogger.getInstance().e(TAG, "全部传输失败: " + error);
                            runOnUiThread(() -> {
                                sendAllData.setEnabled(true);
                                Toast.makeText(TransferActivity.this,
                                        getString(R.string.send_all_data_failed, error),
                                        Toast.LENGTH_LONG).show();
                            });
                        }
                    });
        }).start();
    }

    private void connectToDevice() {
        String address = addressInput.getText().toString().trim();
        if (address.isEmpty()) {
            Toast.makeText(this, R.string.manual_connect_hint, Toast.LENGTH_SHORT).show();
            return;
        }

        // Parse address
        String[] parts = address.split(":");
        if (parts.length != 2) {
            Toast.makeText(this, R.string.invalid_address, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            // TODO: Connect using TransferClientManager
            Toast.makeText(this, "Connecting to " + address, Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.invalid_address, Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshDevices() {
        if (deviceDiscoveryManager != null) {
            deviceDiscoveryManager.refreshDevices();
            Toast.makeText(this, R.string.refreshing, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateDiscoveredDevices(List<DiscoveredDevice> devices) {
        // TODO: Update discovered devices adapter
        Log.d(TAG, "Discovered devices: " + devices.size());
    }

    private void updateClientList(List<ClientInfo> clients) {
        clientList.clear();
        clientList.addAll(clients);
        clientAdapter.notifyDataSetChanged();
    }

    private boolean isServiceRunning() {
        if (!isBound || transferService == null) {
            return false;
        }
        return transferService.getServerManager().isRunning();
    }

    @Override
    public void onClientsChanged(List<ClientInfo> clients) {
        runOnUiThread(() -> {
            clientList.clear();
            clientList.addAll(clients);
            clientAdapter.notifyDataSetChanged();
            clientCountText.setText(getString(R.string.connected_clients_count, clients.size()));
        });
    }

    @Override
    public void onTransferStatusChanged(String message) {
        runOnUiThread(() -> {
            statusText.setText(message);
            Toast.makeText(TransferActivity.this, message, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> {
            statusText.setText(getString(R.string.error_prefix, error));
            Toast.makeText(TransferActivity.this, error, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound && transferService != null) {
            transferService.unregisterCallback();
            unbindService(serviceConnection);
            isBound = false;
        }
        if (deviceDiscoveryManager != null) {
            deviceDiscoveryManager.release();
        }
    }

    /**
     * 客户端列表适配器
     */
    public static class ClientListAdapter extends RecyclerView.Adapter<ClientViewHolder> {
        private Context context;
        private List<ClientInfo> clients;

        public ClientListAdapter(Context context, List<ClientInfo> clients) {
            this.context = context;
            this.clients = clients;
        }

        @NonNull
        @Override
        public ClientViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_transfer_client, parent, false);
            return new ClientViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ClientViewHolder holder, int position) {
            ClientInfo client = clients.get(position);
            holder.bind(client);
        }

        @Override
        public int getItemCount() {
            return clients.size();
        }
    }

    /**
     * 客户端列表项ViewHolder
     */
    public static class ClientViewHolder extends RecyclerView.ViewHolder {
        private TextView deviceNameText;
        private TextView ipAddressText;
        private TextView connectionTimeText;
        private TextView transferStatusText;
        private ProgressBar transferProgressBar;

        public ClientViewHolder(@NonNull View itemView) {
            super(itemView);
            deviceNameText = itemView.findViewById(R.id.client_device_name);
            ipAddressText = itemView.findViewById(R.id.client_ip_address);
            connectionTimeText = itemView.findViewById(R.id.client_connection_time);
            transferStatusText = itemView.findViewById(R.id.client_transfer_status);
            transferProgressBar = itemView.findViewById(R.id.client_transfer_progress);
        }

        public void bind(ClientInfo client) {
            deviceNameText.setText(client.getDeviceName());
            ipAddressText.setText(client.getIpAddress() + ":" + client.getPort());

            long connectedTime = System.currentTimeMillis() - client.getConnectedTime();
            String timeStr = formatTime(connectedTime);
            connectionTimeText.setText(timeStr);

            ClientInfo.TransferStatus status = client.getTransferStatus();
            transferStatusText.setText(status.getStatus());
            transferProgressBar.setProgress(status.getProgressPercent());
        }

        private String formatTime(long millis) {
            long seconds = millis / 1000;
            long minutes = seconds / 60;
            long hours = minutes / 60;

            if (hours > 0) {
                return hours + "小时" + (minutes % 60) + "分钟";
            } else if (minutes > 0) {
                return minutes + "分钟";
            } else {
                return seconds + "秒";
            }
        }
    }
}
