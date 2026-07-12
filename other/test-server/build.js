#!/usr/bin/env node
/**
 * EhViewer Frontend Build & Deploy Script
 * 
 * 用法:
 *   node build.js          - 构建前端并复制到 Android assets
 *   node build.js --dev    - 仅启动开发服务器
 *   node build.js --copy   - 仅复制已构建的 dist 到 Android assets
 */

const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const FRONTEND_DIR = path.join(__dirname, '..', 'frontend');
const ANDROID_WEB_DIR = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'web');
const OPENAPI_SOURCE = path.join(__dirname, 'openapi', 'openapi.yaml');
const DOCS_DIR = path.join(ANDROID_WEB_DIR, 'docs');

function run(cmd, cwd) {
    console.log(`> ${cmd}`);
    execSync(cmd, { cwd, stdio: 'inherit' });
}

function copyDir(src, dest) {
    if (!fs.existsSync(dest)) {
        fs.mkdirSync(dest, { recursive: true });
    }
    const entries = fs.readdirSync(src, { withFileTypes: true });
    for (const entry of entries) {
        const srcPath = path.join(src, entry.name);
        const destPath = path.join(dest, entry.name);
        if (entry.isDirectory()) {
            copyDir(srcPath, destPath);
        } else {
            fs.copyFileSync(srcPath, destPath);
        }
    }
}

function cleanDir(dir) {
    if (fs.existsSync(dir)) {
        fs.rmSync(dir, { recursive: true, force: true });
    }
    fs.mkdirSync(dir, { recursive: true });
}

const args = process.argv.slice(2);

if (args.includes('--dev')) {
    console.log('Starting dev server...');
    run('npm run dev', FRONTEND_DIR);
    process.exit(0);
}

if (!args.includes('--copy')) {
    console.log('=== Building frontend ===');
    run('npm run build', FRONTEND_DIR);
}

console.log('\n=== Copying dist to Android assets ===');
const distDir = path.join(FRONTEND_DIR, 'dist');
if (!fs.existsSync(distDir)) {
    console.error('Error: dist directory not found. Run build first.');
    process.exit(1);
}

// Clean and copy
cleanDir(ANDROID_WEB_DIR);
copyDir(distDir, ANDROID_WEB_DIR);

// Copy docs
if (!fs.existsSync(DOCS_DIR)) {
    fs.mkdirSync(DOCS_DIR, { recursive: true });
}

// Copy OpenAPI spec
fs.copyFileSync(OPENAPI_SOURCE, path.join(DOCS_DIR, 'openapi.yaml'));

// Copy Swagger UI page
const swaggerHtml = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'web', 'docs', 'index.html');
if (!fs.existsSync(swaggerHtml)) {
    // Create default if not exists
    fs.writeFileSync(swaggerHtml, `<!DOCTYPE html>
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
    </style>
</head>
<body>
    <div id="swagger-ui"></div>
    <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
    <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-standalone-preset.js"></script>
    <script>
        SwaggerUIBundle({
            url: './openapi.yaml',
            dom_id: '#swagger-ui',
            deepLinking: true,
            presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
            plugins: [SwaggerUIBundle.plugins.DownloadUrl],
            layout: "StandaloneLayout"
        });
    </script>
</body>
</html>`);
}

console.log('\n=== Build complete ===');
console.log(`Output: ${ANDROID_WEB_DIR}`);
console.log('Files:');
const files = fs.readdirSync(ANDROID_WEB_DIR);
files.forEach(f => {
    const stat = fs.statSync(path.join(ANDROID_WEB_DIR, f));
    if (stat.isDirectory()) {
        console.log(`  ${f}/`);
    } else {
        console.log(`  ${f} (${(stat.size / 1024).toFixed(1)} KB)`);
    }
});
