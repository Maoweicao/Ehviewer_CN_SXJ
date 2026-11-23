package com.hippo.ehviewer.ui.fragment.lab;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.ip.IpPoolManager;
import com.hippo.ehviewer.lab.ip.IpSwitchController;
import com.hippo.ehviewer.lab.ip.core.ClashProfileStore;
import com.hippo.ehviewer.lab.ip.model.IpInfo;
import com.hippo.ehviewer.ui.lab.NodeListActivity;
import com.hippo.ehviewer.ui.lab.SubscriptionManagerActivity;

import java.util.List;

public class IpSwitchFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {

    private static final String KEY_IP_SWITCH_ENABLED = "ip_switch_enabled";
    private static final String KEY_IP_SUBSCRIPTION_MANAGE = "ip_subscription_manage";
    private static final String KEY_IP_NODE_LIST = "ip_node_list";
    private static final String KEY_IP_POOL_STATUS = "ip_pool_status";
    private static final String KEY_IP_POOL_CLEAR = "ip_pool_clear";
    private static final String KEY_IP_SWITCH_NEXT = "ip_switch_next";
    private static final String KEY_IP_HTTP_POOL_DIAG = "ip_http_pool_diag";

    private IpPoolManager ipPoolManager;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (getActivity() == null) return;
            IpSwitchController.getInstance().refreshCurrentNodeAsync();
            Preference poolStatus = findPreference(KEY_IP_POOL_STATUS);
            if (poolStatus != null) updatePoolStatus(poolStatus);
            statusHandler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.lab_ip_switch, rootKey);

        ipPoolManager = IpPoolManager.getInstance();

        Preference enabled = findPreference(KEY_IP_SWITCH_ENABLED);
        Preference subscriptionManage = findPreference(KEY_IP_SUBSCRIPTION_MANAGE);
        Preference nodeList = findPreference(KEY_IP_NODE_LIST);
        Preference poolStatus = findPreference(KEY_IP_POOL_STATUS);
        Preference poolClear = findPreference(KEY_IP_POOL_CLEAR);
        Preference switchNext = findPreference(KEY_IP_SWITCH_NEXT);
        Preference poolDiagnostics = findPreference(KEY_IP_HTTP_POOL_DIAG);

        if (enabled != null) {
            enabled.setOnPreferenceChangeListener(this);
        }

        if (subscriptionManage != null) {
            subscriptionManage.setOnPreferenceClickListener(this);
        }

        if (nodeList != null) {
            nodeList.setOnPreferenceClickListener(this);
        }

        if (poolStatus != null) {
            poolStatus.setOnPreferenceClickListener(this);
            updatePoolStatus(poolStatus);
        }

        if (poolClear != null) {
            poolClear.setOnPreferenceClickListener(this);
        }
        if (switchNext != null) {
            switchNext.setOnPreferenceClickListener(this);
        }
        if (poolDiagnostics != null) {
            poolDiagnostics.setOnPreferenceClickListener(this);
        }
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        if (KEY_IP_SUBSCRIPTION_MANAGE.equals(key)) {
            Intent intent = new Intent(getActivity(), SubscriptionManagerActivity.class);
            startActivity(intent);
            return true;
        } else if (KEY_IP_NODE_LIST.equals(key)) {
            Intent intent = new Intent(getActivity(), NodeListActivity.class);
            startActivity(intent);
            return true;
        } else if (KEY_IP_POOL_STATUS.equals(key)) {
            updatePoolStatus(preference);
            return true;
        } else if (KEY_IP_POOL_CLEAR.equals(key)) {
            showClearPoolDialog();
            return true;
        } else if (KEY_IP_SWITCH_NEXT.equals(key)) {
            switchNextNode();
            return true;
        } else if (KEY_IP_HTTP_POOL_DIAG.equals(key)) {
            showPoolDiagnostics();
            return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (KEY_IP_SWITCH_ENABLED.equals(key)) {
            boolean enabled = (Boolean) newValue;
            boolean wasEnabled = Settings.getIpSwitchEnabled();
            if (enabled && !wasEnabled) {
                // 快照启用前的代理方式，关闭时原样恢复。
                Settings.putIpSwitchPrevProxyType(Settings.getProxyType());
            }
            Settings.putIpSwitchEnabled(enabled);
            if (enabled) {
                // 自动换 IP 负责处理限流，下载里的“限流暂停”自动关闭。
                IpSwitchController.applyDownloadPolicyIfNeeded();
            } else {
                IpSwitchController.restoreDownloadPolicy();
            }
            if (enabled && Settings.getLabEnabled()) {
                com.hippo.ehviewer.lab.ip.SubscriptionManager
                        .getInstance(requireContext().getApplicationContext())
                        .startAutoRefresh();
            }
            IpSwitchController controller = IpSwitchController.getInstance();
            if (enabled) {
                controller.ensureStarted(requireContext().getApplicationContext());
            } else {
                controller.stop();
                com.hippo.ehviewer.lab.ip.SubscriptionManager
                        .getInstance(requireContext().getApplicationContext())
                        .stopAutoRefresh();
                int prev = Settings.getIpSwitchPrevProxyType();
                if (prev >= 0) {
                    Settings.putProxyType(prev);
                }
                Settings.putIpSwitchPrevProxyType(-1);
            }
            return true;
        }
        return false;
    }

    private void updatePoolStatus(Preference preference) {
        int total = ClashProfileStore.getNodes().size();
        if (total <= 0) {
            total = ipPoolManager.getAllIps().size();
        }

        IpSwitchController controller = IpSwitchController.getInstance();
        String currentNode = controller.getCurrentNodeName();
        String currentInfo;
        if (currentNode != null && !currentNode.isEmpty()) {
            currentInfo = currentNode;
        } else if (!controller.isEnabled()) {
            currentInfo = "未启用";
        } else if (controller.isCoreRunning()) {
            currentInfo = "自动选择中";
        } else {
            currentInfo = "无可用节点";
        }
        String coreStatus;
        if (!controller.isEnabled()) {
            coreStatus = "未启用";
        } else if (controller.isCoreRunning()) {
            coreStatus = "运行中";
        } else {
            String error = controller.getLastError();
            coreStatus = "未运行" + (error == null || error.isEmpty() ? "" : "：" + error);
        }

        preference.setSummary(String.format("节点: %d | 内核: %s\n当前节点: %s%s",
                total, coreStatus, currentInfo, vpnNote()));
    }

    private static String vpnNote() {
        try {
            if (com.hippo.ehviewer.network.NetworkStateManager.INSTANCE.isVpnConnected()
                    && !com.hippo.ehviewer.lab.ip.core.VpnBypass.isBound()) {
                return "\n检测到系统 VPN，内置内核可能仍被系统 VPN 转发；建议关闭系统 VPN";
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void switchNextNode() {
        if (getActivity() == null) return;
        IpSwitchController controller = IpSwitchController.getInstance();
        if (!controller.isEnabled() || !controller.isCoreRunning()) {
            Toast.makeText(getActivity(), "请先启用自动换 IP 并等待内核启动",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(getActivity(), "正在切换节点…", Toast.LENGTH_SHORT).show();
        com.hippo.util.IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            String node = controller.switchToNextNodeBlocking("manual-next");
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (getActivity() == null) return;
                if (node == null) {
                    Toast.makeText(getActivity(), "没有可切换的节点",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getActivity(), "已切换到: " + node,
                            Toast.LENGTH_SHORT).show();
                }
                Preference poolStatus = findPreference(KEY_IP_POOL_STATUS);
                if (poolStatus != null) updatePoolStatus(poolStatus);
            });
        });
    }

    private void showPoolDiagnostics() {
        if (getActivity() == null) return;
        final android.content.Context app = requireContext().getApplicationContext();
        final android.app.Activity activity = getActivity();
        // 诊断会读取内核 /connections，必须放到后台线程，主线程网络会直接失败。
        new Thread(() -> {
            final String text = com.hippo.ehviewer.lab.ip.core.HttpPoolDiagnostics.snapshot(app);
            activity.runOnUiThread(() -> {
                if (!isAdded() || getActivity() == null) return;
                new android.app.AlertDialog.Builder(getActivity())
                        .setTitle("HTTP 连接池诊断")
                        .setMessage(text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "ip-pool-diag").start();
    }

    private void showClearPoolDialog() {
        if (getActivity() == null) return;

        new android.app.AlertDialog.Builder(getActivity())
                .setTitle("清空IP池")
                .setMessage("确定要清空所有已缓存的IP信息吗？此操作不可撤销。")
                .setPositiveButton("清空", (dialog, which) -> {
                    ipPoolManager.clearAll();
                    Toast.makeText(getActivity(), "IP池已清空", Toast.LENGTH_SHORT).show();
                    Preference poolStatus = findPreference(KEY_IP_POOL_STATUS);
                    if (poolStatus != null) {
                        updatePoolStatus(poolStatus);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getContext() != null) {
            ClashProfileStore.syncPool(getContext().getApplicationContext());
        }
        Preference poolStatus = findPreference(KEY_IP_POOL_STATUS);
        if (poolStatus != null) {
            updatePoolStatus(poolStatus);
        }
        if (Settings.getIpSwitchEnabled() && getContext() != null) {
            IpSwitchController.getInstance().ensureStarted(getContext().getApplicationContext());
        }
        statusHandler.removeCallbacks(statusTick);
        statusHandler.post(statusTick);
    }

    @Override
    public void onPause() {
        super.onPause();
        statusHandler.removeCallbacks(statusTick);
    }
}
