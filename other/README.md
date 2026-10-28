# EhViewer Other Projects

本目录包含 EhViewer 的前后端分离项目。

## 目录结构

```
other/
├── frontend/           # React 前端项目 (ant-design-mobile)
├── test-server/        # API Mock 服务器 (Node.js)
├── pc-server/          # OCR 服务器 + 嵌入向量服务器 (Python)
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

## 语义搜索远程服务（Embedding Server）

实验室功能「语义搜索」的**远程后端**。把嵌入模型（默认 EmbeddingGemma 2）跑在 PC / GPU 服务器上，
手机只发请求，适合大库首次建索引这类重活，也兼容老设备与模拟器。

### 快速开始

```bash
cd pc-server
pip install -r requirements_embed.txt

# 方式 A：自建服务（llama.cpp / llama-cpp-python）
python embed_server.py --model embeddinggemma-2-UD-Q4_K_XL.gguf --gpu-layers 99

# 方式 B：复用已有 OpenAI 兼容服务（vLLM / Ollama / llama.cpp server）
python embed_server.py --proxy http://127.0.0.1:8000/v1

# Windows 双击：start_embed.bat
```

### 在 App 里配置

`设置 → 实验室 → 语义搜索 → 远程后端`

| 项 | 值 |
|----|-----|
| 接口地址 | `http://<PC-IP>:5002/v1` |
| 协议 | OpenAI 兼容（也支持 Ollama `/api/embed`、TEI `/embed`） |
| 模型名 | 服务端实际使用的模型名 |
| API Key | 本地服务留空 |

配置完点「测试远程连接」发一次真实请求验证。

### 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/health` | GET | 健康检查 |
| `/model/status` | GET | 模型与后端信息 |
| `/v1/embeddings` | POST | OpenAI 兼容，支持批量与 `dimensions` 截断 |
| `/embed` | POST | TEI 兼容格式 |

### 注意事项

- **不要用 FP16**：EmbeddingGemma 2 在 FP16 下可能产出 NaN，请用 Q4_K / Q8 / BF16。
- 任务前缀（`task: search result | query: ...`）由 App 侧添加，服务端不重复添加，否则两侧前缀错位会导致召回崩塌。
- 老显卡（Tesla P4，CC 6.1）可以用 llama.cpp 或 Ollama，但**不能用 vLLM / TEI**（要求 CC ≥ 7.0）。
  详见 [docs/embeddinggemma2-tesla-p4.md](../../docs/embeddinggemma2-tesla-p4.md)。
