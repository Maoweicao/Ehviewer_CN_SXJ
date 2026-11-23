/*
 * Copyright 2026 EhViewer Contributors
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

package com.hippo.ehviewer.transfer.api;

import android.content.Context;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.log.TransferLogger;

import fi.iki.elonen.NanoHTTPD;

/**
 * 辅助下载 API（§5.24，历史路径）——§5.17 的永久别名层。
 *
 * <pre>
 * GET  /api/v1/aux-download/tasks        -> GET  /api/v1/relay/pool
 * GET  /api/v1/aux-download/tasks/{gid}  -> GET  /api/v1/relay/{taskId}/plan?gid={gid}
 * POST /api/v1/aux-download/upload       -> POST /api/v1/relay/return/partial
 * </pre>
 *
 * 响应附加 deprecated:true 与 aliasOf，其余结构、覆盖策略、幂等键与主端点完全一致。
 */
public class AuxDownloadApiHandler extends BaseApiHandler {

    private static final String TAG = "AuxDownloadApiHandler";

    private final RelayPoolApiHandler poolApiHandler;

    public AuxDownloadApiHandler(Context context, AuthManager authManager) {
        super(context, authManager);
        this.poolApiHandler = new RelayPoolApiHandler(context, authManager);
    }

    @Override
    public NanoHTTPD.Response handleGet(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("GET", uri, session);
        if (!checkEnabled()) {
            return ResponseBuilder.forbidden("Auxiliary download is disabled");
        }
        String path = uri.split("\\?")[0];
        if (path.equals("/api/v1/aux-download/tasks")) {
            return poolApiHandler.handlePoolList(session, true);
        }
        if (path.matches("/api/v1/aux-download/tasks/\\d+")) {
            long gid;
            try {
                gid = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
            } catch (NumberFormatException e) {
                return ResponseBuilder.jsonError(NanoHTTPD.Response.Status.BAD_REQUEST,
                        "Invalid gid");
            }
            return poolApiHandler.handlePlan(session, null, gid, true);
        }
        return ResponseBuilder.notFound("Endpoint");
    }

    @Override
    public NanoHTTPD.Response handlePost(NanoHTTPD.IHTTPSession session, String uri) {
        logRequest("POST", uri, session);
        if (!checkEnabled()) {
            return ResponseBuilder.forbidden("Auxiliary download is disabled");
        }
        String path = uri.split("\\?")[0];
        if (path.equals("/api/v1/aux-download/upload")) {
            return poolApiHandler.handlePartialReturn(session, true);
        }
        return ResponseBuilder.notFound("Endpoint");
    }

    /** §5.17.18：isRelayPoolEnabled() 或 isAuxDownloadEnabled() 任一开启即服务。 */
    private boolean checkEnabled() {
        boolean enabled = Settings.isRelayPoolEnabled() || Settings.isAuxDownloadEnabled();
        if (!enabled) {
            TransferLogger.getInstance().d(TAG, "Aux download disabled");
        }
        return enabled;
    }
}
