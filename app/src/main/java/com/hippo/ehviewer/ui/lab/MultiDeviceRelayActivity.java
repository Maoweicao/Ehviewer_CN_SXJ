package com.hippo.ehviewer.ui.lab;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.lab.LabConfig;
import com.hippo.ehviewer.lab.LabConfigStore;
import com.hippo.ehviewer.lab.LabManager;
import com.hippo.ehviewer.lab.TrustedPeer;
import com.hippo.ehviewer.lab.TrustedPeerStore;
import com.hippo.ehviewer.lab.TransferPortHelper;
import com.hippo.ehviewer.ui.ToolbarActivity;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class MultiDeviceRelayActivity extends ToolbarActivity
        implements TrustedPeerAdapter.OnPeerActionListener {

    private static final String SUB_AUTO_RELAY = "autoRelay";
    private static final String SUB_CROSS_DEVICE_IMAGE = "crossDeviceImage";
    private static final String SUB_DB_SNAPSHOT = "dbSnapshot";
    private static final String SUB_INCREMENTAL_RESUME = "incrementalResume";

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

    private boolean bindingSwitches = false;

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
        btnAddPeer.setOnClickListener(v -> showAddPeerDialog());

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TrustedPeerAdapter();
        adapter.setOnPeerActionListener(this);
        recyclerView.setAdapter(adapter);

        bindIdentity();
        bindSwitches();

        switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> onSwitchChanged());
        switchAutoRelay.setOnCheckedChangeListener((buttonView, isChecked) -> onSwitchChanged());
        switchCrossDeviceImage.setOnCheckedChangeListener((buttonView, isChecked) -> onSwitchChanged());
        switchDbSnapshot.setOnCheckedChangeListener((buttonView, isChecked) -> onSwitchChanged());
        switchIncrementalResume.setOnCheckedChangeListener((buttonView, isChecked) -> onSwitchChanged());

        peerStore.addListener(peerListener);
        refreshPeers();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        peerStore.removeListener(peerListener);
    }

    // ==================== 本机身份 ====================

    private void bindIdentity() {
        selfName.setText(labManager.getSelfDeviceName());
        selfId.setText(getString(R.string.multi_device_self_id, labManager.getSelfDeviceId()));
        selfCaps.setText(getString(R.string.multi_device_self_caps,
                Arrays.toString(labManager.getSelfCapabilities())));
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

    // ==================== 信任设备列表 ====================

    private void refreshPeers() {
        List<TrustedPeer> peers = peerStore.list();
        adapter.setPeers(peers);
        boolean has = peers != null && !peers.isEmpty();
        recyclerView.setVisibility(has ? android.view.View.VISIBLE : android.view.View.GONE);
        emptyText.setVisibility(has ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    @Override
    public void onPeerDelete(TrustedPeer peer) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.multi_device_delete_title)
                .setMessage(getString(R.string.multi_device_delete_message, peer.getDeviceName()))
                .setPositiveButton(R.string.multi_device_delete_confirm, (d, w) -> {
                    peerStore.remove(peer.getDeviceId());
                    refreshPeers();
                })
                .setNegativeButton(R.string.multi_device_delete_cancel, null)
                .show();
    }

    // ==================== 手动添加设备 ====================

    private void showAddPeerDialog() {
        android.view.View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_peer, null);
        EditText nameInput = view.findViewById(R.id.input_peer_name);
        EditText hostInput = view.findViewById(R.id.input_peer_host);
        EditText portInput = view.findViewById(R.id.input_peer_port);
        EditText idInput = view.findViewById(R.id.input_peer_id);
        portInput.setText(String.valueOf(TransferPortHelper.getPort(this)));

        new AlertDialog.Builder(this)
                .setTitle(R.string.multi_device_add_title)
                .setView(view)
                .setPositiveButton(R.string.multi_device_add_confirm, (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    String host = hostInput.getText().toString().trim();
                    String portStr = portInput.getText().toString().trim();
                    String id = idInput.getText().toString().trim();

                    if (name.isEmpty() || host.isEmpty()) {
                        Toast.makeText(this, R.string.multi_device_add_missing, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int port;
                    try {
                        port = Integer.parseInt(portStr);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, R.string.multi_device_add_invalid_port, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (port < 1 || port > 65535) {
                        Toast.makeText(this, R.string.multi_device_add_invalid_port, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (id.isEmpty()) {
                        id = UUID.randomUUID().toString();
                    }

                    TrustedPeer peer = new TrustedPeer.Builder(id, name, "android")
                            .host(host)
                            .port(port)
                            .trustedAt(System.currentTimeMillis())
                            .build();
                    peerStore.upsert(peer);
                    Toast.makeText(this, R.string.multi_device_added, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.multi_device_add_cancel, null)
                .show();
    }
}
