package com.hippo.ehviewer.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.util.NetworkDiagnosticTool;

import java.util.ArrayList;
import java.util.List;

public class NetworkDiagnosticActivity extends EhActivity {

    private LinearLayout container;
    private ProgressBar progressBar;
    private TextView infoText;
    private Spinner timeoutSpinner;
    private Button btnProgressCancel;
    private LinearLayout progressContainer;
    private volatile boolean isRunning = false;
    private volatile Thread diagnosticThread;
    private int timeoutSeconds = 60;

    @Override
    protected int getThemeResId(int theme) {
        switch (theme) {
            case Settings.THEME_LIGHT:
            default:
                return R.style.AppTheme;
            case Settings.THEME_DARK:
                return R.style.AppTheme_Dark;
            case Settings.THEME_BLACK:
                return R.style.AppTheme_Black;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_diagnostic);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.network_diagnostic_title);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        container = findViewById(R.id.diagnostic_container);
        progressBar = findViewById(R.id.diagnostic_progress);
        infoText = findViewById(R.id.network_info_text);
        timeoutSpinner = findViewById(R.id.timeout_spinner);
        progressContainer = findViewById(R.id.progress_bar_container);
        btnProgressCancel = findViewById(R.id.btn_cancel);

        timeoutSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                timeoutSeconds = Integer.parseInt(parent.getItemAtPosition(position).toString());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        timeoutSpinner.setSelection(2);

        btnProgressCancel.setOnClickListener(v -> cancelDiagnostic());

        NetworkDiagnosticTool.NetworkInfo networkInfo =
                NetworkDiagnosticTool.getNetworkInfo(this);
        updateNetworkInfoDisplay(networkInfo);

        addAdvancedButton();
        startDiagnostic();
    }

    private void updateNetworkInfoDisplay(NetworkDiagnosticTool.NetworkInfo info) {
        String status = info.isConnected ?
                getString(R.string.network_status_connected) :
                getString(R.string.network_status_disconnected);
        String text = String.format(getString(R.string.network_info_format),
                status, info.networkType, info.currentIP);
        infoText.setText(text);
    }

    private void cancelDiagnostic() {
        if (diagnosticThread != null) {
            diagnosticThread.interrupt();
        }
    }

    private void startDiagnostic() {
        if (isRunning) return;
        isRunning = true;

        progressContainer.setVisibility(View.VISIBLE);

        diagnosticThread = new Thread(() -> {
            String[] domains = {"e-hentai.org", "exhentai.org"};
            String[] outerDomains = {"ehgt.org", "forums.e-hentai.org", "ehwiki.org"};
            try {
                List<NetworkDiagnosticTool.SiteInfo> results =
                        NetworkDiagnosticTool.checkMultipleSites(domains, timeoutSeconds);
                if (Thread.currentThread().isInterrupted()) {
                    runOnUiThread(() -> onDiagnosticCancelled());
                    return;
                }
                List<NetworkDiagnosticTool.SiteInfo> outerResults =
                        NetworkDiagnosticTool.checkMultipleSites(outerDomains, timeoutSeconds);

                runOnUiThread(() -> {
                    progressContainer.setVisibility(View.GONE);
                    container.removeAllViews();
                    displayResults(results);
                    displayOuterResults(outerResults);
                    addAdvancedButton();
                    isRunning = false;
                    diagnosticThread = null;
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressContainer.setVisibility(View.GONE);
                    Toast.makeText(NetworkDiagnosticActivity.this,
                            getString(R.string.diagnostic_error, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                    isRunning = false;
                    diagnosticThread = null;
                });
            }
        });
        diagnosticThread.start();
    }

    private void onDiagnosticCancelled() {
        progressContainer.setVisibility(View.GONE);
        isRunning = false;
        diagnosticThread = null;
        Toast.makeText(this, R.string.diagnostic_cancelled, Toast.LENGTH_SHORT).show();
    }

    private void displayResults(List<NetworkDiagnosticTool.SiteInfo> results) {
        for (NetworkDiagnosticTool.SiteInfo site : results) {
            View itemView = LayoutInflater.from(this).inflate(
                    R.layout.item_diagnostic_result, container, false);

            TextView domainText = itemView.findViewById(R.id.site_domain);
            domainText.setText(site.domain);

            TextView ipText = itemView.findViewById(R.id.site_ip);
            if ("FAILED".equals(site.resolvedIP)) {
                ipText.setText(R.string.dns_resolution_failed);
                ipText.setTextColor(getErrorColor());
            } else {
                ipText.setText(getString(R.string.ip_address_format, site.resolvedIP));
                ipText.setTextColor(getTextSecondaryColor());
            }

            TextView statusText = itemView.findViewById(R.id.site_status);
            if (site.isAccessible) {
                statusText.setText(getString(R.string.site_accessible, site.responseTime));
                statusText.setTextColor(getSuccessColor());
            } else {
                statusText.setText(R.string.site_inaccessible);
                statusText.setTextColor(getErrorColor());
                if (site.error != null) {
                    statusText.setText(statusText.getText() + " (" + site.error + ")");
                }
            }

            container.addView(itemView);
        }
    }

    private void displayOuterResults(List<NetworkDiagnosticTool.SiteInfo> outerResults) {
        TextView sectionHeader = new TextView(this);
        sectionHeader.setText(R.string.diagnostic_outer_section);
        sectionHeader.setTextSize(16);
        sectionHeader.setTextColor(getTextPrimaryColor());
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, 24, 0, 8);
        sectionHeader.setLayoutParams(headerParams);
        container.addView(sectionHeader);

        for (NetworkDiagnosticTool.SiteInfo site : outerResults) {
            View itemView = LayoutInflater.from(this).inflate(
                    R.layout.item_diagnostic_result, container, false);

            TextView domainText = itemView.findViewById(R.id.site_domain);
            domainText.setText(site.domain);

            TextView ipText = itemView.findViewById(R.id.site_ip);
            if ("FAILED".equals(site.resolvedIP)) {
                ipText.setText(R.string.dns_resolution_failed);
                ipText.setTextColor(getErrorColor());
            } else {
                ipText.setText(getString(R.string.ip_address_format, site.resolvedIP));
                ipText.setTextColor(getTextSecondaryColor());
            }

            TextView statusText = itemView.findViewById(R.id.site_status);
            if (site.isAccessible) {
                statusText.setText(getString(R.string.site_accessible, site.responseTime));
                statusText.setTextColor(getSuccessColor());
            } else {
                statusText.setText(R.string.site_inaccessible);
                statusText.setTextColor(getErrorColor());
                if (site.error != null) {
                    statusText.setText(statusText.getText() + " (" + site.error + ")");
                }
            }

            container.addView(itemView);
        }
    }

    private void addAdvancedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 24, 0, 8);

        android.widget.Button advancedBtn = new android.widget.Button(this);
        advancedBtn.setText(R.string.diagnostic_advanced_api);
        advancedBtn.setLayoutParams(params);
        advancedBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, NetworkApiDiagnosticActivity.class);
            intent.putExtra("timeout_seconds", timeoutSeconds);
            startActivity(intent);
        });
        container.addView(advancedBtn);

        android.widget.Button refreshBtn = new android.widget.Button(this);
        refreshBtn.setText(R.string.refresh_diagnostic);
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        refreshParams.setMargins(0, 8, 0, 0);
        refreshBtn.setLayoutParams(refreshParams);
        refreshBtn.setOnClickListener(v -> startDiagnostic());
        container.addView(refreshBtn);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (diagnosticThread != null) {
            diagnosticThread.interrupt();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private int getErrorColor() {
        return getResources().getColor(android.R.color.holo_red_light);
    }

    private int getSuccessColor() {
        return getResources().getColor(android.R.color.holo_green_light);
    }

    private int getTextSecondaryColor() {
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorSecondary, typedValue, true);
        return typedValue.data;
    }

    private int getTextPrimaryColor() {
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorPrimary, typedValue, true);
        return typedValue.data;
    }
}
