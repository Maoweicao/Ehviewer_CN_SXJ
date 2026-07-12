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

package com.hippo.ehviewer.transfer.data;

/**
 * 接收设置数据模型
 */
public class ReceiveSettings {

    private boolean autoReceiveBookmarks;
    private boolean autoReceiveDownloads;
    private boolean autoReceiveFavorites;
    private int pageSize;

    public ReceiveSettings() {
        this.autoReceiveBookmarks = false;
        this.autoReceiveDownloads = false;
        this.autoReceiveFavorites = false;
        this.pageSize = 20;
    }

    public ReceiveSettings(boolean autoReceiveBookmarks, boolean autoReceiveDownloads,
                           boolean autoReceiveFavorites, int pageSize) {
        this.autoReceiveBookmarks = autoReceiveBookmarks;
        this.autoReceiveDownloads = autoReceiveDownloads;
        this.autoReceiveFavorites = autoReceiveFavorites;
        this.pageSize = pageSize;
    }

    public boolean isAutoReceiveBookmarks() {
        return autoReceiveBookmarks;
    }

    public void setAutoReceiveBookmarks(boolean autoReceiveBookmarks) {
        this.autoReceiveBookmarks = autoReceiveBookmarks;
    }

    public boolean isAutoReceiveDownloads() {
        return autoReceiveDownloads;
    }

    public void setAutoReceiveDownloads(boolean autoReceiveDownloads) {
        this.autoReceiveDownloads = autoReceiveDownloads;
    }

    public boolean isAutoReceiveFavorites() {
        return autoReceiveFavorites;
    }

    public void setAutoReceiveFavorites(boolean autoReceiveFavorites) {
        this.autoReceiveFavorites = autoReceiveFavorites;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    /**
     * 检查指定类型是否自动接收
     */
    public boolean isAutoReceive(String type) {
        switch (type) {
            case PushTask.TYPE_BOOKMARKS:
                return autoReceiveBookmarks;
            case PushTask.TYPE_DOWNLOADS:
                return autoReceiveDownloads;
            case PushTask.TYPE_FAVORITES:
                return autoReceiveFavorites;
            default:
                return false;
        }
    }
}
