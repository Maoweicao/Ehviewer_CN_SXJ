package com.hippo.ehviewer.ui.lab;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.hippo.ehviewer.BackgroundTaskManager;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.task.BackgroundTask;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.task.ChainMergeEntry;
import com.hippo.ehviewer.task.ChainMergeStatus;
import com.hippo.ehviewer.task.PlanStatus;
import com.hippo.ehviewer.task.ProgressiveBackupTask;
import com.hippo.ehviewer.task.ProgressiveMergeAllTask;
import com.hippo.ehviewer.task.ProgressiveMergePlan;
import com.hippo.ehviewer.task.ProgressiveMergeTask;
import com.hippo.ehviewer.task.ProgressivePlanManager;
import com.hippo.ehviewer.task.ProgressiveScanTask;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.ToolbarActivity;
import com.hippo.ehviewer.ui.fragment.lab.ProgressiveChainAdapter;
import com.hippo.unifile.UniFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProgressiveManagerActivity extends ToolbarActivity
        implements ProgressiveChainAdapter.OnChainActionListener {

    private static final String TAG = "ProgressiveManager";
    private static final String PREF_NAME = "progressive_manager";
    private static final String PREF_IGNORED_CHAIN_IDS = "ignored_chain_ids";

    private TextView statusText;
    private ProgressBar progressBar;
    private MaterialButton btnScan;
    private MaterialButton btnGeneratePlan;
    private MaterialButton btnConfirmMerge;
    private RecyclerView recyclerView;
    private TextView emptyText;
    private LinearLayout stepButtons;
    private LinearLayout backupPanel;
    private CheckBox cbBackup;
    private CheckBox cbDeleteMissing;

    private ProgressiveChainAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable progressPoller;
    private boolean scanning = false;
    private boolean merging = false;
    private List<ProgressiveScanTask.ProgressiveChain> currentResults;
    private final Set<Integer> ignoredChainIds = new HashSet<>();
    private ProgressiveMergePlan currentPlan;
    private String activeTaskId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_progressive_manager);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.lab_progressive_manager);
        }
        setNavigationIcon(R.drawable.ic_back);

        statusText = findViewById(R.id.status_text);
        progressBar = findViewById(R.id.progress_bar);
        btnScan = findViewById(R.id.btn_scan);
        btnGeneratePlan = findViewById(R.id.btn_generate_plan);
        btnConfirmMerge = findViewById(R.id.btn_confirm_merge);
        stepButtons = findViewById(R.id.step_buttons);
        backupPanel = findViewById(R.id.backup_panel);
        cbBackup = findViewById(R.id.cb_backup);
        cbDeleteMissing = findViewById(R.id.cb_delete_missing);
        recyclerView = findViewById(R.id.recycler_view);
        emptyText = findViewById(R.id.empty_text);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProgressiveChainAdapter();
        adapter.setOnChainActionListener(this);
        recyclerView.setAdapter(adapter);

        btnScan.setOnClickListener(v -> startScan());
        btnGeneratePlan.setOnClickListener(v -> generateMergePlan());
        btnConfirmMerge.setOnClickListener(v -> showConfirmMergeDialog());

        loadIgnoredChains();
        loadExistingResults();
        restoreActiveTask();
    }

    private void restoreActiveTask() {
        var activeTask = BackgroundTaskManager.getInstance()
                .getTaskStatusManager().getActiveUniqueNonDownloadTask();
        if (activeTask == null) return;

        activeTaskId = activeTask.getTaskId();
        var taskType = activeTask.getTaskType();

        if (taskType == BackgroundTask.TaskType.SCAN) {
            scanning = true;
            disableAllButtons();
            btnScan.setText(R.string.progressive_scan_scanning);
            statusText.setText(R.string.progressive_scan_scanning);
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(activeTask.getProgressPercentage());
            recyclerView.setVisibility(View.GONE);
            emptyText.setVisibility(View.GONE);
            btnGeneratePlan.setVisibility(View.GONE);
            btnConfirmMerge.setVisibility(View.GONE);
            backupPanel.setVisibility(View.GONE);
            startScanPoller();
        } else if (taskType == BackgroundTask.TaskType.MERGE) {
            merging = true;
            disableAllButtons();
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(activeTask.getProgressPercentage());
            if (activeTask.getProgressDetail() != null) {
                statusText.setText(activeTask.getProgressDetail());
            }
            startMergePoller();
        }
    }

    private void startScanPoller() {
        progressPoller = new Runnable() {
            @Override
            public void run() {
                if (isDestroyed() || isFinishing()) return;
                if (activeTaskId == null) return;
                var info = BackgroundTaskManager.getInstance()
                        .getTaskStatusManager().getTaskInfo(activeTaskId);
                if (info != null) {
                    int progress = info.getProgressPercentage();
                    if (progress >= 0) progressBar.setProgress(progress);
                    String detail = info.getProgressDetail();
                    if (detail != null && !detail.isEmpty()) statusText.setText(detail);
                    if (info.isCompleted()) { activeTaskId = null; onScanComplete(); return; }
                    if (info.isCancelled()) { activeTaskId = null; onScanFailed("Scan cancelled"); return; }
                    if (info.getErrorMessage() != null) { activeTaskId = null; onScanFailed(info.getErrorMessage()); return; }
                }
                mainHandler.postDelayed(this, 500);
            }
        };
        mainHandler.postDelayed(progressPoller, 500);
    }

    private void startMergePoller() {
        progressPoller = new Runnable() {
            @Override
            public void run() {
                if (isDestroyed() || isFinishing()) return;
                if (activeTaskId == null) return;
                var info = BackgroundTaskManager.getInstance()
                        .getTaskStatusManager().getTaskInfo(activeTaskId);
                if (info != null) {
                    int progress = info.getProgressPercentage();
                    if (progress >= 0) progressBar.setProgress(progress);
                    String detail = info.getProgressDetail();
                    if (detail != null && !detail.isEmpty()) statusText.setText(detail);
                    if (info.isCompleted()) { activeTaskId = null; onPlanMergeComplete(); return; }
                    if (info.isCancelled()) {
                        activeTaskId = null; merging = false;
                        progressBar.setVisibility(View.GONE);
                        enableAllButtons(currentResults != null && !currentResults.isEmpty());
                        statusText.setText("Merge cancelled");
                        return;
                    }
                    if (info.getErrorMessage() != null) {
                        activeTaskId = null; merging = false;
                        progressBar.setVisibility(View.GONE);
                        enableAllButtons(currentResults != null && !currentResults.isEmpty());
                        statusText.setText("Merge failed: " + info.getErrorMessage());
                        return;
                    }
                }
                mainHandler.postDelayed(this, 800);
            }
        };
        mainHandler.postDelayed(progressPoller, 800);
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
                showStepTwo();
            }
        }

        ProgressiveMergePlan plan = ProgressivePlanManager.loadLatestMergePlan();
        if (plan != null && !plan.isExpired()) {
            currentPlan = plan;
            showStepThree();
            statusText.setText(getString(R.string.progressive_plan_generated, "ProgressiveScan", plan.getChains().size()));
            cbBackup.setChecked(plan.getBackupEnabled());
            cbDeleteMissing.setChecked(plan.getDeleteMissingDownloadTasks());
        }
    }

    private void showStepTwo() {
        btnScan.setText(R.string.progressive_scan_refresh);
        btnGeneratePlan.setVisibility(View.VISIBLE);
        btnGeneratePlan.setEnabled(true);
        btnConfirmMerge.setVisibility(View.GONE);
        backupPanel.setVisibility(View.GONE);
    }

    private void showStepThree() {
        btnScan.setText(R.string.progressive_scan_refresh);
        btnGeneratePlan.setVisibility(View.VISIBLE);
        btnGeneratePlan.setEnabled(true);
        btnConfirmMerge.setVisibility(View.VISIBLE);
        btnConfirmMerge.setEnabled(true);
        backupPanel.setVisibility(View.VISIBLE);
    }

    private void generateMergePlan() {
        if (currentResults == null || currentResults.isEmpty()) {
            Toast.makeText(this, R.string.progressive_scan_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        String planId = "plan_" + System.currentTimeMillis();
        ProgressiveMergePlan plan = new ProgressiveMergePlan(planId);
        plan.setStatus(PlanStatus.PENDING);
        plan.setBackupEnabled(cbBackup.isChecked());
        plan.setDeleteMissingDownloadTasks(cbDeleteMissing.isChecked());
        plan.setBackupFilePath(null);
        plan.setCompletedChains(0);

        for (ProgressiveScanTask.ProgressiveChain chain : currentResults) {
            List<ProgressiveScanTask.FolderInfo> folders = chain.getFolders();
            if (folders.isEmpty()) continue;

            long targetGid = adapter.getSelectedTargetGid(chain.getId(), chain);
            if (targetGid <= 0) {
                var mc = chain.getMostComplete();
                targetGid = mc != null ? mc.getGid() : folders.get(0).getGid();
            }

            List<Long> sourceGids = new ArrayList<>();
            for (var f : folders) {
                if (f.getGid() != targetGid) {
                    sourceGids.add(f.getGid());
                }
            }

            ChainMergeEntry entry = new ChainMergeEntry(
                    chain.getId(),
                    chain.getDisplayName(),
                    targetGid,
                    sourceGids,
                    folders.size()
            );
            plan.getChains().add(entry);
        }

        if (ProgressivePlanManager.saveMergePlan(plan)) {
            currentPlan = plan;
            showStepThree();
            String msg = getString(R.string.progressive_plan_generated, "ProgressiveScan", plan.getChains().size());
            statusText.setText(msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Failed to save merge plan", Toast.LENGTH_LONG).show();
        }
    }

    private void showConfirmMergeDialog() {
        if (currentPlan == null || currentPlan.getChains().isEmpty()) {
            Toast.makeText(this, "No merge plan available", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean willBackup = cbBackup.isChecked();
        boolean willDeleteMissing = cbDeleteMissing.isChecked();
        String msg = getString(R.string.progressive_merge_all_confirm, currentPlan.getChains().size());
        if (willBackup) {
            msg += "\n\n" + getString(R.string.progressive_backup_checkbox);
        }
        if (willDeleteMissing) {
            msg += "\n\n" + getString(R.string.progressive_delete_missing_checkbox);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.progressive_merge_all)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    currentPlan.setBackupEnabled(willBackup);
                    currentPlan.setDeleteMissingDownloadTasks(willDeleteMissing);
                    ProgressivePlanManager.saveMergePlan(currentPlan);
                    if (willBackup) {
                        startBackupThenMerge();
                    } else {
                        startPlanMerge();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startBackupThenMerge() {
        if (merging || currentResults == null || currentResults.isEmpty()) return;

        BackgroundTaskManager taskManager = BackgroundTaskManager.getInstance();
        if (taskManager.getTaskStatusManager().getActiveUniqueNonDownloadTask() != null) {
            Toast.makeText(this, R.string.background_task_unique_running, Toast.LENGTH_SHORT).show();
            return;
        }

        merging = true;
        disableAllButtons();
        statusText.setText(R.string.progressive_backup_task_name);

        ProgressiveBackupTask backupTask = new ProgressiveBackupTask(this, currentResults);
        taskManager.submitBackgroundTask(backupTask);

        final String taskId = backupTask.getTaskId();
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isDestroyed() || isFinishing()) return;
                var info = taskManager.getTaskStatusManager().getTaskInfo(taskId);
                if (info != null && info.isCompleted()) {
                    currentPlan.setBackupFilePath(backupTask.getBackupFileNames().isEmpty()
                            ? null : backupTask.getBackupFileNames().get(0));
                    ProgressivePlanManager.saveMergePlan(currentPlan);
                    statusText.setText(R.string.progressive_merge_task_name);
                    startPlanMerge();
                } else if (info != null && (info.isCancelled() || info.getErrorMessage() != null)) {
                    merging = false;
                    enableAllButtons(currentResults != null && !currentResults.isEmpty());
                    statusText.setText("Backup failed: " + (info.getErrorMessage() != null ? info.getErrorMessage() : "cancelled"));
                } else {
                    mainHandler.postDelayed(this, 800);
                }
            }
        }, 800);
    }

    private void startPlanMerge() {
        if (currentPlan == null || currentPlan.getChains().isEmpty()) {
            merging = false;
            enableAllButtons(currentResults != null && !currentResults.isEmpty());
            return;
        }

        BackgroundTaskManager taskManager = BackgroundTaskManager.getInstance();
        if (taskManager.getTaskStatusManager().getActiveUniqueNonDownloadTask() != null) {
            Toast.makeText(this, R.string.background_task_unique_running, Toast.LENGTH_SHORT).show();
            merging = false;
            enableAllButtons(currentResults != null && !currentResults.isEmpty());
            return;
        }

        currentPlan.setStatus(PlanStatus.IN_PROGRESS);
        ProgressivePlanManager.saveMergePlan(currentPlan);

        int totalChains = currentPlan.getChains().size();
        statusText.setText(getString(R.string.progressive_merge_all_started, totalChains));

        ProgressiveMergeAllTask mergeAllTask = new ProgressiveMergeAllTask(this, currentPlan);
        taskManager.submitBackgroundTask(mergeAllTask);

        activeTaskId = mergeAllTask.getTaskId();
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        startMergePoller();
    }

    private void onPlanMergeComplete() {
        merging = false;
        progressBar.setVisibility(View.GONE);

        boolean deleteMissing = false;
        if (currentPlan != null) {
            deleteMissing = currentPlan.getDeleteMissingDownloadTasks();
            boolean hasFailures = false;
            for (ChainMergeEntry entry : currentPlan.getChains()) {
                if (entry.getStatus() == ChainMergeStatus.FAILED) {
                    hasFailures = true;
                    break;
                }
            }
            currentPlan.setStatus(hasFailures ? PlanStatus.FAILED : PlanStatus.COMPLETED);
            ProgressivePlanManager.saveMergePlan(currentPlan);
        }

        statusText.setText(R.string.progressive_merge_all_done);
        Toast.makeText(this, R.string.progressive_merge_all_done, Toast.LENGTH_SHORT).show();
        enableAllButtons(false);
        btnScan.setText(R.string.progressive_scan_refresh);
        currentPlan = null;

        if (deleteMissing) {
            deleteMissingGalleryTasks();
        }
        startScan();
    }

    /**
     * 合并完成后删除下载列表中已不存在的画廊任务。
     * 递进合并会删除源文件夹，但其下载记录可能仍残留在下载列表中，
     * 该任务会清理这些失效的记录。
     */
    private void deleteMissingGalleryTasks() {
        final com.hippo.ehviewer.download.DownloadManager dm =
                com.hippo.ehviewer.EhApplication.getDownloadManager(this);
        List<DownloadInfo> all = dm.getAllDownloadInfoList();
        if (all == null || all.isEmpty()) return;

        // 拷贝快照，避免在迭代过程中修改底层 LinkedList 触发 ConcurrentModificationException
        List<DownloadInfo> snapshot = new ArrayList<>(all);
        int deleted = 0;
        for (DownloadInfo info : snapshot) {
            UniFile dir = SpiderDen.getExistingGalleryDownloadDir(info);
            if (dir == null || !dir.exists()) {
                dm.deleteDownload(info.gid);
                deleted++;
            }
        }
        if (deleted > 0) {
            Toast.makeText(this, getString(R.string.progressive_delete_missing_done, deleted), Toast.LENGTH_LONG).show();
            Log.i(TAG, "deleteMissingGalleryTasks: deleted " + deleted + " stale download tasks");
        }
    }

    private void startScan() {
        if (scanning) return;

        BackgroundTaskManager taskManager = BackgroundTaskManager.getInstance();
        if (taskManager.getTaskStatusManager().getActiveUniqueNonDownloadTask() != null) {
            Toast.makeText(this, R.string.background_task_unique_running, Toast.LENGTH_SHORT).show();
            return;
        }

        scanning = true;
        disableAllButtons();
        btnScan.setText(R.string.progressive_scan_scanning);
        statusText.setText(R.string.progressive_scan_scanning);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        recyclerView.setVisibility(View.GONE);
        emptyText.setVisibility(View.GONE);
        btnGeneratePlan.setVisibility(View.GONE);
        btnConfirmMerge.setVisibility(View.GONE);
        backupPanel.setVisibility(View.GONE);
        currentPlan = null;

        ProgressiveScanTask task = new ProgressiveScanTask(this);
        taskManager.submitBackgroundTask(task);

        activeTaskId = task.getTaskId();
        startScanPoller();
    }

    private void onScanComplete() {
        scanning = false;
        progressBar.setVisibility(View.GONE);

        List<ProgressiveScanTask.ProgressiveChain> results = ProgressiveScanTask.loadResults();
        results = filterIgnored(results);
        currentResults = results;
        if (results != null && !results.isEmpty()) {
            showResults(results);
            statusText.setText(getString(R.string.progressive_chain_count, results.size()));
            showStepTwo();
        } else {
            recyclerView.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
            statusText.setText(R.string.progressive_scan_empty);
        }

        enableAllButtons(results != null && !results.isEmpty());
        btnScan.setText(R.string.progressive_scan_refresh);
        currentPlan = null;
    }

    private void onScanFailed(String error) {
        scanning = false;
        progressBar.setVisibility(View.GONE);
        statusText.setText(getString(R.string.progressive_scan_error, error));
        Toast.makeText(this, getString(R.string.progressive_scan_error, error), Toast.LENGTH_LONG).show();
        enableAllButtons(currentResults != null && !currentResults.isEmpty());
        btnScan.setText(R.string.progressive_scan_start);
    }

    private void showResults(List<ProgressiveScanTask.ProgressiveChain> results) {
        adapter.setChains(results);
        recyclerView.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
    }

    private void disableAllButtons() {
        btnScan.setEnabled(false);
        btnGeneratePlan.setEnabled(false);
        btnConfirmMerge.setEnabled(false);
        cbBackup.setEnabled(false);
        cbDeleteMissing.setEnabled(false);
    }

    private void enableAllButtons(boolean hasResults) {
        btnScan.setEnabled(true);
        if (hasResults) {
            btnGeneratePlan.setEnabled(true);
            btnGeneratePlan.setVisibility(View.VISIBLE);
            if (currentPlan != null) {
                btnConfirmMerge.setEnabled(true);
                btnConfirmMerge.setVisibility(View.VISIBLE);
                backupPanel.setVisibility(View.VISIBLE);
            }
        }
        cbBackup.setEnabled(true);
        cbDeleteMissing.setEnabled(true);
    }

    private void disableButtons() {
        btnScan.setEnabled(false);
        btnGeneratePlan.setEnabled(false);
        btnConfirmMerge.setEnabled(false);
    }

    private void enableButtons() {
        btnScan.setEnabled(true);
        btnGeneratePlan.setEnabled(true);
        btnConfirmMerge.setEnabled(true);
    }

    @Override
    public void onMerge(long targetGid, List<Long> sourceGids) {
        if (sourceGids.isEmpty()) {
            Toast.makeText(this, "No source folders to merge", Toast.LENGTH_SHORT).show();
            return;
        }

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
                    statusText.setText(R.string.progressive_merge_complete);
                    enableButtons();
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
                btnGeneratePlan.setVisibility(View.GONE);
                btnConfirmMerge.setVisibility(View.GONE);
                backupPanel.setVisibility(View.GONE);
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
