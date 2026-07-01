package com.hippo.ehviewer.ui.fragment.lab;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.lab.ip.IpPoolManager;
import com.hippo.ehviewer.lab.ip.IpSwitchController;
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

    private IpPoolManager ipPoolManager;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.lab_ip_switch, rootKey);

        ipPoolManager = IpPoolManager.getInstance();

        Preference enabled = findPreference(KEY_IP_SWITCH_ENABLED);
        Preference subscriptionManage = findPreference(KEY_IP_SUBSCRIPTION_MANAGE);
        Preference nodeList = findPreference(KEY_IP_NODE_LIST);
        Preference poolStatus = findPreference(KEY_IP_POOL_STATUS);
        Preference poolClear = findPreference(KEY_IP_POOL_CLEAR);

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
        }
        return false;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (KEY_IP_SWITCH_ENABLED.equals(key)) {
            boolean enabled = (Boolean) newValue;
            Settings.putIpSwitchEnabled(enabled);
            if (enabled && Settings.getLabEnabled()) {
                com.hippo.ehviewer.lab.ip.SubscriptionManager.getInstance().startAutoRefresh();
            }
            return true;
        }
        return false;
    }

    private void updatePoolStatus(Preference preference) {
        List<IpInfo> allIps = ipPoolManager.getAllIps();
        int total = allIps.size();
        int available = 0;
        int blocked = 0;
        for (IpInfo ip : allIps) {
            if (ip.isAvailable()) {
                available++;
            } else {
                blocked++;
            }
        }

        IpInfo currentIp = IpSwitchController.getInstance().getCurrentIp();
        String currentInfo = "无";
        if (currentIp != null) {
            currentInfo = currentIp.getDisplayName();
        }

        preference.setSummary(String.format("总数: %d | 可用: %d | 被封: %d\n当前代理: %s",
                total, available, blocked, currentInfo));
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
        Preference poolStatus = findPreference(KEY_IP_POOL_STATUS);
        if (poolStatus != null) {
            updatePoolStatus(poolStatus);
        }
    }
}
