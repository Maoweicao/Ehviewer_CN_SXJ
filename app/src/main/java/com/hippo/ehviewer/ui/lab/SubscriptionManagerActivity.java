package com.hippo.ehviewer.ui.lab;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.lab.ip.SubscriptionManager;
import com.hippo.ehviewer.lab.ip.model.IpInfo;
import com.hippo.ehviewer.lab.ip.model.Subscription;
import com.hippo.ehviewer.ui.EhActivity;

import java.util.ArrayList;
import java.util.List;

public class SubscriptionManagerActivity extends EhActivity {

    private RecyclerView recyclerView;
    private SubscriptionAdapter adapter;
    private SubscriptionManager subscriptionManager;
    private List<Subscription> subscriptions;

    private final ActivityResultLauncher<Intent> scanLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    String scanResult = result.getData().getStringExtra("scan_result");
                    if (scanResult != null) {
                        handleScanResult(scanResult);
                    }
                }
            });

    private final SubscriptionManager.SubscriptionRefreshCallback refreshCallback =
            new SubscriptionManager.SubscriptionRefreshCallback() {
                @Override
                public void onStart(Subscription sub) {
                    runOnUiThread(() -> {
                        int pos = subscriptions.indexOf(sub);
                        if (pos >= 0) adapter.notifyItemChanged(pos);
                    });
                }

                @Override
                public void onResult(Subscription sub, List<IpInfo> newIps) {
                    runOnUiThread(() -> {
                        int pos = subscriptions.indexOf(sub);
                        if (pos >= 0) adapter.notifyItemChanged(pos);
                        Toast.makeText(SubscriptionManagerActivity.this,
                                sub.name + " 更新成功, 获取 " + (newIps != null ? newIps.size() : 0) + " 个节点",
                                Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onError(Subscription sub, String error) {
                    runOnUiThread(() -> {
                        int pos = subscriptions.indexOf(sub);
                        if (pos >= 0) adapter.notifyItemChanged(pos);
                        Toast.makeText(SubscriptionManagerActivity.this,
                                sub.name + " 更新失败: " + error, Toast.LENGTH_SHORT).show();
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_subscription_manager);

        subscriptionManager = SubscriptionManager.getInstance();
        subscriptions = new ArrayList<>();

        initToolbar();
        initRecyclerView();
        loadSubscriptions();
    }

    private void initToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(toolbar);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_subscription_manager, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_scan) {
            startScan();
            return true;
        } else if (id == R.id.action_manual) {
            showManualInputDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void initRecyclerView() {
        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SubscriptionAdapter();
        recyclerView.setAdapter(adapter);
    }

    private void startScan() {
        Intent intent = new Intent("com.google.zxing.client.android.SCAN");
        intent.putExtra("SCAN_MODE", "QR_CODE_MODE");
        try {
            scanLauncher.launch(intent);
        } catch (Exception e) {
            showManualInputDialog();
        }
    }

    private void showManualInputDialog() {
        EditText input = new EditText(this);
        input.setHint("粘贴订阅链接");
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle("手动添加订阅")
                .setView(input)
                .setPositiveButton("添加", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        addSubscriptionFromUrl(url);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void handleScanResult(String scanResult) {
        if (scanResult.startsWith("ss://") || scanResult.startsWith("ssr://") ||
                scanResult.startsWith("vmess://") || scanResult.startsWith("vless://") ||
                scanResult.startsWith("trojan://") ||
                scanResult.startsWith("http://") || scanResult.startsWith("https://")) {
            addSubscriptionFromUrl(scanResult);
        } else {
            Toast.makeText(this, "无法识别的二维码内容", Toast.LENGTH_SHORT).show();
        }
    }

    private void addSubscriptionFromUrl(String url) {
        String name = "订阅_" + (subscriptions.size() + 1);
        String type = detectSubscriptionType(url);

        Subscription sub = new Subscription(name, url, type);
        subscriptionManager.addSubscription(sub);
        subscriptions.add(sub);
        adapter.notifyDataSetChanged();

        Toast.makeText(this, "订阅已添加", Toast.LENGTH_SHORT).show();

        subscriptionManager.refreshSubscription(sub, refreshCallback);
    }

    private String detectSubscriptionType(String url) {
        if (url.startsWith("ss://")) return "ss";
        if (url.startsWith("ssr://")) return "ssr";
        if (url.startsWith("vmess://") || url.startsWith("vless://")) return "v2ray";
        if (url.startsWith("trojan://")) return "trojan";
        return "mixed";
    }

    private void loadSubscriptions() {
        subscriptions.clear();
        subscriptions.addAll(subscriptionManager.getAllSubscriptions());
        adapter.notifyDataSetChanged();
    }

    private class SubscriptionAdapter extends RecyclerView.Adapter<SubscriptionAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_subscription, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Subscription sub = subscriptions.get(position);

            holder.tvName.setText(sub.name);
            holder.tvType.setText(sub.getTypeDisplayName());

            holder.tvNodeCount.setText(sub.nodeCount > 0 ? "(" + sub.nodeCount + "节点)" : "");

            String statusText;
            int statusColor;
            switch (sub.updateStatus) {
                case Subscription.STATUS_UPDATING:
                    statusText = "更新中...";
                    statusColor = getResources().getColor(android.R.color.holo_blue_dark);
                    break;
                case Subscription.STATUS_SUCCESS:
                    statusText = "更新成功";
                    statusColor = getResources().getColor(android.R.color.holo_green_dark);
                    break;
                case Subscription.STATUS_ERROR:
                    statusText = "更新失败: " + sub.errorMessage;
                    statusColor = getResources().getColor(android.R.color.holo_red_dark);
                    break;
                default:
                    statusText = sub.enabled ? "已启用" : "已禁用";
                    statusColor = sub.enabled ?
                            getResources().getColor(android.R.color.holo_green_dark) :
                            getResources().getColor(android.R.color.holo_red_dark);
                    break;
            }
            holder.tvStatus.setText(statusText);
            holder.tvStatus.setTextColor(statusColor);

            StringBuilder info = new StringBuilder();
            String traffic = sub.getTrafficDisplay();
            if (!traffic.isEmpty()) info.append("流量: ").append(traffic);
            String expiry = sub.getExpiryDisplay();
            if (!expiry.isEmpty()) {
                if (info.length() > 0) info.append(" | ");
                info.append("到期: ").append(expiry);
            }
            holder.tvInfo.setText(info.toString());
            holder.tvInfo.setVisibility(info.length() > 0 ? View.VISIBLE : View.GONE);

            holder.switchEnabled.setChecked(sub.enabled);
            holder.switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                sub.enabled = isChecked;
                subscriptionManager.updateSubscription(sub);
                notifyItemChanged(position);
            });

            holder.btnRefresh.setOnClickListener(v -> {
                subscriptionManager.refreshSubscription(sub, refreshCallback);
            });

            holder.btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(SubscriptionManagerActivity.this)
                        .setTitle("删除订阅")
                        .setMessage("确定要删除订阅 \"" + sub.name + "\" 吗？")
                        .setPositiveButton("删除", (dialog, which) -> {
                            subscriptionManager.removeSubscription(sub.id);
                            subscriptions.remove(position);
                            notifyDataSetChanged();
                            Toast.makeText(SubscriptionManagerActivity.this, "订阅已删除", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        @Override
        public int getItemCount() {
            return subscriptions.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvType, tvStatus, tvNodeCount, tvInfo;
            Switch switchEnabled;
            ImageButton btnRefresh, btnDelete;

            ViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_name);
                tvType = itemView.findViewById(R.id.tv_type);
                tvStatus = itemView.findViewById(R.id.tv_status);
                tvNodeCount = itemView.findViewById(R.id.tv_node_count);
                tvInfo = itemView.findViewById(R.id.tv_info);
                switchEnabled = itemView.findViewById(R.id.switch_enabled);
                btnRefresh = itemView.findViewById(R.id.btn_refresh);
                btnDelete = itemView.findViewById(R.id.btn_delete);
            }
        }
    }
}
