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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.BookmarkInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.transfer.data.PushTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 数据推送管理器
 * 支持断点续传和自动重试
 */
public class DataPushManager {

    private static final String TAG = "DataPushManager";
    private static final int PAGE_SIZE = 50;
    private static final int MAX_RETRY = 3;
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private Context context;
    private OkHttpClient httpClient;
    private ExecutorService executor;
    private Handler mainHandler;

    private ConcurrentHashMap<String, PushTask> activeTasks = new ConcurrentHashMap<>();
    private List<PushProgressListener> listeners = new ArrayList<>();

    public DataPushManager(Context context) {
        this.context = context;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.executor = Executors.newFixedThreadPool(3);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 创建推送任务
     */
    public PushTask createPushTask(String targetHost, int targetPort, String type, String mode,
                                   List<Long> items, String sourceDevice, String sourceDeviceId) {
        PushTask task = new PushTask(type, mode, sourceDevice, sourceDeviceId);
        task.setItems(items);

        // 计算总数
        int totalCount = 0;
        if (PushTask.MODE_ALL.equals(mode)) {
            totalCount = getDataCount(type);
        } else if (items != null) {
            totalCount = items.size();
        }
        task.setTotalCount(totalCount);

        // 保存到活跃任务
        activeTasks.put(task.getTaskId(), task);

        // 在目标设备上创建任务
        executor.execute(() -> {
            try {
                boolean created = createTaskOnTarget(targetHost, targetPort, task);
                if (created) {
                    // 开始传输数据
                    pushData(targetHost, targetPort, task);
                } else {
                    task.setStatus(PushTask.STATUS_FAILED);
                    notifyFailed(task, "创建任务失败");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to create push task", e);
                task.setStatus(PushTask.STATUS_FAILED);
                notifyFailed(task, e.getMessage());
            }
        });

        return task;
    }

    /**
     * 在目标设备上创建任务
     */
    private boolean createTaskOnTarget(String host, int port, PushTask task) throws IOException {
        JSONObject body = new JSONObject();
        body.put("type", task.getType());
        body.put("mode", task.getMode());
        body.put("totalCount", task.getTotalCount());
        body.put("sourceDevice", task.getSourceDevice());
        body.put("sourceDeviceId", task.getSourceDeviceId());

        if (PushTask.MODE_SELECTED.equals(task.getMode()) && task.getItems() != null) {
            JSONArray itemsArray = new JSONArray();
            for (Long item : task.getItems()) {
                itemsArray.add(item);
            }
            body.put("items", itemsArray);
        }

        String url = "http://" + host + ":" + port + "/api/v1/push/create";
        RequestBody requestBody = RequestBody.create(JSON_MEDIA, body.toJSONString());
        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                JSONObject result = JSON.parseObject(responseBody);
                String remoteTaskId = result.getString("taskId");
                // 使用远程返回的taskId
                task.setTaskId(remoteTaskId);
                return true;
            }
        }
        return false;
    }

    /**
     * 推送数据（支持断点续传和自动重试）
     */
    private void pushData(String host, int port, PushTask task) {
        task.setStatus(PushTask.STATUS_TRANSFERRING);
        notifyProgress(task);

        int offset = task.getLastOffset();
        int total = task.getTotalCount();

        while (offset < total) {
            boolean success = false;
            int retryCount = 0;

            while (!success && retryCount < MAX_RETRY) {
                try {
                    // 获取数据页
                    List<?> items = getDataPage(task.getType(), offset, PAGE_SIZE, task.getItems());
                    if (items == null || items.isEmpty()) {
                        break;
                    }

                    // 发送数据
                    String url = "http://" + host + ":" + port + "/api/v1/push/tasks/" +
                            task.getTaskId() + "/data";

                    JSONObject body = new JSONObject();
                    body.put("offset", offset);
                    body.put("data", serializeData(task.getType(), items));

                    RequestBody requestBody = RequestBody.create(JSON_MEDIA, body.toJSONString());
                    Request request = new Request.Builder()
                            .url(url)
                            .post(requestBody)
                            .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful()) {
                            success = true;
                            offset += items.size();
                            task.setLastOffset(offset);
                            task.setTransferredCount(offset);

                            // 更新进度
                            notifyProgress(task);
                        } else {
                            retryCount++;
                            Thread.sleep(1000L * retryCount); // 递增延迟
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Push data failed, retry " + retryCount, e);
                    retryCount++;
                    try {
                        Thread.sleep(1000L * retryCount);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            if (!success) {
                task.setStatus(PushTask.STATUS_FAILED);
                notifyFailed(task, "传输失败，已重试" + MAX_RETRY + "次");
                return;
            }
        }

        task.setStatus(PushTask.STATUS_COMPLETED);
        task.setTransferredCount(total);
        notifyCompleted(task);
        activeTasks.remove(task.getTaskId());
    }

    /**
     * 获取数据总数
     */
    private int getDataCount(String type) {
        switch (type) {
            case PushTask.TYPE_BOOKMARKS:
                // TODO: Implement bookmark count
                return 0;
            case PushTask.TYPE_DOWNLOADS:
                return EhDB.getAllDownloadInfo().size();
            case PushTask.TYPE_FAVORITES:
                // TODO: Implement favorites count
                return 0;
            default:
                return 0;
        }
    }

    /**
     * 获取数据页
     */
    private List<?> getDataPage(String type, int offset, int limit, List<Long> selectedItems) {
        switch (type) {
            case PushTask.TYPE_BOOKMARKS:
                if (selectedItems != null) {
                    return getSelectedBookmarks(selectedItems, offset, limit);
                }
                return getBookmarksPage(offset, limit);
            case PushTask.TYPE_DOWNLOADS:
                if (selectedItems != null) {
                    return getSelectedDownloads(selectedItems, offset, limit);
                }
                return getDownloadsPage(offset, limit);
            case PushTask.TYPE_FAVORITES:
                if (selectedItems != null) {
                    return getSelectedFavorites(selectedItems, offset, limit);
                }
                return getFavoritesPage(offset, limit);
            default:
                return new ArrayList<>();
        }
    }

    private List<GalleryInfo> getBookmarksPage(int offset, int limit) {
        // TODO: Implement bookmarks page
        return new ArrayList<>();
    }

    private List<DownloadInfo> getDownloadsPage(int offset, int limit) {
        List<DownloadInfo> all = EhDB.getAllDownloadInfo();
        int end = Math.min(offset + limit, all.size());
        if (offset >= all.size()) return new ArrayList<>();
        return all.subList(offset, end);
    }

    private List<GalleryInfo> getFavoritesPage(int offset, int limit) {
        // TODO: Implement favorites page
        return new ArrayList<>();
    }

    private List<GalleryInfo> getSelectedBookmarks(List<Long> gids, int offset, int limit) {
        // TODO: Implement selected bookmarks
        return new ArrayList<>();
    }

    private List<DownloadInfo> getSelectedDownloads(List<Long> gids, int offset, int limit) {
        List<DownloadInfo> result = new ArrayList<>();
        int end = Math.min(offset + limit, gids.size());
        for (int i = offset; i < end; i++) {
            DownloadInfo info = EhDB.getDownloadInfo(gids.get(i));
            if (info != null) result.add(info);
        }
        return result;
    }

    private List<GalleryInfo> getSelectedFavorites(List<Long> gids, int offset, int limit) {
        // TODO: Implement selected favorites
        return new ArrayList<>();
    }

    /**
     * 序列化数据
     */
    private JSONArray serializeData(String type, List<?> items) {
        JSONArray array = new JSONArray();
        for (Object item : items) {
            if (item instanceof GalleryInfo) {
                array.add(((GalleryInfo) item).toJson());
            } else if (item instanceof DownloadInfo) {
                array.add(((DownloadInfo) item).toJson());
            }
        }
        return array;
    }

    /**
     * 添加监听器
     */
    public void addListener(PushProgressListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * 移除监听器
     */
    public void removeListener(PushProgressListener listener) {
        listeners.remove(listener);
    }

    private void notifyProgress(PushTask task) {
        mainHandler.post(() -> {
            for (PushProgressListener listener : listeners) {
                listener.onProgress(task);
            }
        });
    }

    private void notifyCompleted(PushTask task) {
        mainHandler.post(() -> {
            for (PushProgressListener listener : listeners) {
                listener.onCompleted(task);
            }
        });
    }

    private void notifyFailed(PushTask task, String error) {
        mainHandler.post(() -> {
            for (PushProgressListener listener : listeners) {
                listener.onFailed(task, error);
            }
        });
    }

    /**
     * 获取活跃任务
     */
    public PushTask getActiveTask(String taskId) {
        return activeTasks.get(taskId);
    }

    /**
     * 释放资源
     */
    public void release() {
        executor.shutdown();
        activeTasks.clear();
        listeners.clear();
    }

    /**
     * 推送进度监听器
     */
    public interface PushProgressListener {
        void onProgress(PushTask task);
        void onCompleted(PushTask task);
        void onFailed(PushTask task, String error);
    }
}
