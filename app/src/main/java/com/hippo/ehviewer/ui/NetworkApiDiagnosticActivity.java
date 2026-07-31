package com.hippo.ehviewer.ui;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.core.content.FileProvider;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.network.NetworkDiagnosticLogger;
import com.hippo.ehviewer.ui.adapter.DiagnosticExpandableAdapter;
import com.hippo.ehviewer.util.DiagnosticEndpoint;
import com.hippo.ehviewer.util.NetworkDiagnosticTool;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class NetworkApiDiagnosticActivity extends EhActivity {

    private ExpandableListView expandableList;
    private ProgressBar progressBar;
    private TextView statusText;
    private LinearLayout warningBanner;
    private TextView tabE, tabEx;
    private Button btnExport;
    private Button btnExpandCollapse;
    private Button btnCancel;
    private Spinner timeoutSpinner;

    private boolean isExEnabled;
    private volatile boolean isRunning = false;
    private boolean allExpanded = false;
    private List<DiagnosticEndpoint.EndpointCategory> categories;
    private DiagnosticExpandableAdapter adapter;
    private ExecutorService executor;
    private Handler mainHandler;
    private int timeoutSeconds = 60;
    private volatile Future<?> diagnosticFuture;

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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_api_diagnostic);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.api_diagnostic_title);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor();
        isExEnabled = Settings.isLogin();

        if (getIntent() != null && getIntent().hasExtra("timeout_seconds")) {
            timeoutSeconds = getIntent().getIntExtra("timeout_seconds", 60);
        }

        expandableList = findViewById(R.id.expandable_list);
        progressBar = findViewById(R.id.diagnostic_progress);
        statusText = findViewById(R.id.diagnostic_status);
        warningBanner = findViewById(R.id.warning_banner);
        tabE = findViewById(R.id.tab_e_hentai);
        tabEx = findViewById(R.id.tab_ex_hentai);
        btnExport = findViewById(R.id.btn_export_har);
        btnExpandCollapse = findViewById(R.id.btn_expand_collapse);
        btnCancel = findViewById(R.id.btn_cancel);
        timeoutSpinner = findViewById(R.id.timeout_spinner);

        categories = DiagnosticEndpoint.buildCategories();

        setupWarningBanner();
        setupSiteTabs();
        setupButtons();
        setupTimeoutSpinner();

        startDiagnostic();
    }

    private void setupWarningBanner() {
        warningBanner.setVisibility(View.VISIBLE);
        warningBanner.setOnClickListener(v -> warningBanner.setVisibility(View.GONE));
    }

    private void setupSiteTabs() {
        updateTabStyles();

        tabE.setOnClickListener(v -> {
            updateTabStyles();
        });

        if (isExEnabled) {
            tabEx.setOnClickListener(v -> {
                updateTabStyles();
            });
        } else {
            tabEx.setText("Ex-Hentai \uD83D\uDD12");
            tabEx.setAlpha(0.5f);
            tabEx.setOnClickListener(v -> {
                Toast.makeText(this, getString(R.string.api_diagnostic_need_login), Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void updateTabStyles() {
        tabE.setBackgroundResource(android.R.color.transparent);
        tabE.setTextColor(Color.parseColor("#FF4CAF50"));
    }

    private void setupTimeoutSpinner() {
        String[] options = getResources().getStringArray(R.array.timeout_options);
        for (int i = 0; i < options.length; i++) {
            if (Integer.parseInt(options[i]) == timeoutSeconds) {
                timeoutSpinner.setSelection(i);
                break;
            }
        }

        timeoutSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                timeoutSeconds = Integer.parseInt(parent.getItemAtPosition(position).toString());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupButtons() {
        btnExpandCollapse.setOnClickListener(v -> {
            if (allExpanded) {
                for (int i = 0; i < adapter.getGroupCount(); i++) {
                    expandableList.collapseGroup(i);
                }
            } else {
                for (int i = 0; i < adapter.getGroupCount(); i++) {
                    expandableList.expandGroup(i);
                }
            }
            allExpanded = !allExpanded;
            btnExpandCollapse.setText(allExpanded ?
                    R.string.collapse_all : R.string.expand_all);
        });

        btnExport.setOnClickListener(v -> exportHarLog());

        btnCancel.setOnClickListener(v -> cancelDiagnostic());
    }

    private void cancelDiagnostic() {
        if (diagnosticFuture != null) {
            diagnosticFuture.cancel(true);
        }
        executor.shutdownNow();
        executor = Executors.newSingleThreadExecutor();
        mainHandler.post(() -> {
            isRunning = false;
            progressBar.setVisibility(View.GONE);
            btnCancel.setVisibility(View.GONE);
            statusText.setText(R.string.diagnostic_cancelled);
            btnExport.setEnabled(adapter.hasAnyResults());
        });
    }

    private void startDiagnostic() {
        if (isRunning) return;
        isRunning = true;

        progressBar.setVisibility(View.VISIBLE);
        btnCancel.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(R.string.diagnostic_running);
        btnExport.setEnabled(false);

        NetworkDiagnosticLogger.INSTANCE.start();
        NetworkDiagnosticLogger.INSTANCE.clear();

        adapter = new DiagnosticExpandableAdapter(this, categories, isExEnabled);
        expandableList.setAdapter(adapter);

        OkHttpClient baseClient = EhApplication.getOkHttpClient(this);
        OkHttpClient diagnosticClient = NetworkDiagnosticTool.buildDiagnosticClient(
                baseClient.newBuilder()
                        .addInterceptor(NetworkDiagnosticLogger.INSTANCE.createInterceptor())
                        .eventListenerFactory(NetworkDiagnosticLogger.INSTANCE.createEventListenerFactory())
                        .build(),
                timeoutSeconds);
        final boolean exEnabled = isExEnabled;

        diagnosticFuture = executor.submit(() -> {
            int totalGroups = categories.size();
            for (int g = 0; g < totalGroups; g++) {
                DiagnosticEndpoint.EndpointCategory category = categories.get(g);
                int childIndex = 0;

                for (DiagnosticEndpoint.EndpointItem item : category.items) {
                    if (Thread.currentThread().isInterrupted()) return;

                    DiagnosticEndpoint.EndpointItem eItem = item.withSite(DiagnosticEndpoint.Site.E);
                    DiagnosticEndpoint.CheckResult eResult =
                            NetworkDiagnosticTool.checkHttpEndpoint(eItem, diagnosticClient);
                    final int groupPos = g;
                    final int childPos = childIndex;
                    mainHandler.post(() -> adapter.setResult(groupPos, childPos, eResult));
                    childIndex++;

                    if (exEnabled) {
                        if (Thread.currentThread().isInterrupted()) return;
                        DiagnosticEndpoint.EndpointItem exItem = item.withSite(DiagnosticEndpoint.Site.EX);
                        DiagnosticEndpoint.CheckResult exResult =
                                NetworkDiagnosticTool.checkHttpEndpoint(exItem, diagnosticClient);
                        final int exChildPos = childIndex;
                        mainHandler.post(() -> adapter.setResult(groupPos, exChildPos, exResult));
                        childIndex++;
                    }

                    final int progress = childIndex;
                    final String catName = category.name;
                    mainHandler.post(() -> statusText.setText(
                            getString(R.string.diagnostic_progress_format, catName, progress)));
                }
            }

            NetworkDiagnosticLogger.INSTANCE.stop();
            mainHandler.post(() -> {
                isRunning = false;
                progressBar.setVisibility(View.GONE);
                btnCancel.setVisibility(View.GONE);
                statusText.setText(getString(R.string.diagnostic_complete, NetworkDiagnosticLogger.INSTANCE.getEntryCount()));
                btnExport.setEnabled(true);
            });
        });
    }

    private void exportHarLog() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== EhViewer API 诊断报告 ===\n");
        summary.append("时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault()).format(new java.util.Date())).append("\n");
        summary.append("登录状态: ").append(isExEnabled ? "已登录" : "未登录").append("\n\n");

        for (int g = 0; g < categories.size(); g++) {
            DiagnosticEndpoint.EndpointCategory cat = categories.get(g);
            summary.append("--- ").append(cat.name).append(" ---\n");
            List<DiagnosticEndpoint.CheckResult> results = adapter.getGroupResults(g);
            if (results != null) {
                for (DiagnosticEndpoint.CheckResult r : results) {
                    if (r != null) {
                        String siteTag = r.endpoint.site == DiagnosticEndpoint.Site.E ? "[E]" : "[EX]";
                        String status = r.isReachable ? "✓ " + r.httpCode : "✗ FAIL";
                        summary.append(String.format("  %s %s ... %s (%dms)",
                                siteTag, r.endpoint.name, status, r.totalTimeMs));
                        if (r.error != null) summary.append(" - ").append(r.error);
                        summary.append("\n");
                    }
                }
            }
            summary.append("\n");
        }

        File harFile = NetworkDiagnosticLogger.INSTANCE.exportHar(summary.toString());
        if (harFile != null) {
            Toast.makeText(this,
                    getString(R.string.diagnostic_log_exported, harFile.getName()),
                    Toast.LENGTH_LONG).show();

            try {
                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".file_provider", harFile);
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("application/json");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "EhViewer Network Diagnostic HAR");
                startActivity(Intent.createChooser(shareIntent,
                        getString(R.string.share_diagnostic_log)));
            } catch (Exception e) {
                Toast.makeText(this,
                        getString(R.string.diagnostic_log_saved, harFile.getAbsolutePath()),
                        Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, R.string.diagnostic_log_export_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkDiagnosticLogger.INSTANCE.stop();
        if (diagnosticFuture != null) {
            diagnosticFuture.cancel(true);
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
