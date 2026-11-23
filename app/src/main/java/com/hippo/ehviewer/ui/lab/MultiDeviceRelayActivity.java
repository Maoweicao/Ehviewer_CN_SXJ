package com.hippo.ehviewer.ui.lab;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.LabConfig;
import com.hippo.ehviewer.lab.LabConfigStore;
import com.hippo.ehviewer.lab.LabManager;
import com.hippo.ehviewer.lab.PairingCodeManager;
import com.hippo.ehviewer.lab.TransferPortHelper;
import com.hippo.ehviewer.lab.TrustedPeer;
import com.hippo.ehviewer.lab.TrustedPeerStore;
import com.hippo.ehviewer.lab.pair.LabPairingClient;
import com.hippo.ehviewer.lab.pair.LanPeerScanner;
import com.hippo.ehviewer.lab.pair.PairingQrCodec;
import com.hippo.ehviewer.lab.source.LanImageFetcher;
import com.hippo.ehviewer.lab.sync.LabBackfillResumer;
import com.hippo.ehviewer.lab.sync.LabBackfillStore;
import com.hippo.ehviewer.lab.sync.LabSyncDirection;
import com.hippo.ehviewer.lab.sync.LabTasks;
import com.hippo.ehviewer.transfer.TransferService;
import com.hippo.ehviewer.transfer.core.DeviceDiscoveryManager;
import com.hippo.ehviewer.transfer.core.NetworkUtils;
import com.hippo.ehviewer.transfer.core.QrUtils;
import com.hippo.ehviewer.transfer.data.DiscoveredDevice;
import com.hippo.ehviewer.transfer.data.NetworkAddress;
import com.hippo.ehviewer.ui.ToolbarActivity;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MultiDeviceRelayActivity extends ToolbarActivity
        implements TrustedPeerAdapter.OnPeerActionListener {

    private static final String SUB_AUTO_RELAY = "autoRelay";
    private static final String SUB_CROSS_DEVICE_IMAGE = "crossDeviceImage";
    private static final String SUB_DB_SNAPSHOT = "dbSnapshot";
    private static final String SUB_INCREMENTAL_RESUME = "incrementalResume";

    private static final int NAV_DISCOVERY_INTERVAL_MS = 5_000;
    private static final long NEARBY_HINT_DELAY_MS = 12_000L;
    private static final long AUTO_SYNC_INTERVAL_MS = 60_000L;
    private static final long AUTO_SYNC_FIRST_DELAY_MS = 3_000L;
    private static final long PENDING_RESUME_DELAY_MS = 6_000L;

    private LabManager labManager;
    private LabConfigStore configStore;
    private TrustedPeerStore peerStore;

    private TextView selfName;
    private TextView selfId;
    private TextView selfCaps;
    private SwitchCompat switchEnabled;
    private SwitchCompat switchAutoRelay;
    private SwitchCompat switchCrossDeviceImage;
    private SwitchCompat switchDbSnapshot;
    private SwitchCompat switchIncrementalResume;
    private TextView emptyText;
    private RecyclerView recyclerView;
    private TrustedPeerAdapter adapter;
    private Button btnAddPeer;
    private Button btnRefreshStatus;
    private Button btnGenerateCode;
    private Button btnShowQr;
    private Button btnScanPair;
    private TextView pairSelfAddress;
    private TextView pairCodeText;
    private TextView pairCodeTtl;

    private DeviceDiscoveryManager nearbyDiscovery;
    private NearbyDeviceAdapter nearbyAdapter;
    private RecyclerView nearbyList;
    private TextView nearbyEmpty;
    private TextView nearbyApHint;
    private Button btnScanNearby;
    private LanPeerScanner lanScanner;
    private List<DiscoveredDevice> mdnsDevices = new ArrayList<>();
    private List<DiscoveredDevice> scanDevices = new ArrayList<>();
    private final Set<String> pairedAddresses = new HashSet<>();
    private final Set<String> localIps = new HashSet<>();
    private boolean sawAnyNearby = false;
    private boolean scanningNearby = false;

    private final Handler nearbyHandler = new Handler(Looper.getMainLooper());

    private final DeviceDiscoveryManager.DiscoveryListener discoveryListener =
            devices -> updateNearbyDevices(devices);

    private final Runnable nearbyHintRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (nearbyAdapter != null && nearbyAdapter.getItemCount() == 0) {
                nearbyEmpty.setText(R.string.multi_device_nearby_none);
                nearbyApHint.setVisibility(View.VISIBLE);
            }
        }
    };

    /** 页面可见时每 60 秒做一次增量元数据同步，保证列表及时更新。 */
    private final Runnable autoSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            try {
                if (labManager.isLabEnabled(SUB_DB_SNAPSHOT)) {
                    LabSyncDirection direction = readSyncDirection();
                    // 自动同步同样提交为后台任务，失败/完成都能在「后台任务」里看到。
                    LabTasks.autoSync(getApplicationContext(), direction);
                }
            } catch (Throwable ignored) {
            }
            nearbyHandler.postDelayed(this, AUTO_SYNC_INTERVAL_MS);
        }
    };

    /** 自动续传上次未完成的图片补齐。 */
    private final Runnable pendingResumeRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (LabBackfillResumer.isRunning()) return;
            int pending = LabBackfillStore.pending(MultiDeviceRelayActivity.this).size();
            if (pending == 0) return;
            Toast.makeText(MultiDeviceRelayActivity.this,
                    getString(R.string.lab_sync_backfill_resume, pending),
                    Toast.LENGTH_SHORT).show();
            LabBackfillResumer.resumeIfPossible(MultiDeviceRelayActivity.this, null);
        }
    };

    private boolean bindingSwitches = false;

    private String currentPairCode;
    private long currentPairCodeExpiresAt;

    private final Handler pairHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<ScanOptions> qrScanLauncher = registerForActivityResult(
            new ScanContract(),
            result -> {
                if (result.getContents() != null) {
                    handlePairQrScanResult(result.getContents());
                }
            });

    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            if (currentPairCode == null) {
                return;
            }
            long remain = currentPairCodeExpiresAt - System.currentTimeMillis();
            if (remain <= 0) {
                currentPairCode = null;
                pairCodeText.setText(R.string.multi_device_pair_code_idle);
                pairCodeTtl.setText(R.string.multi_device_pair_code_expired);
                btnShowQr.setEnabled(false);
                return;
            }
            pairCodeTtl.setText(getString(R.string.multi_device_pair_code_ttl,
                    (int) ((remain + 999) / 1000)));
            pairHandler.postDelayed(this, 1000);
        }
    };

    private final TrustedPeerStore.Listener peerListener = new TrustedPeerStore.Listener() {
        @Override
        public void onPeerAdded(TrustedPeer peer) {
            refreshPeers();
        }

        @Override
        public void onPeerUpdated(TrustedPeer oldPeer, TrustedPeer newPeer) {
            refreshPeers();
        }

        @Override
        public void onPeerRemoved(TrustedPeer peer) {
            refreshPeers();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_multi_device_relay);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.lab_relay_center);
        }
        setNavigationIcon(R.drawable.ic_back);

        labManager = LabManager.getInstance(this);
        configStore = LabConfigStore.getInstance(this);
        peerStore = TrustedPeerStore.getInstance(this);

        selfName = findViewById(R.id.self_name);
        selfId = findViewById(R.id.self_id);
        selfCaps = findViewById(R.id.self_caps);
        switchEnabled = findViewById(R.id.switch_enabled);
        switchAutoRelay = findViewById(R.id.switch_auto_relay);
        switchCrossDeviceImage = findViewById(R.id.switch_cross_device_image);
        switchDbSnapshot = findViewById(R.id.switch_db_snapshot);
        switchIncrementalResume = findViewById(R.id.switch_incremental_resume);
        emptyText = findViewById(R.id.empty_text);
        recyclerView = findViewById(R.id.recycler_view);
        btnAddPeer = findViewById(R.id.btn_add_peer);
        btnRefreshStatus = findViewById(R.id.btn_refresh_status);
        btnGenerateCode = findViewById(R.id.btn_generate_code);
        btnShowQr = findViewById(R.id.btn_show_qr);
        btnScanPair = findViewById(R.id.btn_scan_pair);
        pairSelfAddress = findViewById(R.id.pair_self_address);
        pairCodeText = findViewById(R.id.tv_pair_code);
        pairCodeTtl = findViewById(R.id.tv_pair_code_ttl);

        btnAddPeer.setOnClickListener(v -> showAddPeerDialog());
        btnRefreshStatus.setOnClickListener(v -> refreshStatus());
        btnGenerateCode.setOnClickListener(v -> generatePairCode());
        btnShowQr.setOnClickListener(v -> showPairQrDialog());
        btnScanPair.setOnClickListener(v -> startPairScan());

        nearbyList = findViewById(R.id.nearby_list);
        nearbyEmpty = findViewById(R.id.nearby_empty);
        nearbyApHint = findViewById(R.id.nearby_ap_hint);
        btnScanNearby = findViewById(R.id.btn_scan_nearby);
        nearbyAdapter = new NearbyDeviceAdapter();
        nearbyAdapter.setOnNearbyDeviceListener(this::onNearbyDevice);
        nearbyList.setLayoutManager(new LinearLayoutManager(this));
        nearbyList.setAdapter(nearbyAdapter);
        nearbyDiscovery = new DeviceDiscoveryManager(this);
        nearbyDiscovery.addListener(discoveryListener);
        lanScanner = LanPeerScanner.getInstance(this);
        btnScanNearby.setOnClickListener(v -> runLanScan());

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TrustedPeerAdapter();
        adapter.setOnPeerActionListener(this);
        recyclerView.setAdapter(adapter);

        bindIdentity();
        bindSwitches();
        updateSelfAddress();

        switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> onSwitchChanged());
        switchAutoRelay.setOnCheckedChangeListener((buttonView, isChecked) -> onSwitchChanged());
        switchCrossDeviceImage.setOnCheckedChangeListener((buttonView, isChecked) -> onSwitchChanged());
        switchDbSnapshot.setOnCheckedChangeListener((buttonView, isChecked) -> onSwitchChanged());
        switchIncrementalResume.setOnCheckedChangeListener((buttonView, isChecked) -> onSwitchChanged());

        peerStore.addListener(peerListener);
        refreshPeers();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 配对/被发现都依赖本机 8080 服务在监听：进入本页即确保服务已启动，
        // 避免「对方局域网正常，但本机服务没开」导致的连接失败。
        ensureTransferServiceRunning();
        // 仅在页面可见时轮询，避免后台耗电
        LanImageFetcher.getInstance(this).startRttProbe();

        // mDNS 自动发现同一局域网设备
        if (nearbyDiscovery != null) {
            nearbyDiscovery.startDiscovery();
            nearbyDiscovery.startAutoRefresh(NAV_DISCOVERY_INTERVAL_MS);
        }
        nearbyEmpty.setText(R.string.multi_device_nearby_searching);
        nearbyApHint.setVisibility(View.GONE);
        nearbyHandler.removeCallbacks(nearbyHintRunnable);
        nearbyHandler.postDelayed(nearbyHintRunnable, NEARBY_HINT_DELAY_MS);
        nearbyHandler.postDelayed(this::runLanScan, 1_000L);
        nearbyHandler.postDelayed(autoSyncRunnable, AUTO_SYNC_FIRST_DELAY_MS);
        nearbyHandler.postDelayed(pendingResumeRunnable, PENDING_RESUME_DELAY_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        LanImageFetcher.getInstance(this).stopRttProbe();
        nearbyHandler.removeCallbacks(nearbyHintRunnable);
        nearbyHandler.removeCallbacksAndMessages(null);
        if (nearbyDiscovery != null) {
            nearbyDiscovery.stopDiscovery();
            nearbyDiscovery.stopAutoRefresh();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSelfAddress();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pairHandler.removeCallbacksAndMessages(null);
        nearbyHandler.removeCallbacksAndMessages(null);
        if (nearbyDiscovery != null) {
            nearbyDiscovery.removeListener(discoveryListener);
            nearbyDiscovery.release();
        }
        peerStore.removeListener(peerListener);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.multi_device_relay, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_lab_sync) {
            LabSyncDialog.show(this);
            return true;
        }
        if (id == R.id.action_check_local) {
            checkLocalFiles();
            return true;
        }
        if (id == R.id.action_stop_backfill) {
            LabBackfillResumer.stop();
            Toast.makeText(this, R.string.multi_device_stop_backfill_done,
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        if (id == R.id.action_clear_peers) {
            confirmClearAllPeers();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ==================== 本机身份 ====================

    private void bindIdentity() {
        selfName.setText(labManager.getSelfDeviceName());
        selfId.setText(getString(R.string.multi_device_self_id, labManager.getSelfDeviceId()));
        selfCaps.setText(getString(R.string.multi_device_self_caps,
                Arrays.toString(labManager.getSelfCapabilities())));
    }

    private void updateSelfAddress() {
        if (pairSelfAddress == null) return;
        localIps.clear();
        for (NetworkAddress address : NetworkUtils.getAllNetworkAddresses()) {
            if (address.getIpAddress() != null) localIps.add(address.getIpAddress());
        }
        String host = NetworkUtils.getWifiIpAddress();
        if (host == null || host.isEmpty() || "0.0.0.0".equals(host)) {
            pairSelfAddress.setText(R.string.no_wifi_address);
            return;
        }
        int port = TransferPortHelper.getPort(this);
        pairSelfAddress.setText(getString(R.string.multi_device_pair_self_address,
                host + ":" + port));
    }

    // ==================== 实验室开关 ====================

    private void bindSwitches() {
        bindingSwitches = true;
        try {
            LabConfig cfg = configStore.get();
            switchEnabled.setChecked(cfg.isEnabled());
            switchAutoRelay.setChecked(cfg.isSubEnabled(SUB_AUTO_RELAY));
            switchCrossDeviceImage.setChecked(cfg.isSubEnabled(SUB_CROSS_DEVICE_IMAGE));
            switchDbSnapshot.setChecked(cfg.isSubEnabled(SUB_DB_SNAPSHOT));
            switchIncrementalResume.setChecked(cfg.isSubEnabled(SUB_INCREMENTAL_RESUME));
        } finally {
            bindingSwitches = false;
        }
    }

    private void onSwitchChanged() {
        if (bindingSwitches) return;
        configStore.update(new LabConfigStore.Updater() {
            @Override
            public LabConfig apply(LabConfig current) {
                return current.toBuilder()
                        .enabled(switchEnabled.isChecked())
                        .subSwitches(buildSubSwitches())
                        .build();
            }
        });
    }

    private LabConfig.SubSwitches buildSubSwitches() {
        boolean autoRelay = switchAutoRelay.isChecked();
        boolean crossDeviceImage = switchCrossDeviceImage.isChecked();
        boolean dbSnapshot = switchDbSnapshot.isChecked();
        boolean incrementalResume = switchIncrementalResume.isChecked();
        return new LabConfig.SubSwitches.Builder()
                .autoRelay(autoRelay)
                .crossDeviceImage(crossDeviceImage)
                .dbSnapshot(dbSnapshot)
                .incrementalResume(incrementalResume)
                .build();
    }

    // ==================== 配对码与二维码 ====================

    private void generatePairCode() {
        ensureTransferServiceRunning();
        PairingCodeManager.PairingIssue issue = labManager.getPairingManager()
                .issue(labManager.getSelfDeviceName());
        currentPairCode = issue.getCode();
        currentPairCodeExpiresAt = issue.getExpiresAt();
        pairCodeText.setText(formatPairCode(currentPairCode));
        btnShowQr.setEnabled(true);
        pairHandler.removeCallbacks(countdownTick);
        pairHandler.post(countdownTick);
    }

    private String formatPairCode(String code) {
        if (code == null || code.length() != 6) return "------";
        return code.substring(0, 3) + " " + code.substring(3);
    }

    private void showPairQrDialog() {
        if (currentPairCode == null || System.currentTimeMillis() > currentPairCodeExpiresAt) {
            generatePairCode();
        }
        if (currentPairCode == null) {
            return;
        }
        String host = NetworkUtils.getWifiIpAddress();
        if (host == null || host.isEmpty() || "0.0.0.0".equals(host)) {
            Toast.makeText(this, R.string.no_wifi_address, Toast.LENGTH_SHORT).show();
            return;
        }
        int port = TransferPortHelper.getPort(this);
        String content;
        try {
            content = PairingQrCodec.encode(host, port, currentPairCode,
                    labManager.getSelfDeviceName());
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, R.string.multi_device_pair_invalid_qr, Toast.LENGTH_SHORT).show();
            return;
        }

        final int sizePx = getResources().getDimensionPixelSize(R.dimen.qr_code_size);
        final Bitmap qrBitmap = QrUtils.generateQrBitmap(content, sizePx);
        if (qrBitmap == null) {
            Toast.makeText(this, R.string.multi_device_pair_invalid_qr, Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_show_pair_qr, null);
        ImageView qrImage = dialogView.findViewById(R.id.qr_image);
        TextView qrHint = dialogView.findViewById(R.id.qr_hint);
        TextView qrAddress = dialogView.findViewById(R.id.qr_address);
        qrImage.setImageBitmap(qrBitmap);
        qrHint.setText(R.string.multi_device_pair_qr_hint);
        qrAddress.setText(getString(R.string.multi_device_pair_self_address,
                host + ":" + port));

        new AlertDialog.Builder(this)
                .setTitle(R.string.multi_device_pair_qr_title)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ==================== 扫码配对 ====================

    private void startPairScan() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt(getString(R.string.multi_device_pair_scan_prompt));
        options.setBeepEnabled(false);
        qrScanLauncher.launch(options);
    }

    private void handlePairQrScanResult(String content) {
        PairingQrCodec.Params params = PairingQrCodec.decode(content);
        if (params == null) {
            Toast.makeText(this, R.string.multi_device_pair_invalid_qr, Toast.LENGTH_SHORT).show();
            return;
        }
        String label = params.getName() != null ? params.getName() : params.getHost();
        String address = params.getHost() + ":" + params.getPort();
        new AlertDialog.Builder(this)
                .setTitle(R.string.multi_device_pair_confirm_title)
                .setMessage(getString(R.string.multi_device_pair_confirm_message, label, address))
                .setPositiveButton(R.string.multi_device_pair_confirm,
                        (d, w) -> startPairing(params.getHost(), params.getPort(), params.getCode()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startPairing(String host, int port, String code) {
        // 让对端能反向访问本机：配对成功后双方都要有可用地址
        ensureTransferServiceRunning();
        setPairingBusy(true);
        Toast.makeText(this, R.string.multi_device_pair_running, Toast.LENGTH_SHORT).show();
        LabPairingClient.getInstance(this).pair(host, port, code, result -> {
            if (isFinishing() || isDestroyed()) return;
            setPairingBusy(false);
            if (!result.getSuccess()) {
                if (result.getError() == LabPairingClient.PairError.UNREACHABLE) {
                    showConnectFailureDialog(host + ":" + port);
                } else {
                    Toast.makeText(this, pairErrorMessage(result), Toast.LENGTH_LONG).show();
                }
                return;
            }
            TrustedPeer peer = result.getPeer();
            String name = peer != null ? peer.getDeviceName() : host;
            Toast.makeText(this, getString(R.string.multi_device_pair_success, name),
                    Toast.LENGTH_SHORT).show();
            refreshPeers();
        });
    }

    private String pairErrorMessage(LabPairingClient.PairResult result) {
        LabPairingClient.PairError error = result.getError();
        String reason;
        if (error == null) {
            reason = result.getMessage();
        } else {
            switch (error) {
                case UNREACHABLE:
                    reason = getString(R.string.multi_device_pair_unreachable);
                    break;
                case INVALID_CODE:
                    reason = getString(R.string.multi_device_pair_invalid_code);
                    break;
                case EXPIRED:
                    reason = getString(R.string.multi_device_pair_expired);
                    break;
                case REJECTED:
                    reason = getString(R.string.multi_device_pair_rejected);
                    break;
                case RATE_LIMITED:
                    reason = getString(R.string.multi_device_pair_rate_limited);
                    break;
                case BAD_RESPONSE:
                    reason = getString(R.string.multi_device_pair_bad_response);
                    break;
                case SELF:
                    reason = getString(R.string.multi_device_pair_self);
                    break;
                default:
                    reason = result.getMessage();
                    break;
            }
        }
        if (reason == null || reason.isEmpty()) {
            reason = getString(R.string.multi_device_pair_bad_response);
        }
        return getString(R.string.multi_device_pair_failed, reason);
    }

    /**
     * 附近设备列表（mDNS）。
     */
    private void updateNearbyDevices(List<DiscoveredDevice> devices) {
        mdnsDevices = devices != null ? new ArrayList<>(devices) : new ArrayList<>();
        applyNearbyDevices();
    }

    /**
     * 手动扫描局域网（mDNS 不可用时的兜底）。
     */
    private void runLanScan() {
        if (isFinishing() || isDestroyed() || scanningNearby || lanScanner == null) return;
        scanningNearby = true;
        btnScanNearby.setEnabled(false);
        nearbyEmpty.setText(R.string.multi_device_nearby_scanning);
        nearbyEmpty.setVisibility(View.VISIBLE);
        lanScanner.scan(devices -> {
            scanningNearby = false;
            if (isFinishing() || isDestroyed()) return;
            btnScanNearby.setEnabled(true);
            scanDevices = devices != null ? new ArrayList<>(devices) : new ArrayList<>();
            applyNearbyDevices();
        });
    }

    /**
     * 合并 mDNS 与局域网扫描结果，并刷新「附近设备」列表。
     */
    private void applyNearbyDevices() {
        if (isFinishing() || isDestroyed() || nearbyAdapter == null) return;

        int selfPort = TransferPortHelper.getPort(this);
        List<DiscoveredDevice> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        appendNearby(merged, seen, mdnsDevices, selfPort);
        appendNearby(merged, seen, scanDevices, selfPort);

        pairedAddresses.clear();
        for (TrustedPeer peer : peerStore.list()) {
            if (peer.getHost() == null || peer.getHost().isEmpty()) continue;
            if (peer.getPresharedKey() == null || peer.getPresharedKey().isEmpty()) continue;
            pairedAddresses.add(peer.getHost() + ":" + peer.getPort());
        }
        // DHCP 换 IP 后：地址变了但设备名没变时，仍视为已配对，点击「使用」即可更新地址
        for (DiscoveredDevice device : merged) {
            String address = device.getHost() + ":" + device.getPort();
            if (pairedAddresses.contains(address)) continue;
            TrustedPeer byName = findPairedPeerByName(device.getName());
            if (byName != null) pairedAddresses.add(address);
        }

        nearbyAdapter.setData(merged, pairedAddresses);
        boolean empty = merged.isEmpty();
        nearbyList.setVisibility(empty ? View.GONE : View.VISIBLE);
        nearbyEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            nearbyEmpty.setText(sawAnyNearby
                    ? R.string.multi_device_nearby_none
                    : R.string.multi_device_nearby_searching);
            if (sawAnyNearby) nearbyApHint.setVisibility(View.VISIBLE);
        } else {
            sawAnyNearby = true;
            nearbyApHint.setVisibility(View.GONE);
            nearbyHandler.removeCallbacks(nearbyHintRunnable);
        }
    }

    private void appendNearby(
            List<DiscoveredDevice> target,
            Set<String> seen,
            List<DiscoveredDevice> source,
            int selfPort) {
        for (DiscoveredDevice device : source) {
            if (device.getHost() == null || device.getHost().isEmpty()) continue;
            if (localIps.contains(device.getHost()) && device.getPort() == selfPort) continue;
            String key = device.getHost() + ":" + device.getPort();
            if (!seen.add(key)) continue;
            target.add(device);
        }
    }

    /**
     * 点击附近设备：已配对则更新地址，未配对则进入 6 位码配对。
     */
    private void onNearbyDevice(DiscoveredDevice device) {
        if (device.getHost() == null || device.getHost().isEmpty() || device.getPort() <= 0) {
            return;
        }
        TrustedPeer existing = findPeerByAddress(device.getHost(), device.getPort());
        if (existing == null) {
            existing = findPairedPeerByName(device.getName());
        }
        if (existing != null && existing.getPresharedKey() != null
                && !existing.getPresharedKey().isEmpty()) {
            peerStore.updateEndpoint(existing.getDeviceId(), existing.getDeviceName(),
                    device.getHost(), device.getPort());
            Toast.makeText(this,
                    getString(R.string.multi_device_nearby_updated, existing.getDeviceName()),
                    Toast.LENGTH_SHORT).show();
            refreshPeers();
            applyNearbyDevices();
            return;
        }
        discoverAndPair(device.getHost(), device.getPort());
    }

    /**
     * 按设备名查找已配对设备（用于 DHCP 换 IP 后自动匹配）。
     * 同名设备多于一个时返回 null，避免误改。
     */
    private TrustedPeer findPairedPeerByName(String deviceName) {
        if (deviceName == null || deviceName.isEmpty()) return null;
        TrustedPeer found = null;
        for (TrustedPeer peer : peerStore.list()) {
            if (peer.getPresharedKey() == null || peer.getPresharedKey().isEmpty()) continue;
            if (!deviceName.equals(peer.getDeviceName())) continue;
            if (found != null) return null;
            found = peer;
        }
        return found;
    }

    private TrustedPeer findPeerByAddress(String host, int port) {
        for (TrustedPeer peer : peerStore.list()) {
            if (host.equals(peer.getHost()) && port == peer.getPort()) return peer;
        }
        return null;
    }

    private boolean isConnectionFailure(String detail) {
        if (detail == null || detail.isEmpty()) return true;
        String lower = detail.toLowerCase();
        return lower.contains("failed to connect")
                || lower.contains("econnrefused")
                || lower.contains("connection refused")
                || lower.contains("no route to host")
                || lower.contains("unreachable")
                || lower.contains("timed out")
                || lower.contains("timeout");
    }

    /**
     * 连接失败时给出可操作的提示：服务未开 / 不同网络 / 路由器 AP 隔离。
     */
    private void showConnectFailureDialog(String address) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.multi_device_connect_failed_title)
                .setMessage(getString(R.string.multi_device_connect_failed_message, address))
                .setPositiveButton(R.string.multi_device_connect_failed_ok, null)
                .show();
    }

    private void setPairingBusy(boolean busy) {
        btnScanPair.setEnabled(!busy);
        btnGenerateCode.setEnabled(!busy);
        btnAddPeer.setEnabled(!busy);
        btnRefreshStatus.setEnabled(!busy);
        if (!busy) {
            btnShowQr.setEnabled(currentPairCode != null);
        }
    }

    private void ensureTransferServiceRunning() {
        try {
            startService(new Intent(this, TransferService.class));
        } catch (Exception e) {
            // 忽略：若服务未能启动，配对时会在网络层失败并给出提示
        }
    }

    // ==================== 信任设备列表 ====================

    private void refreshPeers() {
        List<TrustedPeer> peers = peerStore.list();
        adapter.setPeers(peers);
        boolean has = peers != null && !peers.isEmpty();
        recyclerView.setVisibility(has ? View.VISIBLE : View.GONE);
        emptyText.setVisibility(has ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onPeerDelete(TrustedPeer peer) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.multi_device_delete_title)
                .setMessage(getString(R.string.multi_device_delete_message, peer.getDeviceName()))
                .setPositiveButton(R.string.multi_device_delete_confirm, (d, w) -> {
                    peerStore.remove(peer.getDeviceId());
                    refreshPeers();
                })
                .setNegativeButton(R.string.multi_device_delete_cancel, null)
                .show();
    }

    @Override
    public void onPeerEdit(TrustedPeer peer) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_peer, null);
        EditText nameInput = view.findViewById(R.id.edit_peer_name);
        EditText hostInput = view.findViewById(R.id.edit_peer_host);
        EditText portInput = view.findViewById(R.id.edit_peer_port);
        nameInput.setText(peer.getDeviceName());
        hostInput.setText(peer.getHost());
        portInput.setText(String.valueOf(peer.getPort()));

        new AlertDialog.Builder(this)
                .setTitle(R.string.multi_device_edit_title)
                .setView(view)
                .setPositiveButton(R.string.multi_device_edit_save, (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    String host = hostInput.getText().toString().trim();
                    int port = parsePort(portInput.getText().toString());
                    if (name.isEmpty() || host.isEmpty()) {
                        Toast.makeText(this, R.string.multi_device_add_missing,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (port < 0) {
                        Toast.makeText(this, R.string.multi_device_add_invalid_port,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    TrustedPeer updated = peerStore.updateEndpoint(
                            peer.getDeviceId(), name, host, port);
                    if (updated == null) {
                        return;
                    }
                    Toast.makeText(this, R.string.multi_device_edit_saved,
                            Toast.LENGTH_SHORT).show();
                    refreshPeers();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ==================== 手动配对 ====================

    private void showAddPeerDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_peer, null);
        EditText hostInput = view.findViewById(R.id.input_peer_host);
        EditText portInput = view.findViewById(R.id.input_peer_port);
        portInput.setText(String.valueOf(TransferPortHelper.getPort(this)));

        new AlertDialog.Builder(this)
                .setTitle(R.string.multi_device_add_title)
                .setView(view)
                .setPositiveButton(R.string.multi_device_pair_discover, (d, w) -> {
                    String host = hostInput.getText().toString().trim();
                    int port = parsePort(portInput.getText().toString());
                    if (host.isEmpty()) {
                        Toast.makeText(this, R.string.multi_device_add_missing,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (port < 0) {
                        Toast.makeText(this, R.string.multi_device_add_invalid_port,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    discoverAndPair(host, port);
                })
                .setNegativeButton(R.string.multi_device_add_cancel, null)
                .show();
    }

    private void discoverAndPair(String host, int port) {
        setPairingBusy(true);
        LabPairingClient.getInstance(this).discover(host, port, (device, error) -> {
            if (isFinishing() || isDestroyed()) return;
            setPairingBusy(false);
            if (device == null) {
                String detail = error == null ? "" : error;
                if (isConnectionFailure(detail)) {
                    showConnectFailureDialog(host + ":" + port);
                } else {
                    String reason = detail.isEmpty()
                            ? getString(R.string.multi_device_pair_unreachable) : detail;
                    Toast.makeText(this,
                            getString(R.string.multi_device_pair_discover_failed, reason),
                            Toast.LENGTH_LONG).show();
                }
                return;
            }
            if (device.getDeviceId().equals(labManager.getSelfDeviceId())) {
                Toast.makeText(this, R.string.multi_device_pair_self, Toast.LENGTH_SHORT).show();
                return;
            }
            showPairCodeDialog(host, port, device);
        });
    }

    private void showPairCodeDialog(String host, int port, LabPairingClient.RemoteDevice device) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_pair_code, null);
        TextView info = view.findViewById(R.id.pair_device_info);
        EditText codeInput = view.findViewById(R.id.input_pair_code);
        info.setText(getString(R.string.multi_device_pair_device_info,
                device.getDeviceName(), device.getDeviceId()));

        new AlertDialog.Builder(this)
                .setTitle(R.string.multi_device_add_title)
                .setView(view)
                .setPositiveButton(R.string.multi_device_pair_confirm, (d, w) -> {
                    String code = codeInput.getText().toString().trim();
                    if (!PairingQrCodec.isValidCode(code)) {
                        Toast.makeText(this, R.string.multi_device_pair_code_invalid,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    confirmRepairIfNeeded(device, () -> startPairing(host, port, code));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 已存在带密钥的配对记录时，明确提示会替换密钥。
     */
    private void confirmRepairIfNeeded(LabPairingClient.RemoteDevice device, Runnable onConfirm) {
        TrustedPeer existing = peerStore.findById(device.getDeviceId());
        if (existing == null || existing.getPresharedKey() == null
                || existing.getPresharedKey().isEmpty()) {
            onConfirm.run();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.multi_device_pair_replace_title)
                .setMessage(getString(R.string.multi_device_pair_replace_message,
                        existing.getDeviceName()))
                .setPositiveButton(R.string.multi_device_pair_replace_confirm, (d, w) -> onConfirm.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ==================== 状态刷新与清空 ====================

    private void checkLocalFiles() {
        Toast.makeText(this, R.string.multi_device_checking_local, Toast.LENGTH_SHORT).show();
        boolean queued = LabTasks.checkLocal(this, (success, message) -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            refreshPeers();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }));
        if (!queued) {
            Toast.makeText(this, R.string.multi_device_task_running, Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshStatus() {
        btnRefreshStatus.setEnabled(false);
        Toast.makeText(this, R.string.multi_device_refreshing, Toast.LENGTH_SHORT).show();
        LanImageFetcher.getInstance(this).probeAllRtt(() -> {
            if (isFinishing() || isDestroyed()) return;
            btnRefreshStatus.setEnabled(true);
            refreshPeers();
            Toast.makeText(this, R.string.multi_device_refresh_done,
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void confirmClearAllPeers() {
        List<TrustedPeer> peers = peerStore.list();
        if (peers == null || peers.isEmpty()) {
            Toast.makeText(this, R.string.multi_device_peers_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.multi_device_clear_all)
                .setMessage(getString(R.string.multi_device_clear_all_message, peers.size()))
                .setPositiveButton(R.string.multi_device_clear_all_confirm, (d, w) -> {
                    peerStore.clearAll();
                    refreshPeers();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private LabSyncDirection readSyncDirection() {
        String wire = Settings.getLabSyncDirection();
        for (LabSyncDirection direction : LabSyncDirection.values()) {
            if (direction.getWire().equals(wire)) return direction;
        }
        return LabSyncDirection.BIDIRECTIONAL;
    }

    /**
     * @return 合法端口；非法返回 -1
     */
    private int parsePort(String raw) {        try {
            int port = Integer.parseInt(raw.trim());
            return port >= 1 && port <= 65535 ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
