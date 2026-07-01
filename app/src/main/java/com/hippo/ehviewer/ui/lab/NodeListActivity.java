package com.hippo.ehviewer.ui.lab;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.lab.ip.IpPoolManager;
import com.hippo.ehviewer.lab.ip.model.IpInfo;
import com.hippo.ehviewer.ui.EhActivity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NodeListActivity extends EhActivity {

    private RecyclerView recyclerView;
    private NodeAdapter adapter;
    private IpPoolManager poolManager;
    private TextView tvSummary;
    private Button btnTestAll;
    private final List<IpInfo> nodes = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_node_list);

        poolManager = IpPoolManager.getInstance();

        initToolbar();
        initViews();
        loadNodes();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void initToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(toolbar);
    }

    private void initViews() {
        tvSummary = findViewById(R.id.tv_summary);
        btnTestAll = findViewById(R.id.btn_test_all);

        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NodeAdapter();
        recyclerView.setAdapter(adapter);

        btnTestAll.setOnClickListener(v -> testAllNodes());
    }

    private void loadNodes() {
        nodes.clear();
        nodes.addAll(poolManager.getAllIps());
        adapter.notifyDataSetChanged();
        updateSummary();
    }

    private void updateSummary() {
        int total = nodes.size();
        int available = 0;
        int tested = 0;
        for (IpInfo ip : nodes) {
            if (ip.isAvailable()) available++;
            if (ip.latency >= 0) tested++;
        }
        tvSummary.setText(String.format("总计: %d | 可用: %d | 已测: %d", total, available, tested));
    }

    private void testAllNodes() {
        btnTestAll.setEnabled(false);
        btnTestAll.setText("测试中...");

        executor.execute(() -> {
            final int[] tested = {0};
            for (IpInfo ip : nodes) {
                if (!ip.enabled) continue;
                int latency = testLatency(ip);
                poolManager.updateIpLatency(ip, latency);
                tested[0]++;
                final int index = nodes.indexOf(ip);
                runOnUiThread(() -> {
                    adapter.notifyItemChanged(index);
                    updateSummary();
                });
            }
            runOnUiThread(() -> {
                btnTestAll.setEnabled(true);
                btnTestAll.setText("测试全部");
                Toast.makeText(this, "已完成 " + tested[0] + " 个节点测试", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void testSingleNode(IpInfo ip, int position) {
        executor.execute(() -> {
            int latency = testLatency(ip);
            poolManager.updateIpLatency(ip, latency);
            runOnUiThread(() -> {
                adapter.notifyItemChanged(position);
                updateSummary();
            });
        });
    }

    private int testLatency(IpInfo ip) {
        Socket socket = null;
        try {
            long start = System.currentTimeMillis();
            socket = new Socket();
            socket.connect(new InetSocketAddress(ip.ip, ip.port), 5000);
            socket.close();
            return (int) (System.currentTimeMillis() - start);
        } catch (IOException e) {
            return -2;
        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private class NodeAdapter extends RecyclerView.Adapter<NodeAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_node, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            IpInfo node = nodes.get(position);

            holder.tvName.setText(node.getDisplayName());
            holder.tvProtocol.setText(node.actualProtocol != null ? node.actualProtocol.toUpperCase() : node.protocol.toUpperCase());
            holder.tvSource.setText(node.source);
            holder.tvLatency.setText(node.getLatencyDisplay());

            String status;
            int statusColor;
            if (node.isBlocked) {
                status = "已封禁";
                statusColor = getResources().getColor(android.R.color.holo_red_dark);
            } else if (!node.enabled) {
                status = "已禁用";
                statusColor = getResources().getColor(android.R.color.darker_gray);
            } else if (node.latency < 0) {
                status = "未测试";
                statusColor = getResources().getColor(android.R.color.holo_orange_dark);
            } else if (node.latency == -2) {
                status = "超时";
                statusColor = getResources().getColor(android.R.color.holo_red_dark);
            } else {
                status = "正常";
                statusColor = getResources().getColor(android.R.color.holo_green_dark);
            }
            holder.tvStatus.setText(status);
            holder.tvStatus.setTextColor(statusColor);

            holder.switchEnabled.setChecked(node.enabled);
            holder.switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                poolManager.setIpEnabled(node, isChecked);
                node.enabled = isChecked;
                notifyItemChanged(position);
                updateSummary();
            });

            holder.btnTest.setOnClickListener(v -> {
                node.latency = -1;
                node.lastTestTime = 0;
                notifyItemChanged(position);
                testSingleNode(node, position);
            });
        }

        @Override
        public int getItemCount() {
            return nodes.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvProtocol, tvSource, tvLatency, tvStatus;
            Switch switchEnabled;
            ImageButton btnTest;

            ViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_name);
                tvProtocol = itemView.findViewById(R.id.tv_protocol);
                tvSource = itemView.findViewById(R.id.tv_source);
                tvLatency = itemView.findViewById(R.id.tv_latency);
                tvStatus = itemView.findViewById(R.id.tv_status);
                switchEnabled = itemView.findViewById(R.id.switch_enabled);
                btnTest = itemView.findViewById(R.id.btn_test);
            }
        }
    }
}
