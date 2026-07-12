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
        console.log(`[${requestId}] ← 200 batch delete ${folder}: ${data.files?.length}个`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, deleted: data.files?.length || 0, failed: 0 }));
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
        syncDownloadEnabled: false
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
