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

    // Send data
    private Button sendBookmarksAll;
    private Button sendBookmarksSelect;
    private Button sendDownloadsAll;
    private Button sendDownloadsSelect;
    private Button sendFavoritesAll;
    private Button sendFavoritesSelect;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);

        initializeUI();
        setupServiceConnection();
        bindTransferService();
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

        // Send data
        sendBookmarksAll = findViewById(R.id.send_bookmarks_all);
        sendBookmarksSelect = findViewById(R.id.send_bookmarks_select);
        sendDownloadsAll = findViewById(R.id.send_downloads_all);
        sendDownloadsSelect = findViewById(R.id.send_downloads_select);
        sendFavoritesAll = findViewById(R.id.send_favorites_all);
        sendFavoritesSelect = findViewById(R.id.send_favorites_select);

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

        connectButton.setOnClickListener(v -> connectToDevice());
        refreshDevicesButton.setOnClickListener(v -> refreshDevices());

        // Initialize device discovery
        deviceDiscoveryManager = new DeviceDiscoveryManager(this);
        deviceDiscoveryManager.addListener(devices -> {
            runOnUiThread(() -> updateDiscoveredDevices(devices));
        });

        // Initialize transfer client manager
        transferClientManager = new TransferClientManager(this);

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

        // 延迟更新UI，等待服务启动完成
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            updateUI();
        }, 500);
    }

    private void stopTransferService() {
        Intent intent = new Intent(this, TransferService.class);
        stopService(intent);

        if (isBound && transferService != null) {
            transferService.unregisterCallback();
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
