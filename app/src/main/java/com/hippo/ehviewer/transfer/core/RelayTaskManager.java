/*
 * Copyright 2025 EhViewer Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.transfer.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.DownloadService;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.transfer.data.ConnectedDevice;
import com.hippo.ehviewer.transfer.data.RelayTask;
import com.hippo.ehviewer.transfer.log.TransferLogger;
import com.hippo.unifile.UniFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 接力下载任务管理器
 *
 * 功能：
 * 1. 管理接力任务的生命周期
 * 2. 监听下载进度
 * 3. 自动打包完成的下载
 * 4. 任务持久化
 */
public class RelayTaskManager {

    private static final String TAG = "RelayTaskManager";
    private static final String PREFS_NAME = "relay_tasks";
    private static final String KEY_TASKS = "tasks";

    private static RelayTaskManager instance;

    private Context context;
    private SharedPreferences prefs;
    private Handler mainHandler;
    private DownloadManager downloadManager;

    // 任务存储 (key: taskId)
    private ConcurrentHashMap<String, RelayTask> tasks = new ConcurrentHashMap<>();

    // GID到taskId的映射
    private ConcurrentHashMap<Long, String> gidToTaskMap = new ConcurrentHashMap<>();

    private List<RelayTaskListener> listeners = new ArrayList<>();

    private RelayTaskManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.downloadManager = EhApplication.getDownloadManager(this.context);

        // 加载持久化的任务
        loadTasks();

        // 注册下载监听器
        registerDownloadListener();
    }

    public static synchronized RelayTaskManager getInstance(Context context) {
        if (instance == null) {
            instance = new RelayTaskManager(context);
        }
        return instance;
    }

    // ==================== 任务管理 ====================

    /**
     * 创建新的接力任务
     */
    public RelayTask createTask(long gid, String token, String title, String titleJpn,
                                String thumb, int category, String posted, String uploader,
                                float rating, int pages,
                                String sourceDevice, String sourceDeviceId,
                                String sourceDeviceHost, int sourceDevicePort,
                                String targetDevice, String targetDeviceId,
                                String priority, boolean autoReturn) {
        RelayTask task = new RelayTask(gid, token, title);
        task.setTitleJpn(titleJpn);
        task.setThumb(thumb);
        task.setCategory(category);
        task.setPosted(posted);
        task.setUploader(uploader);
        task.setRating(rating);
        task.setPages(pages);
        task.setSourceDevice(sourceDevice);
        task.setSourceDeviceId(sourceDeviceId);
        task.setSourceDeviceHost(sourceDeviceHost);
        task.setSourceDevicePort(sourceDevicePort);
        task.setTargetDevice(targetDevice);
        task.setTargetDeviceId(targetDeviceId);
        task.setPriority(priority);
        task.setAutoReturn(autoReturn);

        // 根据方向设置状态
        if (targetDevice != null) {
            // 我委托别人
            task.setDirection(RelayTask.DIRECTION_OUTGOING);
            task.setStatus(RelayTask.STATUS_PENDING);
        } else {
            // 别人委托我
            task.setDirection(RelayTask.DIRECTION_INCOMING);
            task.setStatus(RelayTask.STATUS_PENDING);
        }

        tasks.put(task.getTaskId(), task);
        gidToTaskMap.put(gid, task.getTaskId());

        // 持久化
        saveTasks();

        // 通知监听器
        notifyTaskCreated(task);

        TransferLogger.getInstance().d(TAG, "创建接力任务: taskId=" + task.getTaskId() + ", gid=" + gid
                + ", title=" + title + ", sourceDevice=" + sourceDevice + ", targetDevice=" + targetDevice);
        TransferLogger.getInstance().i(TAG, "接力任务已创建: taskId=" + task.getTaskId() + ", gid=" + gid
                + ", direction=" + task.getDirection());
        return task;
    }

    /**
     * 创建入站任务（别人委托我的）
     */
    public RelayTask createIncomingTask(long gid, String token, String title, String titleJpn,
                                        String thumb, int category, String posted, String uploader,
                                        float rating, int pages,
                                        String sourceDevice, String sourceDeviceId,
                                        String sourceDeviceHost, int sourceDevicePort,
                                        String priority, boolean autoReturn) {
        return createTask(gid, token, title, titleJpn, thumb, category, posted, uploader,
                rating, pages, sourceDevice, sourceDeviceId, sourceDeviceHost, sourceDevicePort,
                null, null, priority, autoReturn);
    }

    /**
     * 获取任务
     */
    public RelayTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 通过GID获取任务
     */
    public RelayTask getTaskByGid(long gid) {
        String taskId = gidToTaskMap.get(gid);
        if (taskId != null) {
            return tasks.get(taskId);
        }
        return null;
    }

    /**
     * 获取所有任务
     */
    public List<RelayTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * 按状态筛选任务
     */
    public List<RelayTask> getTasksByStatus(String status) {
        List<RelayTask> result = new ArrayList<>();
        for (RelayTask task : tasks.values()) {
            if ("all".equals(status) || task.getStatus().equals(status)) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * 按方向筛选任务
     */
    public List<RelayTask> getTasksByDirection(String direction) {
        List<RelayTask> result = new ArrayList<>();
        for (RelayTask task : tasks.values()) {
            if ("all".equals(direction) || task.getDirection().equals(direction)) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * 按状态和方向筛选任务
     */
    public List<RelayTask> getTasks(String status, String direction) {
        List<RelayTask> result = new ArrayList<>();
        for (RelayTask task : tasks.values()) {
            boolean statusMatch = "all".equals(status) || task.getStatus().equals(status);
            boolean directionMatch = "all".equals(direction) || task.getDirection().equals(direction);
            if (statusMatch && directionMatch) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * 接受任务
     */
    public boolean acceptTask(String taskId) {
        return acceptTask(taskId, null, null);
    }

    /**
     * 接受任务（带设备信息）
     *
     * @param taskId          任务ID
     * @param acceptedDevice  接受方设备名称
     * @param acceptedDeviceId 接受方设备ID
     */
    public boolean acceptTask(String taskId, String acceptedDevice, String acceptedDeviceId) {
        TransferLogger.getInstance().d(TAG, "接受接力任务: taskId=" + taskId
                + ", acceptedDevice=" + acceptedDevice + ", acceptedDeviceId=" + acceptedDeviceId);
        RelayTask task = tasks.get(taskId);
        if (task == null || !task.isPending()) {
            TransferLogger.getInstance().w(TAG, "接受接力任务失败，任务不存在或非待处理状态: " + taskId);
            return false;
        }

        task.setStatus(RelayTask.STATUS_ACCEPTED);
        task.setAcceptedDevice(acceptedDevice);
        task.setAcceptedDeviceId(acceptedDeviceId);
        task.setAcceptedTime(System.currentTimeMillis());
        saveTasks();
        notifyTaskUpdated(task);

        TransferLogger.getInstance().i(TAG, "任务已接受: taskId=" + taskId + ", acceptedDevice=" + acceptedDevice);
        return true;
    }

    /**
     * 拒绝任务
     */
    public boolean rejectTask(String taskId) {
        TransferLogger.getInstance().d(TAG, "拒绝接力任务: taskId=" + taskId);
        RelayTask task = tasks.get(taskId);
        if (task == null || !task.isPending()) {
            TransferLogger.getInstance().w(TAG, "拒绝接力任务失败，任务不存在或非待处理状态: " + taskId);
            return false;
        }

        task.setStatus(RelayTask.STATUS_REJECTED);
        saveTasks();
        notifyTaskUpdated(task);

        TransferLogger.getInstance().i(TAG, "任务已拒绝: " + taskId);
        return true;
    }

    /**
     * 开始下载（接受后调用）
     */
    public boolean startDownload(String taskId) {
        TransferLogger.getInstance().d(TAG, "开始下载接力任务: taskId=" + taskId);
        RelayTask task = tasks.get(taskId);
        if (task == null || !task.isAccepted()) {
            TransferLogger.getInstance().w(TAG, "开始下载失败，任务不存在或未接受: " + taskId);
            return false;
        }

        // 通过 DownloadService 启动下载
        long gid = task.getGid();
        com.hippo.lib.yorozuya.collect.LongList gidList = 
            new com.hippo.lib.yorozuya.collect.LongList(1);
        gidList.add(gid);

        // 在主线程执行
        mainHandler.post(() -> {
            DownloadService.startRangeDownload(context, gidList);
        });

        task.setStatus(RelayTask.STATUS_DOWNLOADING);
        saveTasks();
        notifyTaskUpdated(task);

        TransferLogger.getInstance().i(TAG, "开始下载: taskId=" + taskId + ", gid=" + gid);
        return true;
    }

    /**
     * 取消任务
     */
    public boolean cancelTask(String taskId) {
        TransferLogger.getInstance().d(TAG, "取消接力任务: taskId=" + taskId);
        RelayTask task = tasks.get(taskId);
        if (task == null || !task.isCancellable()) {
            TransferLogger.getInstance().w(TAG, "取消接力任务失败，任务不存在或不可取消: " + taskId);
            return false;
        }

        // 如果正在下载，停止下载
        if (task.isDownloading()) {
            mainHandler.post(() -> {
                downloadManager.stopDownload(task.getGid());
            });
        }

        task.setStatus(RelayTask.STATUS_CANCELLED);
        saveTasks();
        notifyTaskUpdated(task);

        TransferLogger.getInstance().i(TAG, "任务已取消: " + taskId);
        return true;
    }

    /**
     * 标记任务完成
     */
    public void markCompleted(String taskId) {
        TransferLogger.getInstance().d(TAG, "标记任务完成: taskId=" + taskId);
        RelayTask task = tasks.get(taskId);
        if (task == null) {
            TransferLogger.getInstance().w(TAG, "标记任务完成失败，任务不存在: " + taskId);
            return;
        }

        task.setStatus(RelayTask.STATUS_COMPLETED);
        task.setCompletedTime(System.currentTimeMillis());
        saveTasks();
        notifyTaskUpdated(task);

        TransferLogger.getInstance().i(TAG, "任务已完成: " + taskId);

        // 入站任务完成后自动打包
        if (task.isIncoming()) {
            mainHandler.post(() -> {
                boolean packaged = packageTask(taskId);
                if (packaged) {
                    // 打包成功后，如果有源设备信息，自动推回
                    RelayTask updatedTask = tasks.get(taskId);
                    if (updatedTask != null && updatedTask.getSourceDevice() != null) {
                        pushBackToSource(taskId);
                    }
                }
            });
        }
    }

    /**
     * 将完成的接力任务ZIP推送到发起端设备
     */
    public void pushBackToSource(String taskId) {
        TransferLogger.getInstance().d(TAG, "推送回发起端设备: taskId=" + taskId);
        RelayTask task = tasks.get(taskId);
        if (task == null || !task.isReturned() || task.getZipFilePath() == null) {
            TransferLogger.getInstance().w(TAG, "pushBackToSource: task not ready, taskId=" + taskId);
            return;
        }

        File zipFile = new File(task.getZipFilePath());
        if (!zipFile.exists()) {
            TransferLogger.getInstance().e(TAG, "pushBackToSource: ZIP file not found: " + task.getZipFilePath());
            markFailed(taskId, "ZIP文件不存在");
            return;
        }

        TransferClientManager clientManager = TransferClientManager.getInstance(context);
        ConnectedDevice sourceDevice = findSourceDevice(clientManager, task);

        if (sourceDevice == null) {
            String sourceHost = task.getSourceDeviceHost();
            int sourcePort = task.getSourceDevicePort();
            if (sourceHost != null && !sourceHost.isEmpty() && sourcePort > 0) {
                TransferLogger.getInstance().i(TAG, "pushBackToSource: connecting to source device at " + sourceHost + ":" + sourcePort);
                clientManager.connect(sourceHost, sourcePort, android.os.Build.MODEL, getLocalDeviceId());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                    TransferLogger.getInstance().w(TAG, "pushBackToSource: 等待源设备连接被中断");
                }
                sourceDevice = findSourceDevice(clientManager, task);
            }
        }

        if (sourceDevice == null) {
            TransferLogger.getInstance().w(TAG, "pushBackToSource: source device not connected, taskId=" + taskId);
            return;
        }

        TransferLogger.getInstance().i(TAG, "pushBackToSource: pushing ZIP to " + sourceDevice.getName() + " for taskId=" + taskId);

        clientManager.pushRelayBack(sourceDevice, task.getTaskId(), task.getGid(), zipFile,
                new TransferClientManager.RelayTaskCallback() {
                    @Override
                    public void onSuccess(String result) {
                        TransferLogger.getInstance().i(TAG, "pushBackToSource succeeded for taskId=" + taskId);
                        // 推送成功，清理本地任务和ZIP
                        deleteTask(taskId);
                    }

                    @Override
                    public void onError(String error) {
                        TransferLogger.getInstance().e(TAG, "pushBackToSource failed for taskId=" + taskId + ": " + error);
                        // 推送失败，保留任务等待重试或手动取回
                    }
                });
    }

    /**
     * 查找源设备（从已连接设备中匹配）
     */
    private ConnectedDevice findSourceDevice(TransferClientManager clientManager, RelayTask task) {
        for (ConnectedDevice device : clientManager.getConnectedDevices()) {
            if (task.getSourceDeviceId() != null && task.getSourceDeviceId().equals(device.getDeviceId())) {
                return device;
            }
            if (task.getSourceDevice() != null && task.getSourceDevice().equals(device.getName())) {
                return device;
            }
        }
        return null;
    }

    private String getLocalDeviceId() {
        android.content.SharedPreferences prefs = context.getSharedPreferences("transfer_device", android.content.Context.MODE_PRIVATE);
        String deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString();
            prefs.edit().putString("device_id", deviceId).apply();
        }
        return deviceId;
    }

    /**
     * 标记任务失败
     */
    public void markFailed(String taskId, String errorMessage) {
        TransferLogger.getInstance().d(TAG, "标记任务失败: taskId=" + taskId + ", error=" + errorMessage);
        RelayTask task = tasks.get(taskId);
        if (task == null) {
            TransferLogger.getInstance().w(TAG, "标记任务失败，任务不存在: " + taskId);
            return;
        }

        task.setStatus(RelayTask.STATUS_FAILED);
        task.setErrorMessage(errorMessage);
        saveTasks();
        notifyTaskUpdated(task);

        TransferLogger.getInstance().e(TAG, "任务失败: taskId=" + taskId + ", error=" + errorMessage);
    }

    /**
     * 打包任务文件为ZIP
     */
    public boolean packageTask(String taskId) {
        TransferLogger.getInstance().d(TAG, "打包接力任务: taskId=" + taskId);
        RelayTask task = tasks.get(taskId);
        if (task == null || !task.isCompleted()) {
            TransferLogger.getInstance().w(TAG, "打包失败，任务不存在或未完成: " + taskId);
            return false;
        }

        try {
            // 获取下载目录
            DownloadInfo downloadInfo = downloadManager.getDownloadInfo(task.getGid());
            if (downloadInfo == null) {
                TransferLogger.getInstance().e(TAG, "Download info not found for gid: " + task.getGid());
                return false;
            }

            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(downloadInfo);
            if (downloadDir == null || !downloadDir.isDirectory()) {
                TransferLogger.getInstance().e(TAG, "Download directory not found for gid: " + task.getGid());
                return false;
            }

            // 创建临时ZIP文件
            File cacheDir = context.getCacheDir();
            String zipFileName = "relay_" + task.getGid() + "_" + System.currentTimeMillis() + ".zip";
            File zipFile = new File(cacheDir, zipFileName);

            // 打包ZIP
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));

            // 遍历下载目录中的文件
            UniFile[] files = downloadDir.listFiles();
            int fileCount = 0;
            if (files != null) {
                for (UniFile file : files) {
                    if (file.isFile()) {
                        String fileName = file.getName();
                        ZipEntry entry = new ZipEntry(fileName);
                        zos.putNextEntry(entry);

                        // 写入文件内容
                        java.io.InputStream is = file.openInputStream();
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = is.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                        is.close();
                        zos.closeEntry();
                        fileCount++;
                    }
                }
            }

            zos.close();
            TransferLogger.getInstance().i(TAG, "打包完成: taskId=" + taskId + ", 文件数=" + fileCount
                    + ", 字节数=" + zipFile.length() + ", 路径=" + zipFile.getAbsolutePath());

            // 更新任务信息
            task.setZipFilePath(zipFile.getAbsolutePath());
            task.setZipFileSize(zipFile.length());
            task.setStatus(RelayTask.STATUS_RETURNED);
            task.setReturnedTime(System.currentTimeMillis());
            saveTasks();
            notifyTaskUpdated(task);

            TransferLogger.getInstance().d(TAG, "Packaged relay task: " + taskId + ", file: " + zipFile.getAbsolutePath());
            return true;

        } catch (IOException e) {
            TransferLogger.getInstance().e(TAG, "Failed to package task: " + taskId, e);
            markFailed(taskId, "打包失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 删除任务
     */
    public boolean deleteTask(String taskId) {
        TransferLogger.getInstance().d(TAG, "删除接力任务: taskId=" + taskId);
        RelayTask task = tasks.remove(taskId);
        if (task == null) {
            TransferLogger.getInstance().w(TAG, "删除接力任务失败，任务不存在: " + taskId);
            return false;
        }

        gidToTaskMap.remove(task.getGid());

        // 删除ZIP文件（如果存在）
        if (task.getZipFilePath() != null) {
            File zipFile = new File(task.getZipFilePath());
            if (zipFile.exists()) {
                zipFile.delete();
            }
        }

        saveTasks();
        notifyTaskDeleted(task);

        TransferLogger.getInstance().i(TAG, "接力任务已删除: " + taskId);
        return true;
    }

    // ==================== 下载进度监听 ====================

    private void registerDownloadListener() {
        downloadManager.addDownloadInfoListener(new DownloadManager.DownloadInfoListener() {
            @Override
            public void onUpdate(DownloadInfo info, List<DownloadInfo> list, java.util.LinkedList<DownloadInfo> waitList) {
                handleDownloadUpdate(info);
            }

            @Override
            public void onUpdateAll() {
                // 不需要处理
            }

            @Override
            public void onReload() {
                // 不需要处理
            }

            @Override
            public void onAdd(DownloadInfo info, List<DownloadInfo> list, int position) {
                // 不需要处理
            }

            @Override
            public void onRemove(DownloadInfo info, List<DownloadInfo> list, int position) {
                // 不需要处理
            }

            @Override
            public void onReplace(DownloadInfo newInfo, DownloadInfo oldInfo) {
                // 不需要处理
            }

            @Override
            public void onChange() {
                // 不需要处理
            }

            @Override
            public void onRenameLabel(String from, String to) {
                // 不需要处理
            }

            @Override
            public void onUpdateLabels() {
                // 不需要处理
            }
        });
    }

    private void handleDownloadUpdate(DownloadInfo info) {
        String taskId = gidToTaskMap.get(info.gid);
        if (taskId == null) {
            return;
        }

        RelayTask task = tasks.get(taskId);
        if (task == null || !task.isDownloading()) {
            return;
        }

        // 更新进度
        task.setFinished(info.finished);
        task.setTotal(info.total);
        task.setSpeed(info.speed);
        task.setUpdatedTime(System.currentTimeMillis());

        // 计算已下载大小
        if (info.total > 0 && info.finished > 0 && info.fileSize > 0) {
            long avgSize = info.fileSize / info.total;
            task.setDownloadedSize(avgSize * info.finished);
            task.setTotalSize(info.fileSize);
        }

        TransferLogger.getInstance().d(TAG, "下载进度: taskId=" + taskId + ", finished=" + task.getFinished()
                + "/" + task.getTotal() + ", speed=" + task.getSpeed() + "B/s");

        // 检查状态变化
        if (info.state == DownloadInfo.STATE_FINISH) {
            markCompleted(taskId);
        } else if (info.state == DownloadInfo.STATE_FAILED) {
            markFailed(taskId, "下载失败");
        }

        notifyTaskUpdated(task);
    }

    // ==================== 持久化 ====================

    private void saveTasks() {
        try {
            JSONArray tasksArray = new JSONArray();
            for (RelayTask task : tasks.values()) {
                JSONObject taskJson = taskToJson(task);
                tasksArray.add(taskJson);
            }
            prefs.edit().putString(KEY_TASKS, tasksArray.toJSONString()).apply();
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to save tasks", e);
        }
    }

    private void loadTasks() {
        try {
            String tasksJson = prefs.getString(KEY_TASKS, null);
            if (tasksJson == null) {
                return;
            }

            JSONArray tasksArray = JSON.parseArray(tasksJson);
            for (int i = 0; i < tasksArray.size(); i++) {
                JSONObject taskJson = tasksArray.getJSONObject(i);
                RelayTask task = jsonToTask(taskJson);
                if (task != null) {
                    tasks.put(task.getTaskId(), task);
                    gidToTaskMap.put(task.getGid(), task.getTaskId());
                }
            }

            TransferLogger.getInstance().d(TAG, "Loaded " + tasks.size() + " relay tasks");
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to load tasks", e);
        }
    }

    private JSONObject taskToJson(RelayTask task) {
        JSONObject json = new JSONObject();
        json.put("taskId", task.getTaskId());
        json.put("gid", task.getGid());
        json.put("token", task.getToken());
        json.put("title", task.getTitle());
        json.put("titleJpn", task.getTitleJpn());
        json.put("thumb", task.getThumb());
        json.put("category", task.getCategory());
        json.put("posted", task.getPosted());
        json.put("uploader", task.getUploader());
        json.put("rating", task.getRating());
        json.put("pages", task.getPages());
        json.put("status", task.getStatus());
        json.put("direction", task.getDirection());
        json.put("sourceDevice", task.getSourceDevice());
        json.put("sourceDeviceId", task.getSourceDeviceId());
        json.put("sourceDeviceHost", task.getSourceDeviceHost());
        json.put("sourceDevicePort", task.getSourceDevicePort());
        json.put("targetDevice", task.getTargetDevice());
        json.put("targetDeviceId", task.getTargetDeviceId());
        json.put("acceptedDevice", task.getAcceptedDevice());
        json.put("acceptedDeviceId", task.getAcceptedDeviceId());
        json.put("acceptedTime", task.getAcceptedTime());
        json.put("priority", task.getPriority());
        json.put("autoReturn", task.isAutoReturn());
        json.put("finished", task.getFinished());
        json.put("total", task.getTotal());
        json.put("speed", task.getSpeed());
        json.put("downloadedSize", task.getDownloadedSize());
        json.put("totalSize", task.getTotalSize());
        json.put("createdTime", task.getCreatedTime());
        json.put("updatedTime", task.getUpdatedTime());
        json.put("completedTime", task.getCompletedTime());
        json.put("returnedTime", task.getReturnedTime());
        json.put("zipFilePath", task.getZipFilePath());
        json.put("zipFileSize", task.getZipFileSize());
        json.put("errorMessage", task.getErrorMessage());
        return json;
    }

    private RelayTask jsonToTask(JSONObject json) {
        try {
            RelayTask task = new RelayTask();
            task.setTaskId(json.getString("taskId"));
            task.setGid(json.getLongValue("gid"));
            task.setToken(json.getString("token"));
            task.setTitle(json.getString("title"));
            task.setTitleJpn(json.getString("titleJpn"));
            task.setThumb(json.getString("thumb"));
            task.setCategory(json.getIntValue("category"));
            task.setPosted(json.getString("posted"));
            task.setUploader(json.getString("uploader"));
            task.setRating(json.getFloatValue("rating"));
            task.setPages(json.getIntValue("pages"));
            task.setStatus(json.getString("status"));
            task.setDirection(json.getString("direction"));
            task.setSourceDevice(json.getString("sourceDevice"));
            task.setSourceDeviceId(json.getString("sourceDeviceId"));
            task.setSourceDeviceHost(json.getString("sourceDeviceHost"));
            task.setSourceDevicePort(json.getIntValue("sourceDevicePort"));
            task.setTargetDevice(json.getString("targetDevice"));
            task.setTargetDeviceId(json.getString("targetDeviceId"));
            task.setAcceptedDevice(json.getString("acceptedDevice"));
            task.setAcceptedDeviceId(json.getString("acceptedDeviceId"));
            task.setAcceptedTime(json.getLongValue("acceptedTime"));
            task.setPriority(json.getString("priority"));
            task.setAutoReturn(json.getBooleanValue("autoReturn"));
            task.setFinished(json.getIntValue("finished"));
            task.setTotal(json.getIntValue("total"));
            task.setSpeed(json.getLongValue("speed"));
            task.setDownloadedSize(json.getLongValue("downloadedSize"));
            task.setTotalSize(json.getLongValue("totalSize"));
            task.setCreatedTime(json.getLongValue("createdTime"));
            task.setUpdatedTime(json.getLongValue("updatedTime"));
            task.setCompletedTime(json.getLongValue("completedTime"));
            task.setReturnedTime(json.getLongValue("returnedTime"));
            task.setZipFilePath(json.getString("zipFilePath"));
            task.setZipFileSize(json.getLongValue("zipFileSize"));
            task.setErrorMessage(json.getString("errorMessage"));
            return task;
        } catch (Exception e) {
            TransferLogger.getInstance().e(TAG, "Failed to parse task json", e);
            return null;
        }
    }

    // ==================== 监听器 ====================

    public void addListener(RelayTaskListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(RelayTaskListener listener) {
        listeners.remove(listener);
    }

    private void notifyTaskCreated(RelayTask task) {
        for (RelayTaskListener listener : listeners) {
            listener.onTaskCreated(task);
        }
    }

    private void notifyTaskUpdated(RelayTask task) {
        for (RelayTaskListener listener : listeners) {
            listener.onTaskUpdated(task);
        }
    }

    private void notifyTaskDeleted(RelayTask task) {
        for (RelayTaskListener listener : listeners) {
            listener.onTaskDeleted(task);
        }
    }

    /**
     * 接力任务监听器
     */
    public interface RelayTaskListener {
        void onTaskCreated(RelayTask task);
        void onTaskUpdated(RelayTask task);
        void onTaskDeleted(RelayTask task);
    }
}
