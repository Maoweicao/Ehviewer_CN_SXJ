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

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.transfer.api.AuthApiHandler;
import com.hippo.ehviewer.transfer.api.AuxDownloadApiHandler;
import com.hippo.ehviewer.transfer.api.CompressApiHandler;
import com.hippo.ehviewer.transfer.api.ConnectApiHandler;
import com.hippo.ehviewer.transfer.api.DataApiHandler;
import com.hippo.ehviewer.transfer.api.DownloadApiHandler;
import com.hippo.ehviewer.transfer.api.EhAuthApiHandler;
import com.hippo.ehviewer.transfer.api.EventsApiHandler;
import com.hippo.ehviewer.transfer.api.FileApiHandler;
import com.hippo.ehviewer.transfer.api.GalleryApiHandler;
import com.hippo.ehviewer.transfer.api.GalleryIndexApiHandler;
import com.hippo.ehviewer.transfer.api.LabelApiHandler;
import com.hippo.ehviewer.transfer.api.OnlineApiHandler;
import com.hippo.ehviewer.transfer.api.PageApiHandler;
import com.hippo.ehviewer.transfer.api.PushApiHandler;
import com.hippo.ehviewer.transfer.api.RelayApiHandler;
import com.hippo.ehviewer.transfer.api.ResponseBuilder;
import com.hippo.ehviewer.transfer.api.SettingsApiHandler;
import com.hippo.ehviewer.transfer.api.SystemApiHandler;
import com.hippo.ehviewer.transfer.api.TasksApiHandler;
import com.hippo.ehviewer.lab.LabApiHandler;
import com.hippo.ehviewer.lab.SnapshotApiHandler;
import com.hippo.ehviewer.lab.sync.LabSyncApiHandler;
import com.hippo.ehviewer.lab.sync.LabSyncAuth;
import com.hippo.ehviewer.transfer.auth.AuthManager;
import com.hippo.ehviewer.transfer.auth.AuthMode;
import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import fi.iki.elonen.NanoHTTPD;

/**
 * HTTP传输服务器
 * 提供REST API接口用于远程管理
 *
 * 端点:
 * - GET    /api/v1/auth/status            获取认证状态
 * - POST   /api/v1/auth/login             登录认证
 * - POST   /api/v1/auth/logout            登出
 * - GET    /api/v1/galleries              获取画廊列表
 * - QUERY  /api/v1/galleries              查询画廊列表（带过滤）
 * - GET    /api/v1/galleries/{gid}        获取画廊详情
 * - DELETE /api/v1/galleries/{gid}        删除画廊
 * - DELETE /api/v1/galleries/batch        批量删除画廊
 * - GET    /api/v1/galleries/{gid}/thumbnail  获取缩略图
 * - GET    /api/v1/galleries/{gid}/pages  获取页面列表
 * - GET    /api/v1/galleries/{gid}/pages/{page}  获取图片
 * - GET    /api/v1/labels                 获取标签列表
 * - GET    /api/v1/labels/{label}/galleries  获取标签下的画廊
 * - GET    /api/v1/system/info            获取系统信息
 * - GET    /api/v1/system/stats           获取系统统计
 * - POST   /api/v1/push/create            创建推送任务
 * - GET    /api/v1/push/tasks             获取待处理任务列表
 * - GET    /api/v1/push/tasks/{id}        获取任务状态
 * - POST   /api/v1/push/tasks/{id}/accept 接受任务
 * - POST   /api/v1/push/tasks/{id}/reject 拒绝任务
 * - GET    /api/v1/push/tasks/{id}/data   获取任务数据
 * - GET    /api/v1/settings/receive       获取接收设置
 * - PUT    /api/v1/settings/receive       更新接收设置
 * - GET    /api/v1/device/info            设备信息
 * - GET    /api/v1/downloads              获取下载任务列表
 * - GET    /api/v1/downloads/{gid}        获取下载任务详情
 * - POST   /api/v1/downloads              创建下载任务
 * - POST   /api/v1/downloads/{gid}/start  开始/恢复下载
 * - POST   /api/v1/downloads/{gid}/pause  暂停下载
 * - DELETE /api/v1/downloads/{gid}        删除下载任务
 * - POST   /api/v1/downloads/batch/start  批量开始下载
 * - POST   /api/v1/downloads/batch/pause  批量暂停下载
 * - DELETE /api/v1/downloads/batch        批量删除下载任务
 * - POST   /api/v1/connect                注册设备连接
 * - DELETE /api/v1/connect                注销设备连接
 * - GET    /api/v1/connect/peers          获取已连接设备列表
 * - GET    /api/v1/lab/config             实验室配置（v3.0）
 * - PUT    /api/v1/lab/config             更新实验室配置（v3.0）
 * - GET    /api/v1/lab/topology           集群拓扑（v3.0）
 * - GET    /api/v1/lab/stats              集群统计（v3.0）
 * - GET    /api/v1/lab/trusted-peers      信任设备列表（v3.0）
 * - POST   /api/v1/lab/trusted-peers      申请配对（生成 6 位码）（v3.0）
 * - POST   /api/v1/lab/trusted-peers/pair 双向配对确认（v3.0）
 * - GET    /api/v1/lab/trusted-peers/pair-info 探测配对码（白名单）（v3.0）
 * - DELETE /api/v1/lab/trusted-peers/{id} 撤销信任（v3.0）
 * - GET    /api/v1/lab/db/snapshot          DB 快照（v3.0）
 * - POST   /api/v1/lab/db/snapshot/ack      确认快照版本（v3.0）
 * - GET    /api/v1/lab/db/thumbnail/{gid}   缩略图（v3.0）
 * - GET    /api/v1/aux-download/tasks        未完成画廊列表（§5.24）
 * - GET    /api/v1/aux-download/tasks/{gid}  单画廊缺口详情（§5.24）
 * - POST   /api/v1/aux-download/upload       回传压缩图片（§5.24）
 * - GET    /api/v1/sync/gallery-index/scopes 列表级索引（§5.29）
 * - GET    /api/v1/sync/gallery-index        分区位图清单（§5.29）
 * - GET    /api/v1/sync/gallery-index/buckets/{bucket} 桶内画廊行（§5.29）
 * - POST   /api/v1/lab/sync/sessions         设备同步会话（§5.30）
 * - GET    /api/v1/lab/sync/sessions/{id}    设备同步状态（§5.30）
 * - GET    /docs                          Swagger UI 文档页面
 * - GET    /openapi.yaml                  OpenAPI 规范文件
 * - GET    /web/*                         静态资源
 */
public class TransferHttpServer {

    private static final String TAG = "TransferHttpServer";
    private final int port;
    private final TransferServerManager serverManager;
    private final Context context;
    private boolean isRunning = false;

    private HttpServerImpl httpServer;
    private AuthManager authManager;
    
    // API处理器
    private AuthApiHandler authApiHandler;
    private GalleryApiHandler galleryApiHandler;
    private PageApiHandler pageApiHandler;
    private LabelApiHandler labelApiHandler;
    private SystemApiHandler systemApiHandler;
    private PushApiHandler pushApiHandler;
    private SettingsApiHandler settingsApiHandler;
    private FileApiHandler fileApiHandler;
    private DataApiHandler dataApiHandler;
    private CompressApiHandler compressApiHandler;
    private TasksApiHandler tasksApiHandler;
    private DownloadApiHandler downloadApiHandler;
    private ConnectApiHandler connectApiHandler;
    private RelayApiHandler relayApiHandler;
    private LabApiHandler labApiHandler;
    private SnapshotApiHandler snapshotApiHandler;
    private LabSyncApiHandler labSyncApiHandler;
    private AuxDownloadApiHandler auxDownloadApiHandler;
    // §5.25~§5.28 新增
    private OnlineApiHandler onlineApiHandler;
    private EhAuthApiHandler ehAuthApiHandler;
    private EventsApiHandler eventsApiHandler;
    // §5.29 画廊索引
    private GalleryIndexApiHandler galleryIndexApiHandler;

    public TransferHttpServer(int port, TransferServerManager serverManager, Context context) {
        this.port = port;
        this.serverManager = serverManager;
        this.context = context;
        
        // 初始化认证管理器
        this.authManager = new AuthManager();
        String authModeStr = Settings.getRemoteAuthMode();
        AuthMode authMode = AuthMode.fromString(authModeStr);
        authManager.initialize(authMode);
        
        // 初始化任务管理器
        TaskManager.getInstance().init(context);
        
        // 初始化API处理器
        this.authApiHandler = new AuthApiHandler(context, authManager);
        this.galleryApiHandler = new GalleryApiHandler(context, authManager);
        this.pageApiHandler = new PageApiHandler(context, authManager);
        this.labelApiHandler = new LabelApiHandler(context, authManager);
        this.systemApiHandler = new SystemApiHandler(context, authManager);
        this.pushApiHandler = new PushApiHandler(context, authManager);
        this.settingsApiHandler = new SettingsApiHandler(context, authManager);
        this.fileApiHandler = new FileApiHandler(context, authManager);
        this.dataApiHandler = new DataApiHandler(context, authManager);
        this.compressApiHandler = new CompressApiHandler(context, authManager);
        this.tasksApiHandler = new TasksApiHandler(context, authManager);
        this.downloadApiHandler = new DownloadApiHandler(context, authManager);
        this.connectApiHandler = new ConnectApiHandler(context, authManager);
        this.relayApiHandler = new RelayApiHandler(context, authManager);
        // v3.0 实验室 / 多机联动（Lab）API
        this.labApiHandler = new LabApiHandler(context, authManager);
        this.snapshotApiHandler = new SnapshotApiHandler(context, authManager);
        // §5.30 设备间快速同步会话
        this.labSyncApiHandler = new LabSyncApiHandler(context, authManager);
        // §5.24 辅助下载API
        this.auxDownloadApiHandler = new AuxDownloadApiHandler(context, authManager);
        // §5.25~§5.28 新增
        this.onlineApiHandler = new OnlineApiHandler(context, authManager);
        this.ehAuthApiHandler = new EhAuthApiHandler(context, authManager);
        this.eventsApiHandler = new EventsApiHandler(context, authManager);
        // §5.29 画廊索引（同时负责载入持久化版本号）
        DataChangeBus.init(context);
        this.galleryIndexApiHandler = new GalleryIndexApiHandler(context, authManager);
    }

    /** 启动HTTP服务器 */
    public void start() throws Exception {
        TransferLogger.getInstance().d(TAG, "Starting HTTP server on port " + port);
        httpServer = new HttpServerImpl(port);
        httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        isRunning = true;
        TransferLogger.getInstance().d(TAG, "HTTP server started");
    }

    /** 停止HTTP服务器 */
    public void stop() throws Exception {
        TransferLogger.getInstance().d(TAG, "Stopping HTTP server");
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        isRunning = false;
        TransferLogger.getInstance().d(TAG, "HTTP server stopped");
    }

    /** 检查服务器是否运行 */
    public boolean isRunning() { return isRunning; }

    /** 获取服务器端口 */
    public int getPort() { return port; }
    
    /** 获取认证管理器 */
    public AuthManager getAuthManager() { return authManager; }
    
    /** 获取生成的密码（密码模式） */
    public String getGeneratedPassword() { return authManager.getGeneratedPassword(); }
    
    /** 获取生成的Token（Token模式） */
    public String getGeneratedToken() { return authManager.getGeneratedToken(); }

    /**
     * 有界异步执行器。
     *
     * <p>NanoHTTPD 2.3.1 默认每个请求新建一条线程且无上限，请求风暴（尤其是
     * 传输列表 + 多下载并发时 Web UI 轮询）会瞬间叠加大量线程与内存。
     * 这里改为固定线程池 + 有界队列，超出容量直接关闭连接，保护内存与主进程。
     */
    private static final class BoundedAsyncRunner implements NanoHTTPD.AsyncRunner {

        private static final int DEFAULT_MAX_THREADS = 8;
        private static final int DEFAULT_QUEUE_CAPACITY = 32;

        private final ThreadPoolExecutor executor;
        private final List<NanoHTTPD.ClientHandler> clients =
                Collections.synchronizedList(new ArrayList<>());

        BoundedAsyncRunner() {
            this(DEFAULT_MAX_THREADS, DEFAULT_QUEUE_CAPACITY);
        }

        BoundedAsyncRunner(int maxThreads, int queueCapacity) {
            int max = Math.max(1, maxThreads);
            this.executor = new ThreadPoolExecutor(
                    Math.max(1, max / 2),
                    max,
                    30L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                    new ThreadFactory() {
                        private final AtomicInteger count = new AtomicInteger(1);

                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "TransferHttp-" + count.getAndIncrement());
                            t.setDaemon(true);
                            return t;
                        }
                    },
                    new ThreadPoolExecutor.AbortPolicy());
        }

        @Override
        public void closeAll() {
            for (NanoHTTPD.ClientHandler client : clients) {
                try {
                    client.close();
                } catch (Throwable ignored) {
                }
            }
            clients.clear();
            executor.shutdownNow();
        }

        @Override
        public void closed(NanoHTTPD.ClientHandler clientHandler) {
            clients.remove(clientHandler);
        }

        @Override
        public void exec(NanoHTTPD.ClientHandler code) {
            clients.add(code);
            try {
                executor.execute(code);
            } catch (RejectedExecutionException e) {
                // 过载：关闭该连接而不是无限堆积
                TransferLogger.getInstance().w(TAG, "传输请求过载，拒绝新连接");
                clients.remove(code);
                try {
                    code.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private class HttpServerImpl extends NanoHTTPD {
        HttpServerImpl(int port) {
            super(port);
            setAsyncRunner(new BoundedAsyncRunner());
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            Method method = session.getMethod();
            String methodName = method.name();
            String remoteIp = session.getRemoteIpAddress();
            
            long startTime = System.currentTimeMillis();

            TransferLogger logger = TransferLogger.getInstance();
            logger.d(TAG, String.format("[请求] %s %s [来源: %s]", methodName, uri, remoteIp));

            // 记录查询参数
            try {
                Map<String, String> parms = session.getParms();
                if (parms != null && !parms.isEmpty()) {
                    logger.d(TAG, "[查询参数] " + parms + " [URI: " + uri + "]");
                }
            } catch (Exception e) {
                logger.w(TAG, "[serve] 读取查询参数失败: " + uri + " - " + e.getMessage());
            }

            try {
                // 支持QUERY方法（RFC 10008）
                Response response;
                if ("QUERY".equals(methodName)) {
                    response = handleRequest(session, uri, "QUERY");
                } else {
                    response = handleRequest(session, uri, methodName);
                }

                long cost = System.currentTimeMillis() - startTime;
                String statusText = response == null ? "null" : response.getStatus().getDescription();
                logger.i(TAG, String.format("[响应] %s %s -> %s (耗时: %d ms)", methodName, uri, statusText, cost));
                return response;

            } catch (OutOfMemoryError oom) {
                // 构建响应阶段堆耗尽：清理缓存并返回极小 503，避免异常上抛带崩进程。
                TransferLogger.getInstance().e(TAG, "请求处理 OOM: " + uri, oom);
                try {
                    com.hippo.ehviewer.EhApplication.clearMemoryCacheSafely();
                } catch (Throwable ignored) {
                }
                try {
                    com.hippo.ehviewer.transfer.core.ResponseCache.getInstance().clear();
                } catch (Throwable ignored) {
                }
                return ResponseBuilder.serviceUnavailable();
            } catch (Throwable e) {
                TransferLogger.getInstance().e(TAG, "请求处理异常: " + uri, e);
                return ResponseBuilder.internalError(e.getMessage());
            }
        }
        
        private Response handleRequest(IHTTPSession session, String uri, String method) {
            // 认证检查（白名单端点除外）
            // 辅助下载：开启 trustLan 时局域网请求免认证（§5.24）
            boolean auxLanBypass = uri.startsWith("/api/v1/aux-download")
                    && Settings.isAuxDownloadTrustLan()
                    && AuthManager.isLocalNetwork(session.getRemoteIpAddress());
            // §5.30 已配对设备使用 presharedKey 做 HMAC 签名，免传输服务 Token
            boolean peerSyncBypass = LabSyncAuth.isSyncPath(uri)
                    && LabSyncAuth.verify(context, session);
            if (!auxLanBypass && !peerSyncBypass && !AuthManager.isWhitelisted(uri) && !authManager.authenticate(session)) {
                return ResponseBuilder.unauthorized();
            }
            
            // 远程管理开关检查 - Web UI 路由
            // 当远程管理关闭时，禁止访问 Web UI，但 API 和文档仍可用
            if (!Settings.isRemoteManagementEnabled()) {
                if (uri.equals("/") || uri.startsWith("/web/")) {
                    return ResponseBuilder.forbidden("Remote management is disabled");
                }
            }
            
            // Web界面路由
            if (uri.equals("/")) {
                return ResponseBuilder.redirect("/web/index.html");
            }
            
            // Swagger UI 文档页面
            if (uri.equals("/docs") || uri.equals("/docs/") || uri.equals("/swagger")) {
                return serveStaticResource("/web/docs/index.html");
            }
            
            // OpenAPI 规范文件
            if (uri.equals("/openapi.yaml") || uri.equals("/openapi")) {
                return serveStaticResource("/web/docs/openapi.yaml");
            }
            
            // 静态资源
            if (uri.startsWith("/web/")) {
                return serveStaticResource(uri);
            }
            
            // API路由分发
            switch (method) {
                case "GET":
                    return handleGet(session, uri);
                case "POST":
                    return handlePost(session, uri);
                case "PUT":
                    return handlePut(session, uri);
                case "DELETE":
                    return handleDelete(session, uri);
                case "PATCH":
                    return handlePatch(session, uri);
                case "QUERY":
                    return handleQuery(session, uri);
                default:
                    return ResponseBuilder.methodNotAllowed();
            }
        }
        
        private Response handleGet(IHTTPSession session, String uri) {
            // 认证API
            if (uri.startsWith("/api/v1/auth")) {
                return authApiHandler.handleGet(session, uri);
            }
            
            // 页面API（必须在画廊API之前检查，因为路径包含 /api/v1/galleries）
            if (uri.matches("/api/v1/galleries/\\d+/pages.*")) {
                return pageApiHandler.handleGet(session, uri);
            }
            
            // 画廊API
            if (uri.startsWith("/api/v1/galleries")) {
                return galleryApiHandler.handleGet(session, uri);
            }

            // 收藏API
            if (uri.startsWith("/api/v1/favorites")) {
                return galleryApiHandler.handleGet(session, uri);
            }
            
            // 标签API
            if (uri.startsWith("/api/v1/labels")) {
                return labelApiHandler.handleGet(session, uri);
            }

            // §5.25 在线画廊API
            if (uri.startsWith("/api/v1/online")) {
                return onlineApiHandler.handleGet(session, uri);
            }

            // §5.27 E-Hentai 账号API
            if (uri.startsWith("/api/v1/eh/auth")) {
                return ehAuthApiHandler.handleGet(session, uri);
            }

            // §5.28 实时变更API
            if (uri.startsWith("/api/v1/events") || uri.startsWith("/api/v1/system/version")) {
                return eventsApiHandler.handleGet(session, uri);
            }

            // §5.29 画廊索引与分区位图API
            if (uri.startsWith("/api/v1/sync/gallery-index")) {
                return galleryIndexApiHandler.handleGet(session, uri);
            }

            // 系统API
            if (uri.startsWith("/api/v1/system")) {
                return systemApiHandler.handleGet(session, uri);
            }
            
            // 调试API
            
            // 推送API
            if (uri.startsWith("/api/v1/push")) {
                return pushApiHandler.handleGet(session, uri);
            }
            
            // 设置API
            if (uri.startsWith("/api/v1/settings")) {
                return settingsApiHandler.handleGet(session, uri);
            }
            
            // 文件API
            if (uri.startsWith("/api/v1/folders")) {
                return fileApiHandler.handleGet(session, uri);
            }
            
            // 数据导出API
            if (uri.startsWith("/api/v1/data")) {
                return dataApiHandler.handleGet(session, uri);
            }
            
            // 压缩API
            if (uri.startsWith("/api/v1/compress")) {
                return compressApiHandler.handleGet(session, uri);
            }
            
            // 统一任务API
            if (uri.startsWith("/api/v1/tasks")) {
                return tasksApiHandler.handleGet(session, uri);
            }
            
            // 下载管理API
            if (uri.startsWith("/api/v1/downloads")) {
                return downloadApiHandler.handleGet(session, uri);
            }
            
            // 设备连接API
            if (uri.startsWith("/api/v1/connect")) {
                return connectApiHandler.handleGet(session, uri);
            }
            
            // 接力下载API
            if (uri.startsWith("/api/v1/relay")) {
                return relayApiHandler.handleGet(session, uri);
            }

            // 实验室 / 多机联动（v3.0）API
            if (uri.startsWith("/api/v1/lab/sync")) {
                return labSyncApiHandler.handleGet(session, uri);
            }
            if (uri.startsWith("/api/v1/lab")) {
                // /api/v1/lab/db/* 走 SnapshotApiHandler
                if (uri.startsWith("/api/v1/lab/db")) {
                    return snapshotApiHandler.handleGet(session, uri);
                }
                return labApiHandler.handleGet(session, uri);
            }

            // 辅助下载API（v1 §5.24）
            if (uri.startsWith("/api/v1/aux-download")) {
                return auxDownloadApiHandler.handleGet(session, uri);
            }

            // 设备信息（保持向后兼容）
            if (uri.equals("/api/v1/device/info")) {
                String deviceName = android.os.Build.MODEL;
                String json = "{" +
                        "\"version\":\"1.0\"," +
                        "\"device_name\":\"" + deviceName + "\"," +
                        "\"device_type\":\"android\"," +
                        "\"capabilities\":\"file_transfer,backup,restore,gallery_management\"" +
                        "}";
                return ResponseBuilder.jsonSuccess(json);
            }
            
            return ResponseBuilder.notFound("Endpoint");
        }
        
        private Response handlePost(IHTTPSession session, String uri) {
            // QUERY 语义兜底：内嵌 NanoHTTPD 2.3.1 不支持 QUERY 动词（会在解析阶段 400），
            // 客户端以 POST + X-HTTP-Method-Override: QUERY 承载查询体，这里转给 handleQuery。
            String methodOverride = session.getHeaders().get("x-http-method-override");
            if (methodOverride != null && "QUERY".equalsIgnoreCase(methodOverride.trim())) {
                return handleQuery(session, uri);
            }

            // 认证API
            if (uri.startsWith("/api/v1/auth")) {
                return authApiHandler.handlePost(session, uri);
            }

            // §5.27 E-Hentai 账号API
            if (uri.startsWith("/api/v1/eh/auth")) {
                return ehAuthApiHandler.handlePost(session, uri);
            }

            // §5.26 收藏管理API（加入收藏 / 源站收藏）
            if (uri.startsWith("/api/v1/favorites")) {
                return galleryApiHandler.handlePost(session, uri);
            }

            // 页面API（上传页面图片）—— 必须在画廊API之前匹配，因路径包含 /api/v1/galleries
            if (uri.matches("/api/v1/galleries/\\d+/pages/\\d+/upload")) {
                return pageApiHandler.handlePost(session, uri);
            }

            // 推送API
            if (uri.startsWith("/api/v1/push")) {
                return pushApiHandler.handlePost(session, uri);
            }

            // 数据导入API
            if (uri.startsWith("/api/v1/data")) {
                return dataApiHandler.handlePost(session, uri);
            }

            // 压缩API
            if (uri.startsWith("/api/v1/compress")) {
                return compressApiHandler.handlePost(session, uri);
            }

            // 下载管理API
            if (uri.startsWith("/api/v1/downloads")) {
                return downloadApiHandler.handlePost(session, uri);
            }

            // 设备连接API
            if (uri.startsWith("/api/v1/connect")) {
                return connectApiHandler.handlePost(session, uri);
            }

            // 系统API
            if (uri.startsWith("/api/v1/system")) {
                return systemApiHandler.handlePost(session, uri);
            }

            // 接力下载API
            if (uri.startsWith("/api/v1/relay")) {
                return relayApiHandler.handlePost(session, uri);
            }

            // 实验室 / 多机联动（v3.0）API
            if (uri.startsWith("/api/v1/lab/sync")) {
                return labSyncApiHandler.handlePost(session, uri);
            }
            if (uri.startsWith("/api/v1/lab")) {
                if (uri.startsWith("/api/v1/lab/db")) {
                    return snapshotApiHandler.handlePost(session, uri);
                }
                return labApiHandler.handlePost(session, uri);
            }

            // 辅助下载API（v1 §5.24）
            if (uri.startsWith("/api/v1/aux-download")) {
                return auxDownloadApiHandler.handlePost(session, uri);
            }

            // 统一任务API（后台任务创建 / 开始 / 暂停 / 恢复 / 停止，传输任务控制）
            if (uri.startsWith("/api/v1/tasks") || uri.startsWith("/api/v1/background-tasks")) {
                return tasksApiHandler.handlePost(session, uri);
            }

            return ResponseBuilder.notFound("Endpoint");
        }
        
        private Response handlePut(IHTTPSession session, String uri) {
            // 设置API
            if (uri.startsWith("/api/v1/settings")) {
                return settingsApiHandler.handlePut(session, uri);
            }

            // 实验室 / 多机联动（v3.0）API
            if (uri.startsWith("/api/v1/lab")) {
                return labApiHandler.handlePut(session, uri);
            }

            return ResponseBuilder.notFound("Endpoint");
        }
        
        private Response handleDelete(IHTTPSession session, String uri) {
            // 画廊API
            if (uri.startsWith("/api/v1/galleries")) {
                return galleryApiHandler.handleDelete(session, uri);
            }

            // §5.26 收藏管理API（取消收藏）
            if (uri.startsWith("/api/v1/favorites")) {
                return galleryApiHandler.handleDelete(session, uri);
            }
            
            // 文件API
            if (uri.startsWith("/api/v1/folders")) {
                return fileApiHandler.handleDelete(session, uri);
            }
            
            // 压缩API
            if (uri.startsWith("/api/v1/compress")) {
                return compressApiHandler.handleDelete(session, uri);
            }
            
            // 统一任务API
            if (uri.startsWith("/api/v1/tasks")) {
                return tasksApiHandler.handleDelete(session, uri);
            }
            
            // 下载管理API
            if (uri.startsWith("/api/v1/downloads")) {
                return downloadApiHandler.handleDelete(session, uri);
            }
            
            // 设备连接API
            if (uri.startsWith("/api/v1/connect")) {
                return connectApiHandler.handleDelete(session, uri);
            }
            
            // 接力下载API
            if (uri.startsWith("/api/v1/relay")) {
                return relayApiHandler.handleDelete(session, uri);
            }

            // 实验室 / 多机联动（v3.0）API
            if (uri.startsWith("/api/v1/lab")) {
                return labApiHandler.handleDelete(session, uri);
            }

            return ResponseBuilder.notFound("Endpoint");
        }
        
        private Response handlePatch(IHTTPSession session, String uri) {
            return ResponseBuilder.notFound("Endpoint");
        }
        
        private Response handleQuery(IHTTPSession session, String uri) {
            // 画廊API
            if (uri.startsWith("/api/v1/galleries")) {
                return galleryApiHandler.handleQuery(session, uri);
            }

            // 收藏API
            if (uri.startsWith("/api/v1/favorites")) {
                return galleryApiHandler.handleQuery(session, uri);
            }

            // §5.25 在线画廊API
            if (uri.startsWith("/api/v1/online")) {
                return onlineApiHandler.handleQuery(session, uri);
            }

            return ResponseBuilder.notFound("Endpoint");
        }
        
        private Response serveStaticResource(String uri) {
            TransferLogger logger = TransferLogger.getInstance();
            logger.d(TAG, "加载静态资源: " + uri);
            
            try {
                // 从assets加载静态资源
                String path = uri.substring(1); // 去掉开头的/
                logger.d(TAG, "资源路径: " + path);
                
                InputStream stream = context.getAssets().open(path);
                logger.d(TAG, "资源加载成功: " + path + " (" + stream.available() + " bytes)");
                
                // 确定MIME类型
                String mimeType = getMimeType(path);
                
                // 直接以 chunked 方式流式返回，避免将整段资源读入内存造成 OOM
                return NanoHTTPD.newChunkedResponse(
                    Response.Status.OK,
                    mimeType,
                    stream
                );
                
            } catch (IOException e) {
                logger.e(TAG, "资源加载失败: " + uri, e);
                return ResponseBuilder.notFound("Resource");
            }
        }
        
        private String getMimeType(String path) {
            if (path.endsWith(".html")) return "text/html";
            if (path.endsWith(".css")) return "text/css";
            if (path.endsWith(".js")) return "application/javascript";
            if (path.endsWith(".json")) return "application/json";
            if (path.endsWith(".yaml") || path.endsWith(".yml")) return "application/yaml";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
            if (path.endsWith(".gif")) return "image/gif";
            if (path.endsWith(".svg")) return "image/svg+xml";
            if (path.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }
    }
}
