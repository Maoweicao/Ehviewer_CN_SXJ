package com.hippo.ehviewer.ui.lab;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.hippo.ehviewer.BackgroundTaskManager;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.task.ProgressiveMergeTask;
import com.hippo.ehviewer.task.ProgressiveScanTask;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.EhActivity;
import com.hippo.ehviewer.ui.fragment.lab.ProgressiveChainAdapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProgressiveManagerActivity extends EhActivity
        implements ProgressiveChainAdapter.OnChainActionListener {

    private static final String TAG = "ProgressiveManager";
    private static final String PREF_NAME = "progressive_manager";
    private static final String PREF_IGNORED_CHAIN_IDS = "ignored_chain_ids";

    private TextView statusText;
    private ProgressBar progressBar;
    private MaterialButton btnScan;
    private MaterialButton btnMergeAll;
    private RecyclerView recyclerView;
    private TextView emptyText;

    private ProgressiveChainAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable progressPoller;
    private boolean scanning = false;
    private boolean merging = false;
    private List<ProgressiveScanTask.ProgressiveChain> currentResults;
    private final Set<Integer> ignoredChainIds = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_progressive_manager);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        statusText = findViewById(R.id.status_text);
        progressBar = findViewById(R.id.progress_bar);
        btnScan = findViewById(R.id.btn_scan);
        btnMergeAll = findViewById(R.id.btn_merge_all);
        recyclerView = findViewById(R.id.recycler_view);
        emptyText = findViewById(R.id.empty_text);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProgressiveChainAdapter();
        adapter.setOnChainActionListener(this);
        recyclerView.setAdapter(adapter);

        btnScan.setOnClickListener(v -> startScan());
        btnMergeAll.setOnClickListener(v -> showMergeAllConfirm());

        loadIgnoredChains();
        loadExistingResults();
    }

    private void loadIgnoredChains() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet(PREF_IGNORED_CHAIN_IDS, null);
        ignoredChainIds.clear();
        if (saved != null) {
            for (String s : saved) {
                try {
                    ignoredChainIds.add(Integer.parseInt(s));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private void saveIgnoredChains() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        Set<String> toSave = new HashSet<>();
        for (Integer id : ignoredChainIds) {
            toSave.add(String.valueOf(id));
        }
        prefs.edit().putStringSet(PREF_IGNORED_CHAIN_IDS, toSave).apply();
    }

    private List<ProgressiveScanTask.ProgressiveChain> filterIgnored(List<ProgressiveScanTask.ProgressiveChain> results) {
        if (results == null) return null;
        List<ProgressiveScanTask.ProgressiveChain> filtered = new ArrayList<>();
        for (ProgressiveScanTask.ProgressiveChain chain : results) {
            if (!ignoredChainIds.contains(chain.getId())) {
                filtered.add(chain);
            }
        }
        return filtered;
    }

    private void loadExistingResults() {
        List<ProgressiveScanTask.ProgressiveChain> results = ProgressiveScanTask.loadResults();
        if (results != null && !results.isEmpty()) {
            results = filterIgnored(results);
            if (!results.isEmpty()) {
                currentResults = results;
                showResults(results);
                statusText.setText(getString(R.string.progressive_chain_count, results.size()));
                btnScan.setText(R.string.progressive_scan_refresh);
                btnMergeAll.setVisibility(View.VISIBLE);
            }
        }
    }

    private void showMergeAllConfirm() {
        if (currentResults == null || currentResults.isEmpty()) return;
        int count = currentResults.size();
        new AlertDialog.Builder(this)
                .setTitle(R.string.progressive_merge_all)
                .setMessage(getString(R.string.progressive_merge_all_confirm, count))
                .setPositiveButton(android.R.string.ok, (d, w) -> startMergeAll())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startMergeAll() {
        if (merging || currentResults == null || currentResults.isEmpty()) return;

        Log.i(TAG, "startMergeAll: totalChains=" + currentResults.size());

        BackgroundTaskManager taskManager = BackgroundTaskManager.getInstance();
        if (taskManager.getTaskStatusManager().getActiveUniqueNonDownloadTask() != null) {
            Toast.makeText(this, R.string.background_task_unique_running, Toast.LENGTH_SHORT).show();
            return;
        }

        merging = true;
        disableButtons();

        int totalChains = currentResults.size();
        final int[] completed = {0};
        final int[] failed = {0};

        Runnable processNext = new Runnable() {
            @Override
            public void run() {
                if (completed[0] + failed[0] >= totalChains) {
                    Log.i(TAG, "startMergeAll: all " + totalChains + " chains processed, failed=" + failed[0]);
                    onMergeAllComplete(totalChains, failed[0]);
                    return;
                }

                ProgressiveScanTask.ProgressiveChain chain = currentResults.get(completed[0] + failed[0]);
                var mostComplete = chain.getMostComplete();
                long targetGid = mostComplete != null ? mostComplete.getGid() : chain.getFolders().get(0).getGid();

                Log.i(TAG, "startMergeAll: chain " + (completed[0] + failed[0] + 1) + "/" + totalChains
                        + " targetGid=" + targetGid + " name=" + chain.getDisplayName());

                List<Long> sourceGids = new ArrayList<>();
                for (var f : chain.getFolders()) {
                    if (f.getGid() != targetGid) {
                        sourceGids.add(f.getGid());
                    }
                }

                if (sourceGids.isEmpty()) {
                    completed[0]++;
                    mainHandler.post(this);
                    return;
                }

                statusText.setText(getString(R.string.progressive_merge_all_started, totalChains));

                ProgressiveMergeTask mergeTask = new ProgressiveMergeTask(ProgressiveManagerActivity.this, targetGid, sourceGids);
                BackgroundTaskManager.getInstance().submitBackgroundTask(mergeTask);

                pollMergeTask(mergeTask.getTaskId(),
                        () -> { completed[0]++; mainHandler.post(this); },
                        () -> { failed[0]++; mainHandler.post(this); });
            }
        };

        mainHandler.post(processNext);
    }

    private void pollMergeTask(String taskId, Runnable onSuccess, Runnable onFailure) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isDestroyed() || isFinishing()) return;
                var info = BackgroundTaskManager.getInstance().getTaskStatusManager().getTaskInfo(taskId);
                if (info != null && info.isCompleted()) {
                    onSuccess.run();
                } else if (info != null && (info.isCancelled() || info.getErrorMessage() != null)) {
                    Log.w(TAG, "Merge task failed: " + taskId
                            + " error=" + info.getErrorMessage()
                            + " cancelled=" + info.isCancelled());
                    onFailure.run();
                } else {
                    mainHandler.postDelayed(this, 800);
                }
            }
        }, 800);
    }

    private void onMergeAllComplete(int totalCount, int failedCount) {
        Log.i(TAG, "onMergeAllComplete: " + totalCount + " chains, " + failedCount + " failed");
        merging = false;
        enableButtons();
        if (failedCount > 0) {
            String msg = getString(R.string.progressive_merge_all_partial, (totalCount - failedCount), failedCount);
            statusText.setText(msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        } else {
            statusText.setText(R.string.progressive_merge_all_done);
            Toast.makeText(this, R.string.progressive_merge_all_done, Toast.LENGTH_SHORT).show();
        }
        startScan();
    }

    private void startScan() {
        if (scanning) return;

        BackgroundTaskManager taskManager = BackgroundTaskManager.getInstance();
        if (taskManager.getTaskStatusManager().getActiveUniqueNonDownloadTask() != null) {
            Toast.makeText(this, R.string.background_task_unique_running, Toast.LENGTH_SHORT).show();
            return;
        }

        scanning = true;
        disableButtons();
        btnScan.setText(R.string.progressive_scan_scanning);
        statusText.setText(R.string.progressive_scan_scanning);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        recyclerView.setVisibility(View.GONE);
        emptyText.setVisibility(View.GONE);
        btnMergeAll.setVisibility(View.GONE);

        ProgressiveScanTask task = new ProgressiveScanTask(this);
        taskManager.submitBackgroundTask(task);

        final String taskId = task.getTaskId();
        progressPoller = new Runnable() {
            @Override
            public void run() {
                if (!scanning) return;
                var info = taskManager.getTaskStatusManager().getTaskInfo(taskId);
                if (info != null) {
                    int progress = info.getProgressPercentage();
                    if (progress >= 0) progressBar.setProgress(progress);
                    String detail = info.getProgressDetail();
                    if (detail != null && !detail.isEmpty()) statusText.setText(detail);
                    if (info.isCompleted()) { onScanComplete(); return; }
                    if (info.isCancelled()) { onScanFailed("Scan cancelled"); return; }
                    if (info.getErrorMessage() != null) { onScanFailed(info.getErrorMessage()); return; }
                }
                mainHandler.postDelayed(this, 500);
            }
        };
        mainHandler.postDelayed(progressPoller, 500);
    }

    private void onScanComplete() {
        scanning = false;
        enableButtons();
        btnScan.setText(R.string.progressive_scan_refresh);
        progressBar.setVisibility(View.GONE);

        List<ProgressiveScanTask.ProgressiveChain> results = ProgressiveScanTask.loadResults();
        results = filterIgnored(results);
        currentResults = results;
        if (results != null && !results.isEmpty()) {
            showResults(results);
            statusText.setText(getString(R.string.progressive_chain_count, results.size()));
            btnMergeAll.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
            statusText.setText(R.string.progressive_scan_empty);
            btnMergeAll.setVisibility(View.GONE);
        }
    }

    private void onScanFailed(String error) {
        scanning = false;
        enableButtons();
        btnScan.setText(R.string.progressive_scan_start);
        progressBar.setVisibility(View.GONE);
        statusText.setText(getString(R.string.progressive_scan_error, error));
        Toast.makeText(this, getString(R.string.progressive_scan_error, error), Toast.LENGTH_LONG).show();
    }

    private void showResults(List<ProgressiveScanTask.ProgressiveChain> results) {
        adapter.setChains(results);
        recyclerView.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
    }

    private void disableButtons() {
        btnScan.setEnabled(false);
        btnMergeAll.setEnabled(false);
    }

    private void enableButtons() {
        btnScan.setEnabled(true);
        btnMergeAll.setEnabled(true);
    }

    @Override
    public void onMerge(long targetGid, List<Long> sourceGids) {
        if (sourceGids.isEmpty()) {
            Log.w(TAG, "onMerge: no source folders to merge");
            Toast.makeText(this, "No source folders to merge", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.i(TAG, "onMerge: targetGid=" + targetGid + ", sourceGids=" + sourceGids + ", count=" + sourceGids.size());

        BackgroundTaskManager taskManager = BackgroundTaskManager.getInstance();
        if (taskManager.getTaskStatusManager().getActiveUniqueNonDownloadTask() != null) {
            Toast.makeText(this, R.string.background_task_unique_running, Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressiveMergeTask mergeTask = new ProgressiveMergeTask(this, targetGid, sourceGids);
        taskManager.submitBackgroundTask(mergeTask);

        Toast.makeText(this, R.string.progressive_merge_started, Toast.LENGTH_SHORT).show();
        statusText.setText(getString(R.string.progressive_merge_task_desc, sourceGids.size()));
        disableButtons();

        final String taskId = mergeTask.getTaskId();
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isDestroyed() || isFinishing()) return;
                var info = taskManager.getTaskStatusManager().getTaskInfo(taskId);
                if (info != null && info.isCompleted()) {
                    Log.i(TAG, "onMerge: task completed, targetGid=" + targetGid);
                    statusText.setText(R.string.progressive_merge_complete);
                    enableButtons();
                    // Re-scan to reflect merged/deleted folders
                    startScan();
                } else if (info != null && (info.isCancelled() || info.getErrorMessage() != null)) {
                    statusText.setText(R.string.progressive_merge_failed);
                    enableButtons();
                } else {
                    mainHandler.postDelayed(this, 1000);
                }
            }
        }, 1000);
    }

    @Override
    public void onIgnore(ProgressiveScanTask.ProgressiveChain chain) {
        if (currentResults != null) {
            ignoredChainIds.add(chain.getId());
            saveIgnoredChains();
            currentResults.remove(chain);
            showResults(currentResults);
            if (currentResults.isEmpty()) {
                recyclerView.setVisibility(View.GONE);
                emptyText.setVisibility(View.VISIBLE);
                statusText.setText(R.string.progressive_scan_empty);
                btnMergeAll.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public void onView(ProgressiveScanTask.ProgressiveChain chain) {
        var mostComplete = chain.getMostComplete();
        long gid = mostComplete != null ? mostComplete.getGid() : chain.getFolders().get(0).getGid();
        DownloadInfo info = EhDB.getDownloadInfo(gid);
        if (info == null) {
            Toast.makeText(this, "Download record not found for gid=" + gid, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, GalleryActivity.class);
        intent.setAction(GalleryActivity.ACTION_EH);
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, info);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (progressPoller != null) {
            mainHandler.removeCallbacks(progressPoller);
        }
    }
}
