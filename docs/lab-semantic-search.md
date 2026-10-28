# 语义搜索（实验室功能）

用嵌入模型（EmbeddingGemma 2）给本地库搜索加上「按语义匹配」的能力：
搜「校园恋爱」能命中标题写 `school romance` / `高校ロマンス` 的条目——
这是原来 `LIKE '%关键词%'` 字面匹配做不到的。

> 状态：P0 + P1 已实现（骨架 / 能力自适应后端 / 本地库语义搜索）。
> P2（标签联想、搜索翻译官）与 P3（以图搜图）尚未实现。

## 入口

`设置 → 实验室 → 语义搜索`，需要**实验室总开关**和**本功能开关**同时打开才生效
（与 AI 翻译 / AI 分析一致的双开关约定）。

关闭时所有搜索入口行为与改动前完全一致，不产生任何额外开销。

## 后端自适应

后端选择遵循降级链，由 `BackendFactory` 在运行时决定：

```
native_gpu → native_cpu → remote → disabled
```

`CapabilityDetector` 探测的维度：

| 探测项 | 方式 | 影响 |
|---|---|---|
| ABI | `Build.SUPPORTED_ABIS` | 非 arm64（模拟器 / 32 位）→ 直接走远程 |
| 内存 | `ActivityManager.MemoryInfo` | ≥10GB → 增强档；4~8GB → 标准档；<4GB → 远程 |
| CPU 指令集 | `/proc/cpuinfo` Features | dotprod / i8mm 缺失时限速提示 |
| Vulkan | `PackageManager` 反射探测 | Adreno → 自动启用 GPU 加速批量索引 |
| GPU 家族 | `Build.HARDWARE` 推断 | 高通→Adreno（支持好），联发科→Mali（CPU 为主） |
| 存储余量 | `StatFs` | 不足 700MB 时禁用多模态模型 |

档位 → 默认配置：

| 档位 | 典型设备 | 后端 | 模型 | 维度 |
|---|---|---|---|---|
| 增强 | K50 Pro 12GB+、拯救者 Y700 | native（Y700 自动 +Vulkan） | 270M / 可选 740M | 256 |
| 标准 | K50 Pro 8GB | native_cpu | 270M | 256 / 128 |
| 远程 | 模拟器、老设备 | remote | 云端 | 256 |

## 模块结构

```
lab/semantic/
├── SemanticConfig.java              # 常量、档位、后端模式取值
├── SemanticSearchManager.java       # 总入口，双开关、懒加载、降级重试
├── capability/
│   ├── CapabilityDetector.java      # 平台能力探测（带 30s 缓存）
│   └── CapabilityProfile.java       # 探测结论 + 决策矩阵 + 中文报告
├── backend/
│   ├── EmbeddingBackend.java        # 后端接口
│   ├── NativeLlamaBackend.java      # 端侧 llama.cpp（JNI，so 缺失时优雅降级）
│   ├── RemoteEmbeddingBackend.java  # 远程：openai / ollama / tei 三协议 + 重试 + 归一化
│   ├── DisabledBackend.java         # 关闭态
│   └── BackendFactory.java          # 降级链构建 + 降级事件通知
├── model/
│   ├── ModelSpec / ModelCatalog     # GGUF 模型清单（含镜像源与体积）
│   ├── ModelManager.java            # 下载（断点续传 + HF→hf-mirror）+ 校验 + 删除
│   └── EmbeddingPrompts.java        # task 前缀（查询/文档必须区分）
├── index/
│   ├── SemanticVectorStore.java     # 独立 SQLite，int8 量化存储
│   ├── VectorMath.java              # MRL 截断 / L2 归一化 / int8 量化 / 余弦
│   ├── DocumentComposer.java        # 标题+上传者+各命名空间标签 → 文档文本
│   └── IndexingPipeline.java        # 后台批量索引（充电策略、可中断续跑）
└── search/SemanticRanker.java       # 关键词 + 语义双路召回与加权融合
```

## 接入的搜索入口

| 入口 | 文件 | 方式 |
|---|---|---|
| 本地收藏搜索 | `EhDB.searchLocalFavorites(String)` | LIKE 结果 + 语义补充召回 |
| 下载库搜索 | `DownloadListInfosExecutor.searchByKeyword()` | 关键词结果 + 语义补充（候选 >5000 时跳过） |

两处都用 `SemanticSearchManager.isEnabled()` 守卫并 `try/catch` 兜底，
推理失败、索引为空、模型缺失都不会影响原有搜索。

## 索引说明

- 独立数据库 `semantic_index.db`，与主 GreenDAO 库隔离，可整库删除而不动主库 schema
- 覆盖**下载列表 + 本地收藏**（按 gid 去重合并）
- int8 对称量化：256 维 ≈ 260 字节/条，2 万条画廊仅约 5MB
- 万级条目全表扫描几毫秒，因此**不引入向量数据库**
- 默认**仅充电时**索引；批间让出 CPU，避免抢占前台
- 换模型时自动清空重建（按 model id 隔离）
- 增量索引：只处理尚未入库的条目

## 端侧推理（native）

`app/src/main/cpp/semantic/` 提供 JNI 层，**默认构建桩模式**：
导出符号但不含 llama.cpp，`nativeProbe()` 返回 false，Java 层自动降级到远程后端。

启用真正的端侧推理：

```powershell
.\scripts\fetch_llama_cpp.ps1        # 拉取 llama.cpp 源码到 cpp/third_party/
# 重新构建，CMake 自动检测并编译 llama.cpp
```

注意事项：
- EmbeddingGemma 2 **不能用 FP16**（会产出 NaN），必须 Q4_K / Q8 / BF16
- Vulkan 默认开启，Mali 上若有问题可 `-DEHVIEWER_SEMANTIC_VULKAN=OFF`
- 视觉编码器在 llama.cpp 侧尚未验证，`supportsImage()` 暂返回 false（P3 再开）

## 远程后端

见 `other/pc-server/embed_server.py` 与 `other/README.md`。
支持 OpenAI 兼容 / Ollama / TEI 三种协议，App 端配置在实验室设置页「远程后端」分区。

服务端**不添加** task 前缀（由 App 统一添加），否则两侧前缀错位会导致召回崩塌。

## 调参

- **语义权重**（`semantic_blend`）：0=纯关键词，0.65=推荐，1=纯语义
- **向量维度**：128 / 256 / 512 / 768，越低索引越小
- **充电时索引**：建议保持开启，避免耗电与发热

## 已知限制

- P2/P3 未实现：标签联想、自然语言→搜索语法、以图搜图
- 视觉编码（以图搜图）在 llama.cpp 侧未验证
- 索引为增量式，不自动感知标题修改（需清空重建）