# EhViewer Other Projects

本目录包含 EhViewer 的前后端分离项目。

## 目录结构

```
other/
├── frontend/           # React 前端项目 (ant-design-mobile)
├── test-server/        # API Mock 服务器 (Node.js)
├── pc-server/          # OCR 服务器 (Python)
└── build.js            # 构建 & 部署脚本
```

## 快速开始

### 1. 安装依赖

```bash
# 前端
cd frontend
npm install

# 测试服务器
cd ../test-server
npm install
```

### 2. 启动开发环境

```bash
# 终端1: 启动 API Mock 服务器
cd test-server
npm start

# 终端2: 启动前端开发服务器
cd frontend
npm run dev
```

前端开发服务器运行在 `http://localhost:3000`，API 请求自动代理到 `http://localhost:8080`。

### 3. 构建并部署到 Android

```bash
cd test-server
node build.js
```

此命令会：
1. 构建前端项目 (`npm run build`)
2. 将 `dist/` 复制到 `app/src/main/assets/web/`
3. 复制 OpenAPI 规范到 `assets/web/docs/`

## API 文档

### Swagger UI

启动 test-server 后访问：
- http://localhost:8080/docs - Swagger UI 交互式文档
- http://localhost:8080/openapi.yaml - OpenAPI 3.0 规范文件
- http://localhost:8080/api/v1/debug - API 调试页面

### Android 端

Android 应用启动远程管理服务后访问：
- http://<device-ip>:8080/docs - Swagger UI 文档
- http://<device-ip>:8080/openapi.yaml - OpenAPI 规范

## 技术栈

| 组件 | 技术 |
|------|------|
| 前端 | React 18 + TypeScript + ant-design-mobile + Vite |
| Mock 服务器 | Node.js + NanoHTTPD (原生 http) |
| API 文档 | OpenAPI 3.0 + Swagger UI |
| Android 端 | NanoHTTPD + Java/Kotlin |

## API 端点概览

| 分类 | 端点 | 方法 |
|------|------|------|
| 认证 | `/api/v1/auth/status` | GET |
| 认证 | `/api/v1/auth/login` | POST |
| 认证 | `/api/v1/auth/logout` | POST |
| 画廊 | `/api/v1/galleries` | GET, QUERY |
| 画廊 | `/api/v1/galleries/{gid}` | GET, DELETE |
| 画廊 | `/api/v1/galleries/{gid}/thumbnail` | GET |
| 画廊 | `/api/v1/galleries/{gid}/pages` | GET |
| 画廊 | `/api/v1/galleries/{gid}/pages/{page}` | GET |
| 标签 | `/api/v1/labels` | GET |
| 标签 | `/api/v1/labels/{label}/galleries` | GET |
| 文件 | `/api/v1/folders` | GET |
| 文件 | `/api/v1/folders/{folder}/files` | GET |
| 文件 | `/api/v1/folders/{folder}/files/{filename}` | GET, DELETE |
| 文件 | `/api/v1/folders/{folder}/files/{filename}/preview` | GET |
| 系统 | `/api/v1/system/info` | GET |
| 系统 | `/api/v1/system/stats` | GET |
| 推送 | `/api/v1/push/create` | POST |
| 推送 | `/api/v1/push/tasks` | GET |
| 设置 | `/api/v1/settings/receive` | GET, PUT |
| 设备 | `/api/v1/device/info` | GET |
| 文档 | `/docs` | GET |
| 文档 | `/openapi.yaml` | GET |
