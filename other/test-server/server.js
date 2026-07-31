/**
 * EhViewer Remote Manager - Pure API Mock Server
 * 支持 OpenAPI 3.0 + Swagger UI
 */

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 8080;
const HOST = '0.0.0.0';

// 模拟数据
const mockGalleries = [];
const categories = ['Doujinshi', 'Manga', 'Artist CG', 'Game CG', 'Image Set', 'Cosplay'];
const uploaders = ['user_alpha', 'user_beta', 'user_gamma', 'user_delta', 'user_epsilon'];
const languages = ['Chinese', 'Japanese', 'English', 'Korean'];

for (let i = 1; i <= 100; i++) {
    mockGalleries.push({
        gid: 1000 + i,
        token: `token${i}`,
        title: `[Test Author ${i % 10}] Test Gallery ${i} - 这是一个测试画廊标题`,
        titleJpn: `[テスト作者] テストギャラリー${i}`,
        thumb: `https://picsum.photos/seed/gallery${i}/300/400`,
        category: categories[i % categories.length],
        posted: `2026-0${1 + (i % 9)}-${10 + (i % 20)}`,
        uploader: uploaders[i % uploaders.length],
        rating: 2.5 + (i % 5) * 0.5,
        pages: 10 + i % 50,
        state: 3,
        label: i % 3 === 0 ? '默认' : '收藏',
        time: Date.now() - i * 100000,
        language: languages[i % languages.length],
        tags: [
            `artist:artist${i % 10}`,
            `language:${languages[i % languages.length].toLowerCase()}`,
            `female:tag${i % 8}`,
            `male:tag${(i + 3) % 5}`
        ],
        finished: 10 + i % 50,
        downloaded: 10 + i % 50,
        total: 10 + i % 50,
        fileSize: (10 + i % 50) * 5 * 1024 * 1024
    });
}

const mockLabels = [
    { name: '默认', count: 67 },
    { name: '收藏', count: 33 }
];

const mockFolders = [
    { name: 'Output', path: '/sdcard/EhViewer/Output', fileCount: 15, totalSize: 52428800, totalSizeFormatted: '50.0 MB' },
    { name: 'logcat', path: '/sdcard/EhViewer/logcat', fileCount: 8, totalSize: 10485760, totalSizeFormatted: '10.0 MB' },
    { name: 'logs', path: '/sdcard/EhViewer/logs', fileCount: 3, totalSize: 5242880, totalSizeFormatted: '5.0 MB' },
    { name: 'crash', path: '/sdcard/EhViewer/crash', fileCount: 0, totalSize: 0, totalSizeFormatted: '0 B' },
    { name: 'data', path: '/sdcard/EhViewer/data', fileCount: 5, totalSize: 20971520, totalSizeFormatted: '20.0 MB' },
    { name: 'parse_error', path: '/sdcard/EhViewer/parse_error', fileCount: 0, totalSize: 0, totalSizeFormatted: '0 B' }
];

// 推送任务存储
const mockPushTasks = [];

// 接收设置
let receiveSettings = {
    autoReceiveBookmarks: false,
    autoReceiveDownloads: false,
    autoReceiveFavorites: false,
    pageSize: 50
};

// 收藏数据（从画廊数据中选取部分作为本地收藏）
const mockFavorites = mockGalleries.slice(0, 30).map(g => ({
    gid: g.gid,
    token: g.token,
    title: g.title,
    titleJpn: g.titleJpn,
    thumb: g.thumb,
    favCat: 0,
    favNote: ''
}));

// 下载任务存储
const mockDownloads = mockGalleries.slice(0, 20).map(g => {
    const state = g.state || 3;
    const downloaded = g.downloaded || g.pages;
    return {
        gid: g.gid,
        token: g.token,
        title: g.title,
        titleJpn: g.titleJpn,
        thumb: g.thumb,
        category: g.category,
        posted: g.posted,
        uploader: g.uploader,
        rating: g.rating,
        language: g.language,
        pages: g.pages,
        state,
        stateName: downloadStateToName(state),
        label: g.label,
        time: g.time,
        createdTime: g.time,
        createdDate: new Date(g.time).toLocaleString(),
        finished: downloaded,
        total: g.pages,
        downloaded,
        speed: 0,
        speedFormatted: '0 B/s',
        remaining: g.pages - downloaded,
        progress: Math.round(downloaded / g.pages * 1000) / 10,
        legacy: 0,
        fileSize: g.fileSize,
        simpleTags: g.tags || []
    };
});

function downloadStateToName(state) {
    const map = { 0: 'none', 1: 'wait', 2: 'downloading', 3: 'finished', 4: 'failed', 5: 'update' };
    return map[state] || 'none';
}

// 已连接设备
const mockPeers = [];

// 接力任务存储
const mockRelayTasks = [];

// 生成模拟文件
function generateMockFiles(folder) {
    const files = [];
    const count = mockFolders.find(f => f.name === folder)?.fileCount || 0;
    for (let i = 1; i <= count; i++) {
        files.push({
            name: `${folder.toLowerCase()}_${i}.txt`,
            path: `/sdcard/EhViewer/${folder}/${folder.toLowerCase()}_${i}.txt`,
            size: 1024 * i * 10,
            sizeFormatted: `${(i * 10)} KB`,
            lastModified: Date.now() - i * 86400000,
            lastModifiedFormatted: new Date(Date.now() - i * 86400000).toLocaleString(),
            extension: 'txt'
        });
    }
    return files;
}

// 格式化文件大小
function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

// MIME类型
const MIME_TYPES = {
    '.html': 'text/html',
    '.css': 'text/css',
    '.js': 'application/javascript',
    '.json': 'application/json',
    '.yaml': 'application/yaml',
    '.yml': 'application/yaml',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.svg': 'image/svg+xml'
};

// 读取 OpenAPI 规范文件
function getOpenApiSpec() {
    const specPath = path.join(__dirname, 'openapi', 'openapi.yaml');
    return fs.readFileSync(specPath, 'utf-8');
}

// 生成 API 文档 HTML（Swagger UI）
function getSwaggerUI() {
    return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>EhViewer API - Swagger UI</title>
    <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
    <style>
        html { box-sizing: border-box; overflow-y: scroll; }
        *, *:before, *:after { box-sizing: inherit; }
        body { margin: 0; background: #fafafa; }
        .topbar { display: none !important; }
        .fallback { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; padding: 40px 20px; text-align: center; }
        .fallback h1 { color: #333; margin-bottom: 16px; }
        .fallback p { color: #666; margin-bottom: 24px; }
        .fallback a { display: inline-block; padding: 12px 24px; background: #2196F3; color: white; border-radius: 6px; text-decoration: none; margin: 8px; }
        .fallback a:hover { background: #1976D2; }
        .fallback .links { margin-top: 24px; }
        .fallback .links a { background: #4CAF50; }
        .fallback .links a:hover { background: #388E3C; }
    </style>
</head>
<body>
    <div id="swagger-ui">
        <div class="fallback">
            <h1>EhViewer Remote Manager API</h1>
            <p>正在加载 Swagger UI...</p>
            <p>如果加载失败，请使用以下链接：</p>
            <div class="links">
                <a href="/openapi.yaml">OpenAPI 规范 (YAML)</a>
                <a href="/api/v1/debug">API 调试页面</a>
            </div>
        </div>
    </div>
    <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
    <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-standalone-preset.js"></script>
    <script>
        try {
            SwaggerUIBundle({
                url: '/openapi.yaml',
                dom_id: '#swagger-ui',
                deepLinking: true,
                presets: [
                    SwaggerUIBundle.presets.apis,
                    SwaggerUIStandalonePreset
                ],
                plugins: [
                    SwaggerUIBundle.plugins.DownloadUrl
                ],
                layout: "StandaloneLayout",
                defaultModelsExpandDepth: 1,
                defaultModelExpandDepth: 1,
                docExpansion: 'list',
                filter: true,
                showRequestHeaders: true
            });
        } catch (e) {
            document.getElementById('swagger-ui').innerHTML = 
                '<div class="fallback"><h1>Swagger UI 加载失败</h1><p>请检查网络连接或使用以下链接：</p><div class="links"><a href="/openapi.yaml">OpenAPI 规范</a><a href="/api/v1/debug">API 调试</a></div></div>';
        }
    </script>
</body>
</html>`;
}

// 生成调试页面（重定向到 Swagger UI）
function getDebugPageHTML() {
    return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>EhViewer API Debug</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; max-width: 800px; margin: 60px auto; padding: 0 20px; background: #fafafa; color: #333; }
        h1 { color: #1a1a2e; border-bottom: 2px solid #2196F3; padding-bottom: 12px; }
        .card { background: #fff; border-radius: 8px; padding: 24px; margin: 16px 0; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
        a.btn { display: inline-block; padding: 12px 24px; background: #2196F3; color: white; border-radius: 6px; text-decoration: none; font-weight: 500; margin: 8px 8px 8px 0; transition: background 0.2s; }
        a.btn:hover { background: #1976D2; }
        a.btn.secondary { background: #4CAF50; }
        a.btn.secondary:hover { background: #388E3C; }
        .endpoint { display: block; margin: 6px 0; padding: 8px 12px; background: #f5f5f5; border-radius: 4px; text-decoration: none; color: #2196F3; font-family: monospace; }
        .endpoint:hover { background: #e3f2fd; }
        h2 { color: #666; margin-top: 24px; font-size: 16px; }
    </style>
</head>
<body>
    <h1>EhViewer Remote Manager API</h1>
    <div class="card">
        <p>API 调试与文档中心</p>
        <a class="btn" href="/docs">Swagger UI 文档</a>
        <a class="btn secondary" href="/openapi.yaml">OpenAPI 规范</a>
    </div>

    <h2>Auth</h2>
    <a class="endpoint" href="/api/v1/auth/status">GET /api/v1/auth/status</a>

    <h2>Galleries</h2>
    <a class="endpoint" href="/api/v1/galleries?page=1&limit=10">GET /api/v1/galleries?page=1&limit=10</a>
    <a class="endpoint" href="/api/v1/galleries/1001">GET /api/v1/galleries/1001</a>
    <a class="endpoint" href="/api/v1/galleries/1001/thumbnail">GET /api/v1/galleries/1001/thumbnail</a>

    <h2>Labels</h2>
    <a class="endpoint" href="/api/v1/labels">GET /api/v1/labels</a>

    <h2>Folders & Files</h2>
    <a class="endpoint" href="/api/v1/folders">GET /api/v1/folders</a>
    <a class="endpoint" href="/api/v1/folders/logcat/files">GET /api/v1/folders/logcat/files</a>
    <a class="endpoint" href="/api/v1/folders/Output/files">GET /api/v1/folders/Output/files</a>

    <h2>System</h2>
    <a class="endpoint" href="/api/v1/system/info">GET /api/v1/system/info</a>
    <a class="endpoint" href="/api/v1/system/stats">GET /api/v1/system/stats</a>

    <h2>Push</h2>
    <a class="endpoint" href="/api/v1/push/tasks">GET /api/v1/push/tasks</a>

    <h2>Settings</h2>
    <a class="endpoint" href="/api/v1/settings/receive">GET /api/v1/settings/receive</a>

    <h2>Favorites</h2>
    <a class="endpoint" href="/api/v1/favorites?page=1&limit=10">GET /api/v1/favorites?page=1&limit=10</a>

    <h2>Downloads</h2>
    <a class="endpoint" href="/api/v1/downloads?state=all&page=1&limit=10">GET /api/v1/downloads?state=all&page=1&limit=10</a>
    <a class="endpoint" href="/api/v1/downloads/1001">GET /api/v1/downloads/1001</a>

    <h2>Connect</h2>
    <a class="endpoint" href="/api/v1/connect/peers">GET /api/v1/connect/peers</a>

    <h2>Relay</h2>
    <a class="endpoint" href="/api/v1/relay/tasks?status=all&direction=all">GET /api/v1/relay/tasks</a>

    <h2>Tasks</h2>
    <a class="endpoint" href="/api/v1/tasks?type=all&status=all">GET /api/v1/tasks</a>

    <h2>Device</h2>
    <a class="endpoint" href="/api/v1/device/info">GET /api/v1/device/info</a>
</body>
</html>`;
}

// 创建服务器
const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const pathname = url.pathname;
    const method = req.method;
    const requestId = Math.random().toString(36).substr(2, 6);
    const startTime = Date.now();

    console.log(`[${new Date().toLocaleTimeString()}] [${requestId}] → ${method} ${pathname}`);

    // CORS
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS, QUERY');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, Range');

    if (method === 'OPTIONS') {
        res.writeHead(200);
        res.end();
        return;
    }

    try {
        // ==================== OpenAPI / Swagger UI ====================
        if (pathname === '/openapi.yaml' || pathname === '/openapi') {
            const spec = getOpenApiSpec();
            const elapsed = Date.now() - startTime;
            console.log(`[${requestId}] ← 200 openapi.yaml (${elapsed}ms)`);
            res.writeHead(200, { 'Content-Type': 'application/yaml; charset=utf-8' });
            res.end(spec);
            return;
        }

        if (pathname === '/docs' || pathname === '/swagger') {
            const html = getSwaggerUI();
            const elapsed = Date.now() - startTime;
            console.log(`[${requestId}] ← 200 swagger-ui (${elapsed}ms)`);
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            res.end(html);
            return;
        }

        // ==================== Auth ====================
        if (pathname === '/api/v1/auth/status') {
            handleAuthStatus(res, requestId);
        } else if (pathname === '/api/v1/auth/login' && method === 'POST') {
            handleLogin(req, res, requestId);
        } else if (pathname === '/api/v1/auth/logout' && method === 'POST') {
            handleLogout(res, requestId);
        }
        // ==================== Galleries ====================
        else if (pathname === '/api/v1/galleries' && method === 'GET') {
            handleGalleries(req, res, url, requestId);
        } else if (pathname === '/api/v1/galleries' && method === 'QUERY') {
            handleGalleriesQuery(req, res, requestId);
        } else if (pathname.match(/^\/api\/v1\/galleries\/batch$/) && method === 'DELETE') {
            handleBatchDeleteGalleries(req, res, requestId);
        } else if (pathname.match(/^\/api\/v1\/galleries\/\d+$/) && method === 'GET') {
            const gid = parseInt(pathname.split('/').pop());
            handleGalleryDetail(res, gid, requestId);
        } else if (pathname.match(/^\/api\/v1\/galleries\/\d+$/) && method === 'DELETE') {
            const gid = parseInt(pathname.split('/').pop());
            handleGalleryDelete(res, gid, requestId);
        } else if (pathname.match(/^\/api\/v1\/galleries\/\d+\/thumbnail$/)) {
            const gid = parseInt(pathname.split('/')[4]);
            handleThumbnail(req, res, gid, url, requestId);
        }
        // ==================== Pages ====================
        else if (pathname.match(/^\/api\/v1\/galleries\/\d+\/pages$/) && method === 'GET') {
            const gid = parseInt(pathname.split('/')[4]);
            handlePages(res, gid, requestId);
        } else if (pathname.match(/^\/api\/v1\/galleries\/\d+\/pages\/\d+$/) && method === 'GET') {
            const parts = pathname.split('/');
            const gid = parseInt(parts[4]);
            const page = parseInt(parts[6]);
            handlePageImage(res, gid, page, requestId);
        }
        // ==================== Labels ====================
        else if (pathname === '/api/v1/labels') {
            handleLabels(res, requestId);
        } else if (pathname.match(/^\/api\/v1\/labels\/[^/]+\/galleries$/)) {
            const label = decodeURIComponent(pathname.split('/')[4]);
            handleLabelGalleries(req, res, label, url, requestId);
        }
        // ==================== Folders ====================
        else if (pathname === '/api/v1/folders') {
            handleFolders(res, requestId);
        }
        // ==================== Files ====================
        else if (pathname.match(/^\/api\/v1\/folders\/[^/]+\/files$/) && method === 'GET') {
            const folder = decodeURIComponent(pathname.split('/')[4]);
            handleFiles(req, res, folder, url, requestId);
        } else if (pathname.match(/^\/api\/v1\/folders\/[^/]+\/files\/[^/]+\/preview$/)) {
            const parts = pathname.split('/');
            const folder = decodeURIComponent(parts[4]);
            const filename = decodeURIComponent(parts[6]);
            handleFilePreview(res, folder, filename, requestId);
        } else if (pathname.match(/^\/api\/v1\/folders\/[^/]+\/files\/batch/) && method === 'DELETE') {
            const folder = decodeURIComponent(pathname.split('/')[4]);
            handleBatchDelete(req, res, folder, requestId);
        } else if (pathname.match(/^\/api\/v1\/folders\/[^/]+\/files\/[^/]+/) && method === 'DELETE') {
            const parts = pathname.split('/');
            const folder = decodeURIComponent(parts[4]);
            const filename = decodeURIComponent(parts[6]);
            handleFileDelete(res, folder, filename, requestId);
        } else if (pathname.match(/^\/api\/v1\/folders\/[^/]+\/files\/[^/]+/) && method === 'GET') {
            const parts = pathname.split('/');
            const folder = decodeURIComponent(parts[4]);
            const filename = decodeURIComponent(parts[6]);
            handleFileDownload(res, folder, filename, requestId);
        }
        // ==================== Integrity Check ====================
        else if (pathname.match(/^\/api\/v1\/folders\/[^/]+\/files\/[^/]+\/hash$/) && method === 'GET') {
            const parts = pathname.split('/');
            const folder = decodeURIComponent(parts[4]);
            const filename = decodeURIComponent(parts[6]);
            const algorithm = url.searchParams.get('algorithm') || 'md5';
            handleFileHash(res, folder, filename, algorithm, requestId);
        } else if (pathname.match(/^\/api\/v1\/folders\/downloads\/files\/\d+\/integrity$/) && method === 'GET') {
            const gid = parseInt(pathname.split('/')[6]);
            const algorithm = url.searchParams.get('algorithm') || 'md5';
            handleGalleryIntegrity(res, gid, algorithm, requestId);
        } else if (pathname.match(/^\/api\/v1\/folders\/downloads\/files\/\d+\/verify$/) && method === 'POST') {
            const gid = parseInt(pathname.split('/')[6]);
            handleGalleryVerify(req, res, gid, requestId);
        }
        // ==================== System ====================
        else if (pathname === '/api/v1/system/info') {
            handleSystemInfo(res, requestId);
        } else if (pathname === '/api/v1/system/stats') {
            handleSystemStats(res, requestId);
        }
        // ==================== Push ====================
        else if (pathname === '/api/v1/push/create' && method === 'POST') {
            handlePushCreate(req, res, requestId);
        } else if (pathname === '/api/v1/push/tasks' && method === 'GET') {
            handlePushTasks(res, requestId);
        } else if (pathname.match(/^\/api\/v1\/push\/tasks\/[^/]+$/) && method === 'GET') {
            const id = pathname.split('/').pop();
            handlePushTask(res, id, requestId);
        } else if (pathname.match(/^\/api\/v1\/push\/tasks\/[^/]+\/accept$/) && method === 'POST') {
            const id = pathname.split('/')[5];
            handlePushTaskAccept(res, id, requestId);
        } else if (pathname.match(/^\/api\/v1\/push\/tasks\/[^/]+\/reject$/) && method === 'POST') {
            const id = pathname.split('/')[5];
            handlePushTaskReject(res, id, requestId);
        } else if (pathname.match(/^\/api\/v1\/push\/tasks\/[^/]+\/data$/) && method === 'GET') {
            const id = pathname.split('/')[5];
            handlePushTaskData(req, res, id, url, requestId);
        }
        // ==================== Settings ====================
        else if (pathname === '/api/v1/settings/receive' && method === 'GET') {
            handleGetReceiveSettings(res, requestId);
        } else if (pathname === '/api/v1/settings/receive' && method === 'PUT') {
            handleUpdateReceiveSettings(req, res, requestId);
        }
        // ==================== Data Export/Import ====================
        else if (pathname === '/api/v1/data/export/files' && method === 'GET') {
            handleDataExportFiles(res, requestId);
        } else if (pathname === '/api/v1/data/export/bookmarks' && method === 'GET') {
            handleDataExportBookmarks(res, requestId);
        } else if (pathname === '/api/v1/data/export/favorites' && method === 'GET') {
            handleDataExportFavorites(res, requestId);
        } else if (pathname === '/api/v1/data/export/downloads' && method === 'GET') {
            handleDataExportDownloads(res, requestId);
        } else if (pathname === '/api/v1/data/export/db' && method === 'GET') {
            handleDataExportDB(res, requestId);
        } else if (pathname === '/api/v1/data/export/csv' && method === 'GET') {
            handleDataExportCSV(res, requestId);
        } else if (pathname === '/api/v1/data/import/bookmarks' && method === 'POST') {
            handleDataImport(req, res, 'bookmarks', requestId);
        } else if (pathname === '/api/v1/data/import/favorites' && method === 'POST') {
            handleDataImport(req, res, 'favorites', requestId);
        } else if (pathname === '/api/v1/data/import/downloads' && method === 'POST') {
            handleDataImport(req, res, 'downloads', requestId);
        } else if (pathname === '/api/v1/data/import/db' && method === 'POST') {
            handleDataImportDB(req, res, requestId);
        } else if (pathname === '/api/v1/data/import/csv' && method === 'POST') {
            handleDataImportCSV(req, res, requestId);
        }
        // ==================== Compress ====================
        else if (pathname === '/api/v1/compress/create' && method === 'POST') {
            handleCompressCreate(req, res, requestId);
        } else if (pathname === '/api/v1/compress/tasks' && method === 'GET') {
            handleCompressTasks(res, requestId);
        } else if (pathname.match(/^\/api\/v1\/compress\/tasks\/[^/]+\/download/) && method === 'GET') {
            const taskId = pathname.split('/')[5];
            handleCompressDownload(res, taskId, requestId);
        } else if (pathname.match(/^\/api\/v1\/compress\/tasks\/[^/]+$/) && method === 'GET') {
            const taskId = pathname.split('/')[5];
            handleCompressTaskStatus(res, taskId, requestId);
        } else if (pathname.match(/^\/api\/v1\/compress\/tasks\/[^/]+$/) && method === 'DELETE') {
            const taskId = pathname.split('/')[5];
            handleCompressTaskDelete(res, taskId, requestId);
        } else if (pathname === '/api/v1/compress/import' && method === 'POST') {
            handleCompressImport(req, res, requestId);
        } else if (pathname === '/api/v1/compress/import/confirm' && method === 'POST') {
            handleCompressImportConfirm(req, res, requestId);
        }
        // ==================== Push Data (POST) ====================
        else if (pathname.match(/^\/api\/v1\/push\/tasks\/[^/]+\/data$/) && method === 'POST') {
            const id = pathname.split('/')[5];
            handlePushTaskDataPost(req, res, id, requestId);
        }
        // ==================== Favorites ====================
        else if (pathname === '/api/v1/favorites' && method === 'GET') {
            handleFavorites(req, res, url, requestId);
        } else if (pathname === '/api/v1/favorites' && method === 'QUERY') {
            handleFavoritesQuery(req, res, requestId);
        }
        // ==================== Downloads ====================
        else if (pathname === '/api/v1/downloads/batch/start' && method === 'POST') {
            handleBatchStartDownloads(req, res, requestId);
        } else if (pathname === '/api/v1/downloads/batch/pause' && method === 'POST') {
            handleBatchPauseDownloads(req, res, requestId);
        } else if (pathname === '/api/v1/downloads/batch' && method === 'DELETE') {
            handleBatchDeleteDownloads(req, res, requestId);
        } else if (pathname === '/api/v1/downloads' && method === 'GET') {
            handleDownloads(req, res, url, requestId);
        } else if (pathname === '/api/v1/downloads' && method === 'POST') {
            handleCreateDownload(req, res, requestId);
        } else if (pathname.match(/^\/api\/v1\/downloads\/\d+\/start$/) && method === 'POST') {
            const gid = parseInt(pathname.split('/')[4]);
            handleStartDownload(res, gid, requestId);
        } else if (pathname.match(/^\/api\/v1\/downloads\/\d+\/pause$/) && method === 'POST') {
            const gid = parseInt(pathname.split('/')[4]);
            handlePauseDownload(res, gid, requestId);
        } else if (pathname.match(/^\/api\/v1\/downloads\/\d+$/) && method === 'DELETE') {
            const gid = parseInt(pathname.split('/')[4]);
            handleDeleteDownload(res, gid, requestId);
        } else if (pathname.match(/^\/api\/v1\/downloads\/\d+$/) && method === 'GET') {
            const gid = parseInt(pathname.split('/')[4]);
            handleDownloadDetail(res, gid, requestId);
        }
        // ==================== Connect ====================
        else if (pathname === '/api/v1/connect' && method === 'POST') {
            handleConnect(req, res, requestId);
        } else if (pathname === '/api/v1/connect' && method === 'DELETE') {
            handleDisconnect(req, res, requestId);
        } else if (pathname === '/api/v1/connect/peers' && method === 'GET') {
            handleGetPeers(res, requestId);
        }
        // ==================== Relay ====================
        else if (pathname === '/api/v1/relay/batch' && method === 'POST') {
            handleRelayBatch(req, res, requestId);
        } else if (pathname === '/api/v1/relay/create' && method === 'POST') {
            handleRelayCreate(req, res, requestId);
        } else if (pathname === '/api/v1/relay/tasks' && method === 'GET') {
            handleRelayTasks(req, res, url, requestId);
        } else if (pathname.match(/^\/api\/v1\/relay\/[^/]+\/status$/) && method === 'GET') {
            const taskId = pathname.split('/')[4];
            handleRelayStatus(res, taskId, requestId);
        } else if (pathname.match(/^\/api\/v1\/relay\/[^/]+\/accept$/) && method === 'POST') {
            const taskId = pathname.split('/')[4];
            handleRelayAccept(res, taskId, requestId);
        } else if (pathname.match(/^\/api\/v1\/relay\/[^/]+\/reject$/) && method === 'POST') {
            const taskId = pathname.split('/')[4];
            handleRelayReject(res, taskId, requestId);
        } else if (pathname.match(/^\/api\/v1\/relay\/[^/]+\/cancel$/) && method === 'POST') {
            const taskId = pathname.split('/')[4];
            handleRelayCancel(res, taskId, requestId);
        } else if (pathname.match(/^\/api\/v1\/relay\/[^/]+\/download$/) && method === 'GET') {
            const taskId = pathname.split('/')[4];
            handleRelayDownload(res, taskId, requestId);
        } else if (pathname.match(/^\/api\/v1\/relay\/[^/]+$/) && method === 'DELETE') {
            const taskId = pathname.split('/')[4];
            handleRelayDelete(res, taskId, requestId);
        }
        // ==================== Unified Tasks ====================
        else if (pathname === '/api/v1/tasks' && method === 'GET') {
            handleUnifiedTasks(req, res, url, requestId);
        } else if (pathname.match(/^\/api\/v1\/tasks\/[^/]+$/) && method === 'GET') {
            const taskId = pathname.split('/').pop();
            handleUnifiedTaskDetail(res, taskId, requestId);
        } else if (pathname.match(/^\/api\/v1\/tasks\/[^/]+$/) && method === 'DELETE') {
            const taskId = pathname.split('/').pop();
            handleUnifiedTaskDelete(res, taskId, requestId);
        }
        // ==================== Device ====================
        else if (pathname === '/api/v1/device/info') {
            handleDeviceInfo(res, requestId);
        }
        // ==================== Debug ====================
        else if (pathname === '/api/v1/debug') {
            const html = getDebugPageHTML();
            const elapsed = Date.now() - startTime;
            console.log(`[${requestId}] ← 200 debug (${elapsed}ms)`);
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            res.end(html);
        }
        // ==================== Root ====================
        else if (pathname === '/') {
            res.writeHead(302, { 'Location': '/docs' });
            res.end();
        }
        // ==================== 404 ====================
        else {
            const elapsed = Date.now() - startTime;
            console.log(`[${requestId}] ← 404 (${elapsed}ms)`);
            res.writeHead(404, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ success: false, error: 'Not found' }));
        }
    } catch (error) {
        const elapsed = Date.now() - startTime;
        console.error(`[${requestId}] ✗ ERROR (${elapsed}ms):`, error.message);
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: error.message }));
    }
});

// ==================== Handler Functions ====================

function handleAuthStatus(res, requestId) {
    const data = {
        mode: 'none',
        authenticated: true,
        isLocalNetwork: true,
        remoteIp: '127.0.0.1'
    };
    console.log(`[${requestId}] ← 200 auth/status: mode=${data.mode}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(data));
}

function handleLogin(req, res, requestId) {
    readBody(req, (body) => {
        console.log(`[${requestId}]   body: ${body}`);
        const data = {
            success: true,
            token: 'test-session-token-' + Date.now(),
            expires: 86400,
            mode: 'none'
        };
        console.log(`[${requestId}] ← 200 auth/login: success=${data.success}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(data));
    });
}

function handleLogout(res, requestId) {
    console.log(`[${requestId}] ← 200 auth/logout`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'Logged out' }));
}

function handleGalleries(req, res, url, requestId) {
    const page = parseInt(url.searchParams.get('page') || '1');
    const limit = parseInt(url.searchParams.get('limit') || '20');
    const label = url.searchParams.get('label');
    const search = url.searchParams.get('search');

    let filtered = [...mockGalleries];
    if (label) filtered = filtered.filter(g => g.label === label);
    if (search) {
        const s = search.toLowerCase();
        filtered = filtered.filter(g =>
            g.title.toLowerCase().includes(s) ||
            (g.titleJpn && g.titleJpn.toLowerCase().includes(s))
        );
    }

    const total = filtered.length;
    const start = (page - 1) * limit;
    const galleries = filtered.slice(start, start + limit);

    console.log(`[${requestId}] ← 200 galleries: total=${total}, page=${page}, 返回${galleries.length}条`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ total, page, limit, galleries }));
}

function handleGalleriesQuery(req, res, requestId) {
    readBody(req, (body) => {
        console.log(`[${requestId}]   QUERY body: ${body.substring(0, 200)}`);
        let query = {};
        try { query = JSON.parse(body); } catch (e) { }

        const page = query.page || 1;
        const limit = query.limit || 20;
        let filtered = [...mockGalleries];

        if (query.filter?.category) {
            filtered = filtered.filter(g => query.filter.category.includes(g.category));
        }

        const total = filtered.length;
        const start = (page - 1) * limit;
        const galleries = filtered.slice(start, start + limit);

        console.log(`[${requestId}] ← 200 QUERY galleries: total=${total}, page=${page}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ total, page, limit, galleries }));
    });
}

function handleBatchDeleteGalleries(req, res, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }
        const count = data.gids?.length || 0;
        console.log(`[${requestId}] ← 200 batch delete galleries: ${count}个`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, deleted: count, failed: 0 }));
    });
}

function handleGalleryDetail(res, gid, requestId) {
    const gallery = mockGalleries.find(g => g.gid === gid);
    if (!gallery) {
        console.log(`[${requestId}] ← 404 gallery/${gid}`);
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Not found' }));
        return;
    }

    const detail = {
        ...gallery,
        apiUid: -1,
        apiKey: '',
        torrentCount: 0,
        torrentUrl: '',
        archiveUrl: '',
        parent: '',
        visible: 'yes',
        size: `${gallery.pages * 5} MB`,
        favoriteCount: Math.floor(Math.random() * 100),
        isFavorited: false,
        ratingCount: Math.floor(Math.random() * 500),
        previewPages: Math.ceil(gallery.pages / 20),
        comments: []
    };

    console.log(`[${requestId}] ← 200 gallery/${gid}: ${detail.title}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(detail));
}

function handleGalleryDelete(res, gid, requestId) {
    const idx = mockGalleries.findIndex(g => g.gid === gid);
    if (idx >= 0) mockGalleries.splice(idx, 1);
    console.log(`[${requestId}] ← 200 delete gallery/${gid}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'Gallery deleted' }));
}

function handleThumbnail(req, res, gid, url, requestId) {
    const gallery = mockGalleries.find(g => g.gid === gid);
    if (!gallery) {
        res.writeHead(404, { 'Content-Type': 'text/plain' });
        res.end('Not found');
        return;
    }
    const picsumUrl = `https://picsum.photos/seed/gallery${gid}/300/400`;
    console.log(`[${requestId}] ← 302 thumbnail/${gid} → picsum`);
    res.writeHead(302, { 'Location': picsumUrl });
    res.end();
}

function handlePages(res, gid, requestId) {
    const gallery = mockGalleries.find(g => g.gid === gid);
    if (!gallery) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Gallery not found' }));
        return;
    }

    const pageList = [];
    for (let i = 1; i <= gallery.pages; i++) {
        pageList.push({
            page: i,
            state: i <= gallery.downloaded ? 'downloaded' : 'pending',
            pToken: `ptoken_${gid}_${i}`
        });
    }

    console.log(`[${requestId}] ← 200 pages/${gid}: ${gallery.pages}页`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ gid, pages: gallery.pages, pageList }));
}

function handlePageImage(res, gid, page, requestId) {
    const picsumUrl = `https://picsum.photos/seed/page${gid}-${page}/800/1200`;
    console.log(`[${requestId}] ← 302 page/${gid}/${page} → picsum`);
    res.writeHead(302, { 'Location': picsumUrl });
    res.end();
}

function handleLabels(res, requestId) {
    console.log(`[${requestId}] ← 200 labels: ${mockLabels.length}个`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ labels: mockLabels }));
}

function handleLabelGalleries(req, res, label, url, requestId) {
    const page = parseInt(url.searchParams.get('page') || '1');
    const limit = parseInt(url.searchParams.get('limit') || '20');
    let filtered = mockGalleries.filter(g => g.label === label);
    const total = filtered.length;
    const start = (page - 1) * limit;
    const galleries = filtered.slice(start, start + limit);

    console.log(`[${requestId}] ← 200 label/${label}/galleries: total=${total}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ total, page, limit, label, galleries }));
}

function handleFolders(res, requestId) {
    // 添加已下载画廊虚拟文件夹
    const downloadsFolder = {
        name: 'downloads',
        path: 'galleries',
        fileCount: mockGalleries.filter(g => g.state === 3).length,
        totalSize: 0,
        totalSizeFormatted: `${mockGalleries.filter(g => g.state === 3).length} 个画廊`
    };
    
    const allFolders = [downloadsFolder, ...mockFolders];
    console.log(`[${requestId}] ← 200 folders: ${allFolders.length}个`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ folders: allFolders }));
}

function handleFiles(req, res, folder, url, requestId) {
    const page = parseInt(url.searchParams.get('page') || '1');
    const limit = parseInt(url.searchParams.get('limit') || '50');
    const sort = url.searchParams.get('sort') || 'time';
    const order = url.searchParams.get('order') || 'desc';
    const search = url.searchParams.get('search') || '';

    // 处理已下载画廊列表
    if (folder === 'downloads') {
        let galleries = mockGalleries.filter(g => g.state === 3);
        
        if (search) {
            const s = search.toLowerCase();
            galleries = galleries.filter(g => 
                g.title.toLowerCase().includes(s) || 
                (g.titleJpn && g.titleJpn.toLowerCase().includes(s))
            );
        }
        
        if (sort === 'name') {
            galleries.sort((a, b) => order === 'asc' ? a.title.localeCompare(b.title) : b.title.localeCompare(a.title));
        } else if (sort === 'size') {
            galleries.sort((a, b) => order === 'asc' ? a.fileSize - b.fileSize : b.fileSize - a.fileSize);
        } else {
            galleries.sort((a, b) => order === 'asc' ? a.time - b.time : b.time - a.time);
        }
        
        const total = galleries.length;
        const start = (page - 1) * limit;
        const pagedGalleries = galleries.slice(start, start + limit);
        
        const files = pagedGalleries.map(g => ({
            name: `${g.gid} - ${g.title}`,
            path: `/sdcard/EhViewer/${g.gid}-${g.title}`,
            gid: g.gid,
            title: g.title,
            titleJpn: g.titleJpn,
            thumb: g.thumb,
            category: g.category,
            pages: g.pages,
            fileCount: g.pages,
            size: g.fileSize,
            sizeFormatted: formatSize(g.fileSize),
            lastModified: g.time,
            lastModifiedFormatted: new Date(g.time).toLocaleString(),
            extension: 'folder',
            isGallery: true
        }));
        
        console.log(`[${requestId}] ← 200 downloads: total=${total}, page=${page}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ folder: 'downloads', path: 'galleries', total, page, limit, sort, order, files }));
        return;
    }

    let files = generateMockFiles(folder);
    if (search) files = files.filter(f => f.name.toLowerCase().includes(search.toLowerCase()));

    if (sort === 'name') {
        files.sort((a, b) => order === 'asc' ? a.name.localeCompare(b.name) : b.name.localeCompare(a.name));
    } else if (sort === 'size') {
        files.sort((a, b) => order === 'asc' ? a.size - b.size : b.size - a.size);
    } else {
        files.sort((a, b) => order === 'asc' ? a.lastModified - b.lastModified : b.lastModified - a.lastModified);
    }

    const total = files.length;
    const start = (page - 1) * limit;
    const pagedFiles = files.slice(start, start + limit);

    console.log(`[${requestId}] ← 200 folders/${folder}/files: total=${total}, page=${page}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ folder, total, page, limit, sort, order, files: pagedFiles }));
}

function handleFilePreview(res, folder, filename, requestId) {
    const content = `=== ${filename} ===\nFolder: ${folder}\nGenerated at: ${new Date().toLocaleString()}\n\nThis is a test file content.\nLine 1: Hello World\nLine 2: Test data\nLine 3: More content\n...`;
    console.log(`[${requestId}] ← 200 preview ${folder}/${filename}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ name: filename, size: 10240, sizeFormatted: '10.0 KB', type: 'text', content, truncated: false }));
}

function handleFileDelete(res, folder, filename, requestId) {
    console.log(`[${requestId}] ← 200 delete ${folder}/${filename}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'File deleted' }));
}

function handleFileDownload(res, folder, filename, requestId) {
    const content = `Mock file content for ${filename} in ${folder}`;
    console.log(`[${requestId}] ← 200 download ${folder}/${filename}`);
    res.writeHead(200, {
        'Content-Type': 'application/octet-stream',
        'Content-Disposition': `attachment; filename="${filename}"`,
        'Content-Length': Buffer.byteLength(content)
    });
    res.end(content);
}

function handleBatchDelete(req, res, folder, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }
        const fileCount = data.files?.length || 0;
        console.log(`[${requestId}] ← 200 batch delete ${folder}: ${fileCount}个`);
        // 模拟删除延迟
        setTimeout(() => {
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ success: true, deleted: fileCount, failed: 0 }));
        }, 500);
    });
}

function handleSystemInfo(res, requestId) {
    console.log(`[${requestId}] ← 200 system/info`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
        deviceName: 'Test PC',
        deviceManufacturer: 'Test',
        androidVersion: '14',
        sdkVersion: 34,
        appVersion: '2.0.2.2',
        appVersionCode: 100,
        downloadLocation: '/sdcard/EhViewer',
        totalGalleries: mockGalleries.length,
        downloadedGalleries: mockGalleries.length,
        totalPages: mockGalleries.reduce((sum, g) => sum + g.pages, 0),
        storageTotal: 128000000000,
        storageUsed: 64000000000,
        storageFree: 64000000000,
        storageTotalFormatted: '119.21 GB',
        storageUsedFormatted: '59.60 GB',
        storageFreeFormatted: '59.60 GB',
        authMode: 'none',
        deleteEnabled: true,
        syncDownloadEnabled: false,
        remoteManagementEnabled: true
    }));
}

function handleSystemStats(res, requestId) {
    console.log(`[${requestId}] ← 200 system/stats`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
        totalGalleries: mockGalleries.length,
        downloading: 0,
        waiting: 0,
        finished: mockGalleries.length,
        failed: 0,
        none: 0
    }));
}

function handlePushCreate(req, res, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }
        const task = {
            id: 'push-' + Date.now(),
            type: data.type || 'downloads',
            status: 'pending',
            mode: data.mode || 'all',
            createdTime: Date.now(),
            fromDevice: 'Test PC',
            progress: 0,
            total: 0,
            transferred: 0
        };
        mockPushTasks.push(task);
        console.log(`[${requestId}] ← 200 push/create: ${task.id}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(task));
    });
}

function handlePushTasks(res, requestId) {
    console.log(`[${requestId}] ← 200 push/tasks: ${mockPushTasks.length}个`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ tasks: mockPushTasks }));
}

function handlePushTask(res, id, requestId) {
    const task = mockPushTasks.find(t => t.id === id);
    if (!task) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Task not found' }));
        return;
    }
    console.log(`[${requestId}] ← 200 push/tasks/${id}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(task));
}

function handlePushTaskAccept(res, id, requestId) {
    const task = mockPushTasks.find(t => t.id === id);
    if (task) task.status = 'accepted';
    console.log(`[${requestId}] ← 200 push/tasks/${id}/accept`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'Task accepted' }));
}

function handlePushTaskReject(res, id, requestId) {
    const task = mockPushTasks.find(t => t.id === id);
    if (task) task.status = 'rejected';
    console.log(`[${requestId}] ← 200 push/tasks/${id}/reject`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'Task rejected' }));
}

function handlePushTaskData(req, res, id, url, requestId) {
    const page = parseInt(url.searchParams.get('page') || '1');
    const limit = parseInt(url.searchParams.get('limit') || '50');
    const total = mockGalleries.length;
    const start = (page - 1) * limit;
    const items = mockGalleries.slice(start, start + limit);

    console.log(`[${requestId}] ← 200 push/tasks/${id}/data: page=${page}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ total, page, items }));
}

function handleGetReceiveSettings(res, requestId) {
    console.log(`[${requestId}] ← 200 settings/receive`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(receiveSettings));
}

function handleUpdateReceiveSettings(req, res, requestId) {
    readBody(req, (body) => {
        try {
            const data = JSON.parse(body);
            receiveSettings = { ...receiveSettings, ...data };
        } catch (e) { }
        console.log(`[${requestId}] ← 200 settings/receive (updated)`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, message: 'Settings updated' }));
    });
}

function handleDeviceInfo(res, requestId) {
    console.log(`[${requestId}] ← 200 device/info`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
        deviceId: 'test-device-001',
        deviceName: 'EhViewer Test Server',
        deviceType: 'server',
        appVersion: '2.0.2.2'
    }));
}

// ==================== Integrity Check Handlers ====================

function generateMockHash(length) {
    const chars = '0123456789abcdef';
    let result = '';
    for (let i = 0; i < length; i++) {
        result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return result;
}

function handleFileHash(res, folder, filename, algorithm, requestId) {
    const hashLength = algorithm === 'sha256' ? 64 : algorithm === 'sha1' ? 40 : 32;
    const hash = generateMockHash(hashLength);
    const size = Math.floor(Math.random() * 5000000) + 100000;

    console.log(`[${requestId}] ← 200 ${folder}/${filename}/hash (${algorithm})`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
        filename,
        path: `/sdcard/EhViewer/${folder}/${filename}`,
        size,
        sizeFormatted: formatSize(size),
        hash,
        algorithm,
        lastModified: Date.now() - Math.floor(Math.random() * 86400000 * 30),
        lastModifiedFormatted: new Date().toLocaleString()
    }));
}

function handleGalleryIntegrity(res, gid, algorithm, requestId) {
    const gallery = mockGalleries.find(g => g.gid === gid);
    const title = gallery ? gallery.title : `Gallery ${gid}`;
    const fileCount = gallery ? gallery.pages : 25;
    const hashLength = algorithm === 'sha256' ? 64 : algorithm === 'sha1' ? 40 : 32;

    const files = [];
    let totalSize = 0;

    // Add .ehviewer metadata file
    const metaSize = 1024;
    files.push({
        filename: '.ehviewer',
        size: metaSize,
        sizeFormatted: formatSize(metaSize),
        hash: generateMockHash(hashLength)
    });
    totalSize += metaSize;

    // Add .ehviewer.extra.json
    const extraSize = 4096;
    files.push({
        filename: '.ehviewer.extra.json',
        size: extraSize,
        sizeFormatted: formatSize(extraSize),
        hash: generateMockHash(hashLength)
    });
    totalSize += extraSize;

    // Add image files
    for (let i = 1; i <= fileCount; i++) {
        const size = Math.floor(Math.random() * 3000000) + 500000;
        files.push({
            filename: String(i).padStart(8, '0') + '.jpg',
            size,
            sizeFormatted: formatSize(size),
            hash: generateMockHash(hashLength)
        });
        totalSize += size;
    }

    console.log(`[${requestId}] ← 200 integrity/${gid}: ${files.length} files`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
        gid,
        title,
        folderName: `${gid} - ${title}`,
        totalFiles: files.length,
        totalSize,
        totalSizeFormatted: formatSize(totalSize),
        algorithm,
        files
    }));
}

function handleGalleryVerify(req, res, gid, requestId) {
    readBody(req, (body) => {
        let request = {};
        try { request = JSON.parse(body); } catch (e) { }

        const algorithm = request.algorithm || 'md5';
        const clientFiles = request.files || [];
        const hashLength = algorithm === 'sha256' ? 64 : algorithm === 'sha1' ? 40 : 32;

        const gallery = mockGalleries.find(g => g.gid === gid);
        const totalFiles = gallery ? gallery.pages + 2 : 27; // +2 for metadata files

        const details = [];
        let match = 0;
        let mismatch = 0;
        const missingFiles = [];

        // Process client files
        clientFiles.forEach(cf => {
            // Simulate most files matching
            if (Math.random() > 0.1) {
                details.push({ filename: cf.filename, status: 'match' });
                match++;
            } else {
                details.push({
                    filename: cf.filename,
                    status: 'mismatch',
                    remoteHash: generateMockHash(hashLength),
                    localHash: cf.hash,
                    remoteSize: cf.size,
                    localSize: cf.size
                });
                mismatch++;
            }
        });

        // Add some missing files
        const missingCount = Math.min(3, totalFiles - clientFiles.length);
        for (let i = 0; i < missingCount; i++) {
            const name = String(clientFiles.length + i + 1).padStart(8, '0') + '.jpg';
            missingFiles.push(name);
        }

        console.log(`[${requestId}] ← 200 verify/${gid}: match=${match}, mismatch=${mismatch}, missing=${missingFiles.length}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            gid,
            totalFiles,
            verifiedFiles: clientFiles.length,
            match,
            mismatch,
            missing: missingFiles.length,
            details,
            missingFiles
        }));
    });
}

// ==================== Data Export/Import Handlers ====================

function handleDataExportFiles(res, requestId) {
    console.log(`[${requestId}] ← 200 data/export/files`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
        dbFiles: [
            { name: '1720608000000.db', path: '/data/1720608000000.db', size: 1048576, sizeFormatted: '1.0 MB', lastModified: Date.now(), type: 'db' },
            { name: '1720607000000.db', path: '/data/1720607000000.db', size: 524288, sizeFormatted: '512.0 KB', lastModified: Date.now() - 86400000, type: 'db' }
        ],
        csvFiles: [
            { name: 'ehviewer-download-20260712.csv', path: '/Output/ehviewer-download-20260712.csv', size: 262144, sizeFormatted: '256.0 KB', lastModified: Date.now(), type: 'csv' }
        ]
    }));
}

function handleDataExportBookmarks(res, requestId) {
    const items = mockGalleries.slice(0, 10).map(g => ({
        gid: g.gid, token: g.token, title: g.title, titleJpn: g.titleJpn,
        thumb: g.thumb, category: g.category, posted: g.posted,
        uploader: g.uploader, rating: g.rating, pages: g.pages
    }));
    console.log(`[${requestId}] ← 200 data/export/bookmarks: ${items.length} items`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ type: 'bookmarks', exportTime: Date.now(), total: items.length, items }));
}

function handleDataExportFavorites(res, requestId) {
    // Local favorites - only a few items, not all downloads
    const items = mockGalleries.slice(0, 3).map(g => ({
        gid: g.gid, token: g.token, title: g.title, titleJpn: g.titleJpn,
        thumb: g.thumb, favCat: '默认', favNote: ''
    }));
    console.log(`[${requestId}] ← 200 data/export/favorites: ${items.length} items`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
        type: 'favorites', exportTime: Date.now(), total: items.length,
        catNames: ['默认'],
        catCounts: [items.length],
        items
    }));
}

function handleDataExportDownloads(res, requestId) {
    const items = mockGalleries.map(g => ({
        gid: g.gid, token: g.token, title: g.title, titleJpn: g.titleJpn,
        thumb: g.thumb, category: g.category, posted: g.posted,
        uploader: g.uploader, rating: g.rating, pages: g.pages,
        state: g.state, time: g.time, label: g.label
    }));
    console.log(`[${requestId}] ← 200 data/export/downloads: ${items.length} items`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ type: 'downloads', exportTime: Date.now(), total: items.length, items }));
}

function handleDataExportDB(res, requestId) {
    const content = 'SQLite format 3\0mock database content for testing';
    console.log(`[${requestId}] ← 200 data/export/db`);
    res.writeHead(200, {
        'Content-Type': 'application/octet-stream',
        'Content-Disposition': 'attachment; filename="ehviewer_export_20260712.db"',
        'Content-Length': Buffer.byteLength(content)
    });
    res.end(content);
}

function handleDataExportCSV(res, requestId) {
    let csv = 'GID,Token,Title,TitleJpn,Category,Posted,Uploader,Rating,Pages,State,Label,Time\n';
    mockGalleries.slice(0, 20).forEach(g => {
        csv += `${g.gid},${g.token},${g.title},${g.titleJpn},${g.category},${g.posted},${g.uploader},${g.rating},${g.pages},${g.state},${g.label},${g.time}\n`;
    });
    console.log(`[${requestId}] ← 200 data/export/csv`);
    res.writeHead(200, {
        'Content-Type': 'text/csv; charset=utf-8',
        'Content-Disposition': 'attachment; filename="ehviewer-download-20260712.csv"',
        'Content-Length': Buffer.byteLength(csv)
    });
    res.end(csv);
}

function handleDataImport(req, res, type, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }
        const items = data.items || [];
        console.log(`[${requestId}] ← 200 data/import/${type}: ${items.length} items`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, imported: items.length, skipped: 0, failed: 0 }));
    });
}

function handleDataImportDB(req, res, requestId) {
    console.log(`[${requestId}] ← 200 data/import/db`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'Database imported successfully', tables: { downloads: 100, bookmarks: 50, history: 200 } }));
}

function handleDataImportCSV(req, res, requestId) {
    readBody(req, (body) => {
        console.log(`[${requestId}] ← 200 data/import/csv`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, imported: 20, skipped: 0, failed: 0 }));
    });
}

// ==================== Compress Handlers ====================

const mockCompressTasks = [];

function handleCompressCreate(req, res, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }
        const task = {
            taskId: 'compress-' + Date.now(),
            status: 'completed',
            totalGalleries: (data.gids || []).length,
            completedGalleries: (data.gids || []).length,
            progress: 100,
            splitSizeMB: data.splitSizeMB || 1024,
            outputFiles: [
                { name: 'ehviewer_20260712-part1.zip', path: '/compress/ehviewer_20260712-part1.zip', size: 1073741824, sizeFormatted: '1.00 GB' },
                { name: 'ehviewer_20260712-part2.zip', path: '/compress/ehviewer_20260712-part2.zip', size: 524288000, sizeFormatted: '500.00 MB' }
            ],
            createdTime: Date.now(),
            completedTime: Date.now()
        };
        mockCompressTasks.push(task);
        console.log(`[${requestId}] ← 200 compress/create: ${task.taskId}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(task));
    });
}

function handleCompressTasks(res, requestId) {
    console.log(`[${requestId}] ← 200 compress/tasks: ${mockCompressTasks.length} tasks`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ tasks: mockCompressTasks }));
}

function handleCompressTaskStatus(res, taskId, requestId) {
    const task = mockCompressTasks.find(t => t.taskId === taskId);
    if (!task) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Task not found' }));
        return;
    }
    console.log(`[${requestId}] ← 200 compress/tasks/${taskId}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(task));
}

function handleCompressDownload(res, taskId, requestId) {
    const content = 'PK mock zip content for testing';
    console.log(`[${requestId}] ← 200 compress/tasks/${taskId}/download`);
    res.writeHead(200, {
        'Content-Type': 'application/zip',
        'Content-Disposition': 'attachment; filename="ehviewer_20260712-part1.zip"',
        'Content-Length': Buffer.byteLength(content)
    });
    res.end(content);
}

function handleCompressTaskDelete(res, taskId, requestId) {
    const idx = mockCompressTasks.findIndex(t => t.taskId === taskId);
    if (idx >= 0) mockCompressTasks.splice(idx, 1);
    console.log(`[${requestId}] ← 200 compress/tasks/${taskId} (deleted)`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'Task cancelled' }));
}

function handleCompressImport(req, res, requestId) {
    console.log(`[${requestId}] ← 200 compress/import`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
        importId: 'import-' + Date.now(),
        fileName: 'ehviewer_backup.zip',
        fileSize: 1073741824,
        totalGalleries: 3,
        galleries: [
            { folderName: '12345 - Gallery Title', gid: 12345, title: 'Gallery Title', hasMetadata: true, fileCount: 25, totalSize: 52428800, isDuplicate: false },
            { folderName: '67890 - Another Gallery', gid: 67890, title: 'Another Gallery', hasMetadata: true, fileCount: 30, totalSize: 62914560, isDuplicate: true },
            { folderName: 'Unknown Gallery', gid: null, title: 'Unknown Gallery', hasMetadata: false, fileCount: 15, totalSize: 31457280, isDuplicate: false }
        ],
        duplicates: [{ gid: 67890, existingTitle: '67890 - Another Gallery (existing)', newTitle: '67890 - Another Gallery' }]
    }));
}

function handleCompressImportConfirm(req, res, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }
        console.log(`[${requestId}] ← 200 compress/import/confirm`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            success: true, imported: 2, skipped: 1, failed: 0,
            details: [
                { gid: 12345, status: 'imported', message: 'Import success' },
                { gid: 67890, status: 'skipped', message: 'Already exists' },
                { gid: null, status: 'imported', message: 'Imported as local gallery, GID: 999001' }
            ]
        }));
    });
}

function handlePushTaskDataPost(req, res, id, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }
        console.log(`[${requestId}] ← 200 push/tasks/${id}/data (chunk ${data.chunk || 1})`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, received: data.length || 0, totalReceived: data.offset || 0 }));
    });
}

// ==================== Unified Tasks Handlers ====================

function handleUnifiedTasks(req, res, url, requestId) {
    const type = url.searchParams.get('type') || 'all';
    const status = url.searchParams.get('status') || 'all';

    // Merge push and compress tasks
    const allTasks = [];

    // Add push tasks
    mockPushTasks.forEach(t => {
        allTasks.push({
            taskId: t.id,
            type: 'push',
            subType: t.type,
            status: t.status,
            sourceDevice: t.fromDevice,
            progress: t.progress || 0,
            total: t.total || 0,
            completed: t.transferred || 0,
            createdTime: t.createdTime || Date.now(),
            updatedTime: t.createdTime,
            completedTime: null
        });
    });

    // Add compress tasks
    mockCompressTasks.forEach(t => {
        allTasks.push({
            taskId: t.taskId,
            type: 'compress',
            subType: 'gallery',
            status: t.status,
            progress: t.progress,
            total: t.totalGalleries,
            completed: t.completedGalleries,
            createdTime: t.createdTime,
            updatedTime: t.completedTime || t.createdTime,
            completedTime: t.completedTime,
            splitSizeMB: t.splitSizeMB,
            outputFiles: t.outputFiles
        });
    });

    // Add relay tasks
    mockRelayTasks.forEach(t => {
        allTasks.push({
            taskId: t.taskId,
            type: 'relay',
            subType: 'gallery',
            status: t.status,
            sourceDevice: t.sourceDevice,
            targetDevice: t.targetDevice,
            progress: t.progress,
            total: t.total,
            completed: t.finished,
            createdTime: t.createdTime,
            updatedTime: t.updatedTime,
            completedTime: t.completedTime
        });
    });

    // Filter
    let filtered = allTasks;
    if (type !== 'all') {
        filtered = filtered.filter(t => t.type === type);
    }
    if (status !== 'all') {
        filtered = filtered.filter(t => t.status === status);
    }

    // Sort by createdTime desc
    filtered.sort((a, b) => b.createdTime - a.createdTime);

    console.log(`[${requestId}] ← 200 tasks: ${filtered.length} tasks (type=${type}, status=${status})`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ tasks: filtered, total: filtered.length }));
}

function handleUnifiedTaskDetail(res, taskId, requestId) {
    // Search in push tasks
    let task = mockPushTasks.find(t => t.id === taskId);
    if (task) {
        const result = {
            taskId: task.id,
            type: 'push',
            subType: task.type,
            status: task.status,
            sourceDevice: task.fromDevice,
            progress: task.progress || 0,
            total: task.total || 0,
            completed: task.transferred || 0,
            createdTime: task.createdTime || Date.now()
        };
        console.log(`[${requestId}] ← 200 tasks/${taskId}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(result));
        return;
    }

    // Search in compress tasks
    task = mockCompressTasks.find(t => t.taskId === taskId);
    if (task) {
        console.log(`[${requestId}] ← 200 tasks/${taskId}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            taskId: task.taskId,
            type: 'compress',
            subType: 'gallery',
            status: task.status,
            progress: task.progress,
            total: task.totalGalleries,
            completed: task.completedGalleries,
            createdTime: task.createdTime,
            completedTime: task.completedTime,
            splitSizeMB: task.splitSizeMB,
            outputFiles: task.outputFiles
        }));
        return;
    }

    // Search in relay tasks
    task = mockRelayTasks.find(t => t.taskId === taskId);
    if (task) {
        console.log(`[${requestId}] ← 200 tasks/${taskId}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            taskId: task.taskId,
            type: 'relay',
            subType: 'gallery',
            status: task.status,
            sourceDevice: task.sourceDevice,
            targetDevice: task.targetDevice,
            progress: task.progress,
            total: task.total,
            completed: task.finished,
            createdTime: task.createdTime,
            updatedTime: task.updatedTime,
            completedTime: task.completedTime
        }));
        return;
    }

    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: false, error: 'Task not found' }));
}

function handleUnifiedTaskDelete(res, taskId, requestId) {
    // Try push tasks
    const pushIdx = mockPushTasks.findIndex(t => t.id === taskId);
    if (pushIdx >= 0) {
        mockPushTasks[pushIdx].status = 'cancelled';
        console.log(`[${requestId}] ← 200 tasks/${taskId} (cancelled push task)`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, message: 'Task cancelled' }));
        return;
    }

    // Try compress tasks
    const compressIdx = mockCompressTasks.findIndex(t => t.taskId === taskId);
    if (compressIdx >= 0) {
        mockCompressTasks.splice(compressIdx, 1);
        console.log(`[${requestId}] ← 200 tasks/${taskId} (removed compress task)`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, message: 'Task removed' }));
        return;
    }

    // Try relay tasks
    const relayIdx = mockRelayTasks.findIndex(t => t.taskId === taskId);
    if (relayIdx >= 0) {
        mockRelayTasks[relayIdx].status = 'cancelled';
        mockRelayTasks[relayIdx].updatedTime = Date.now();
        console.log(`[${requestId}] ← 200 tasks/${taskId} (cancelled relay task)`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, message: 'Task cancelled' }));
        return;
    }

    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: false, error: 'Task not found' }));
}

// ==================== Favorites Handlers ====================

function handleFavorites(req, res, url, requestId) {
    const page = parseInt(url.searchParams.get('page') || '1');
    const limit = parseInt(url.searchParams.get('limit') || '30');
    const search = url.searchParams.get('search');

    let items = mockFavorites.map(fav => {
        const gallery = mockGalleries.find(g => g.gid === fav.gid);
        if (gallery) {
            return {
                ...gallery,
                favCat: fav.favCat,
                favNote: fav.favNote,
                isFavorited: true
            };
        }
        return { ...fav, state: -1, size: 0, isComplete: false, downloadedPages: 0, isFavorited: true };
    });

    if (search) {
        const s = search.toLowerCase();
        items = items.filter(g =>
            g.title.toLowerCase().includes(s) ||
            (g.titleJpn && g.titleJpn.toLowerCase().includes(s))
        );
    }

    const total = items.length;
    const start = (page - 1) * limit;
    const galleries = items.slice(start, start + limit);

    console.log(`[${requestId}] ← 200 favorites: total=${total}, page=${page}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ total, page, limit, galleries }));
}

function handleFavoritesQuery(req, res, requestId) {
    readBody(req, (body) => {
        let query = {};
        try { query = JSON.parse(body); } catch (e) { }
        const page = query.page || 1;
        const limit = query.limit || 30;
        let items = mockFavorites.map(fav => {
            const gallery = mockGalleries.find(g => g.gid === fav.gid);
            if (gallery) {
                return { ...gallery, favCat: fav.favCat, favNote: fav.favNote, isFavorited: true };
            }
            return { ...fav, state: -1, size: 0, isComplete: false, downloadedPages: 0, isFavorited: true };
        });

        if (query.filter?.category) {
            items = items.filter(g => query.filter.category.includes(g.category));
        }

        const total = items.length;
        const start = (page - 1) * limit;
        const galleries = items.slice(start, start + limit);

        console.log(`[${requestId}] ← 200 QUERY favorites: total=${total}, page=${page}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ total, page, limit, galleries }));
    });
}

// ==================== Downloads Handlers ====================

function handleDownloads(req, res, url, requestId) {
    const page = parseInt(url.searchParams.get('page') || '1');
    const limit = parseInt(url.searchParams.get('limit') || '30');
    const stateFilter = url.searchParams.get('state') || 'all';
    const label = url.searchParams.get('label');
    const search = url.searchParams.get('search');

    let filtered = [...mockDownloads];

    if (stateFilter !== 'all') {
        const states = stateFilter.split(',').map(Number);
        filtered = filtered.filter(d => states.includes(d.state));
    }
    if (label) filtered = filtered.filter(d => d.label === label);
    if (search) {
        const s = search.toLowerCase();
        filtered = filtered.filter(d =>
            d.title.toLowerCase().includes(s) ||
            (d.titleJpn && d.titleJpn.toLowerCase().includes(s)) ||
            (d.uploader && d.uploader.toLowerCase().includes(s))
        );
    }

    const total = filtered.length;
    const start = (page - 1) * limit;
    const downloads = filtered.slice(start, start + limit);

    console.log(`[${requestId}] ← 200 downloads: total=${total}, page=${page}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ total, page, limit, downloads }));
}

function handleDownloadDetail(res, gid, requestId) {
    const download = mockDownloads.find(d => d.gid === gid);
    if (!download) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Download not found' }));
        return;
    }
    console.log(`[${requestId}] ← 200 downloads/${gid}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(download));
}

function handleCreateDownload(req, res, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }

        const existing = mockDownloads.find(d => d.gid === data.gid);
        if (existing) {
            console.log(`[${requestId}] ← 409 downloads (already exists)`);
            res.writeHead(409, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ success: false, error: 'Download already exists', code: 409 }));
            return;
        }

        const startState = data.startImmediately ? 1 : 0;
        const download = {
            gid: data.gid,
            token: data.token || '',
            title: data.title || `Gallery ${data.gid}`,
            titleJpn: data.titleJpn || '',
            thumb: data.thumb || '',
            category: data.category || 2,
            posted: data.posted || '',
            uploader: data.uploader || '',
            rating: data.rating || 0,
            language: data.language || '',
            pages: data.pages || 0,
            state: startState,
            stateName: downloadStateToName(startState),
            label: data.label || '默认',
            time: Date.now(),
            createdTime: Date.now(),
            createdDate: new Date().toLocaleString(),
            finished: 0,
            total: data.pages || 0,
            downloaded: 0,
            speed: 0,
            speedFormatted: '0 B/s',
            remaining: data.pages || 0,
            progress: 0,
            legacy: 0,
            fileSize: 0,
            simpleTags: []
        };
        mockDownloads.push(download);

        console.log(`[${requestId}] ← 200 downloads (created gid=${data.gid})`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, message: 'Download added', gid: data.gid, state: downloadStateToName(startState) }));
    });
}

function handleStartDownload(res, gid, requestId) {
    const download = mockDownloads.find(d => d.gid === gid);
    if (!download) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Download not found' }));
        return;
    }
    if (download.state === 2) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Download is in state downloading, cannot start' }));
        return;
    }
    download.state = 1;
    download.stateName = 'wait';
    console.log(`[${requestId}] ← 200 downloads/${gid}/start`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'Download started', gid, state: 'wait' }));
}

function handlePauseDownload(res, gid, requestId) {
    const download = mockDownloads.find(d => d.gid === gid);
    if (!download) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Download not found' }));
        return;
    }
    download.state = 0;
    download.stateName = 'none';
    download.speed = 0;
    download.speedFormatted = '0 B/s';
    console.log(`[${requestId}] ← 200 downloads/${gid}/pause`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'Download paused', gid, state: 'none' }));
}

function handleDeleteDownload(res, gid, requestId) {
    const idx = mockDownloads.findIndex(d => d.gid === gid);
    if (idx >= 0) mockDownloads.splice(idx, 1);
    console.log(`[${requestId}] ← 200 downloads/${gid} (deleted)`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'Download deleted', gid }));
}

function handleBatchStartDownloads(req, res, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }
        const gids = data.gids || [];
        let count = 0;
        gids.forEach(gid => {
            const d = mockDownloads.find(dl => dl.gid === gid);
            if (d && d.state !== 2) {
                d.state = 1;
                d.stateName = 'wait';
                count++;
            }
        });
        console.log(`[${requestId}] ← 200 downloads/batch/start: ${count}个`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, message: 'Batch start completed', count }));
    });
}

function handleBatchPauseDownloads(req, res, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }
        const gids = data.gids || [];
        let count = 0;
        gids.forEach(gid => {
            const d = mockDownloads.find(dl => dl.gid === gid);
            if (d) {
                d.state = 0;
                d.stateName = 'none';
                d.speed = 0;
                d.speedFormatted = '0 B/s';
                count++;
            }
        });
        console.log(`[${requestId}] ← 200 downloads/batch/pause: ${count}个`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, message: 'Batch pause completed', count }));
    });
}

function handleBatchDeleteDownloads(req, res, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }
        const gids = data.gids || [];
        let count = 0;
        gids.forEach(gid => {
            const idx = mockDownloads.findIndex(d => d.gid === gid);
            if (idx >= 0) { mockDownloads.splice(idx, 1); count++; }
        });
        console.log(`[${requestId}] ← 200 downloads/batch: ${count}个 deleted`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, message: 'Batch delete completed', count }));
    });
}

// ==================== Connect Handlers ====================

function handleConnect(req, res, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }

        const peer = {
            deviceId: data.deviceId || 'unknown-' + Date.now(),
            deviceName: data.deviceName || 'Unknown Device',
            deviceType: data.deviceType || 'pc',
            remoteIp: req.socket.remoteAddress || '127.0.0.1',
            port: data.port || 8080,
            connectedAt: Date.now(),
            lastSeen: Date.now()
        };

        const existing = mockPeers.findIndex(p => p.deviceId === peer.deviceId);
        if (existing >= 0) {
            mockPeers[existing] = peer;
        } else {
            mockPeers.push(peer);
        }

        console.log(`[${requestId}] ← 200 connect: ${peer.deviceId}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            success: true,
            message: 'Connected',
            deviceId: peer.deviceId,
            serverTime: Date.now()
        }));
    });
}

function handleDisconnect(req, res, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }
        const deviceId = data.deviceId;
        const idx = mockPeers.findIndex(p => p.deviceId === deviceId);
        if (idx >= 0) mockPeers.splice(idx, 1);
        console.log(`[${requestId}] ← 200 disconnect: ${deviceId}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, message: 'Disconnected', deviceId }));
    });
}

function handleGetPeers(res, requestId) {
    const now = Date.now();
    const TIMEOUT = 5 * 60 * 1000;
    for (let i = mockPeers.length - 1; i >= 0; i--) {
        if (now - mockPeers[i].lastSeen > TIMEOUT) {
            mockPeers.splice(i, 1);
        }
    }
    console.log(`[${requestId}] ← 200 connect/peers: ${mockPeers.length}个`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ peers: mockPeers, total: mockPeers.length }));
}

// ==================== Relay Handlers ====================

function handleRelayCreate(req, res, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }
        const task = {
            taskId: 'relay-' + Date.now() + '-' + Math.random().toString(36).substr(2, 4),
            gid: data.gid,
            token: data.token || '',
            title: data.title || `Gallery ${data.gid}`,
            titleJpn: data.titleJpn || '',
            thumb: data.thumb || '',
            category: data.category || 0,
            posted: data.posted || '',
            uploader: data.uploader || '',
            rating: data.rating || 0,
            pages: data.pages || 0,
            status: 'pending',
            direction: 'outgoing',
            directionName: '发出的',
            sourceDevice: data.sourceDevice || 'Test PC',
            sourceDeviceId: data.sourceDeviceId || '',
            targetDevice: data.targetDevice || '',
            targetDeviceId: data.targetDeviceId || '',
            priority: data.priority || 'normal',
            autoReturn: data.autoReturn !== false,
            finished: 0,
            total: data.pages || 0,
            speed: 0,
            speedFormatted: '0 B/s',
            downloadedSize: 0,
            downloadedSizeFormatted: '0 B',
            totalSize: 0,
            totalSizeFormatted: '0 B',
            progress: 0,
            createdTime: Date.now(),
            updatedTime: Date.now(),
            completedTime: null,
            returnedTime: null,
            createdDate: new Date().toLocaleString(),
            updatedDate: new Date().toLocaleString()
        };
        mockRelayTasks.push(task);
        console.log(`[${requestId}] ← 200 relay/create: ${task.taskId}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            success: true,
            taskId: task.taskId,
            gid: task.gid,
            status: task.status,
            createdTime: task.createdTime
        }));
    });
}

function handleRelayBatch(req, res, requestId) {
    readBody(req, (body) => {
        let data = {};
        try { data = JSON.parse(body); } catch (e) { }
        const gids = data.gids || [];
        const tasks = gids.map(gid => {
            const gallery = mockGalleries.find(g => g.gid === gid);
            const task = {
                taskId: 'relay-' + Date.now() + '-' + Math.random().toString(36).substr(2, 4),
                gid,
                token: gallery ? gallery.token : '',
                title: gallery ? gallery.title : `Gallery ${gid}`,
                titleJpn: gallery ? gallery.titleJpn : '',
                thumb: gallery ? gallery.thumb : '',
                category: gallery ? gallery.category : 0,
                posted: gallery ? gallery.posted : '',
                uploader: gallery ? gallery.uploader : '',
                rating: gallery ? gallery.rating : 0,
                pages: gallery ? gallery.pages : 0,
                status: 'pending',
                direction: 'outgoing',
                directionName: '发出的',
                sourceDevice: data.sourceDevice || 'Test PC',
                sourceDeviceId: data.sourceDeviceId || '',
                targetDevice: data.targetDevice || '',
                targetDeviceId: data.targetDeviceId || '',
                priority: data.priority || 'normal',
                autoReturn: data.autoReturn !== false,
                finished: 0,
                total: gallery ? gallery.pages : 0,
                speed: 0,
                speedFormatted: '0 B/s',
                downloadedSize: 0,
                downloadedSizeFormatted: '0 B',
                totalSize: gallery ? gallery.fileSize : 0,
                totalSizeFormatted: gallery ? formatSize(gallery.fileSize) : '0 B',
                progress: 0,
                createdTime: Date.now(),
                updatedTime: Date.now(),
                completedTime: null,
                returnedTime: null,
                createdDate: new Date().toLocaleString(),
                updatedDate: new Date().toLocaleString()
            };
            mockRelayTasks.push(task);
            return { taskId: task.taskId, gid, status: 'pending' };
        });
        console.log(`[${requestId}] ← 200 relay/batch: ${tasks.length}个`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, tasks, total: tasks.length }));
    });
}

function handleRelayTasks(req, res, url, requestId) {
    const statusFilter = url.searchParams.get('status') || 'all';
    const direction = url.searchParams.get('direction') || 'all';

    let filtered = [...mockRelayTasks];
    if (statusFilter !== 'all') {
        filtered = filtered.filter(t => t.status === statusFilter);
    }
    if (direction !== 'all') {
        filtered = filtered.filter(t => t.direction === direction);
    }
    filtered.sort((a, b) => b.createdTime - a.createdTime);

    console.log(`[${requestId}] ← 200 relay/tasks: ${filtered.length}个`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ tasks: filtered, total: filtered.length }));
}

function handleRelayStatus(res, taskId, requestId) {
    const task = mockRelayTasks.find(t => t.taskId === taskId);
    if (!task) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Task not found' }));
        return;
    }
    console.log(`[${requestId}] ← 200 relay/${taskId}/status`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(task));
}

function handleRelayAccept(res, taskId, requestId) {
    const task = mockRelayTasks.find(t => t.taskId === taskId);
    if (!task) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Task not found' }));
        return;
    }
    task.status = 'downloading';
    task.updatedTime = Date.now();
    task.updatedDate = new Date().toLocaleString();
    console.log(`[${requestId}] ← 200 relay/${taskId}/accept`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'Task accepted', taskId }));
}

function handleRelayReject(res, taskId, requestId) {
    const task = mockRelayTasks.find(t => t.taskId === taskId);
    if (!task) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Task not found' }));
        return;
    }
    task.status = 'rejected';
    task.updatedTime = Date.now();
    task.updatedDate = new Date().toLocaleString();
    console.log(`[${requestId}] ← 200 relay/${taskId}/reject`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'Task rejected', taskId }));
}

function handleRelayCancel(res, taskId, requestId) {
    const task = mockRelayTasks.find(t => t.taskId === taskId);
    if (!task) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Task not found' }));
        return;
    }
    task.status = 'cancelled';
    task.updatedTime = Date.now();
    task.updatedDate = new Date().toLocaleString();
    console.log(`[${requestId}] ← 200 relay/${taskId}/cancel`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'Task cancelled', taskId }));
}

function handleRelayDownload(res, taskId, requestId) {
    const task = mockRelayTasks.find(t => t.taskId === taskId);
    if (!task) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Task not found' }));
        return;
    }
    if (task.status !== 'returned') {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Task status is not returned' }));
        return;
    }
    const content = `PK mock relay zip content for gallery ${task.gid}`;
    console.log(`[${requestId}] ← 200 relay/${taskId}/download`);
    res.writeHead(200, {
        'Content-Type': 'application/zip',
        'Content-Disposition': `attachment; filename="relay_${task.gid}.zip"`,
        'Content-Length': Buffer.byteLength(content)
    });
    res.end(content);
}

function handleRelayDelete(res, taskId, requestId) {
    const idx = mockRelayTasks.findIndex(t => t.taskId === taskId);
    if (idx >= 0) mockRelayTasks.splice(idx, 1);
    console.log(`[${requestId}] ← 200 relay/${taskId} (deleted)`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'Task deleted', taskId }));
}

// 读取请求体
function readBody(req, callback) {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => callback(body));
}

// 启动服务器
server.listen(PORT, HOST, () => {
    const os = require('os');
    const interfaces = os.networkInterfaces();
    let localIP = '127.0.0.1';
    for (const name of Object.keys(interfaces)) {
        for (const iface of interfaces[name]) {
            if (iface.family === 'IPv4' && !iface.internal) {
                localIP = iface.address;
                break;
            }
        }
    }

    console.log(`
╔══════════════════════════════════════════════════════════════════╗
║         EhViewer Remote Manager API Mock Server                 ║
╠══════════════════════════════════════════════════════════════════╣
║  Local:     http://localhost:${PORT}                                ║
║  Network:   http://${localIP}:${PORT}                               ║
║                                                                  ║
║  Swagger UI: http://localhost:${PORT}/docs                          ║
║  OpenAPI:    http://localhost:${PORT}/openapi.yaml                  ║
║  Debug:      http://localhost:${PORT}/api/v1/debug                  ║
╚══════════════════════════════════════════════════════════════════╝
    `);
});
