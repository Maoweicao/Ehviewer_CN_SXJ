# Tesla P4 能否承载 EmbeddingGemma 2 —— 评估结论

> 结论先行：**能跑，而且很合适。** 但只能走 llama.cpp / Ollama 这条路，vLLM 和 TEI 直接排除。

## 1. 硬件底数（按 NVIDIA 官方 datasheet）

| 项目 | Tesla P4 |
|---|---|
| 架构 | Pascal GP104，**Compute Capability 6.1** |
| 显存 | **8 GB GDDR5** |
| 显存带宽 | **192 GB/s** |
| INT8 算力 | 22 TOPS |
| FP32 算力 | 5.5 TFLOPS |
| 功耗 | 50W / 75W |
| 互联 | PCIe，无 NVLink |

对照目标模型（GGUF 量化）：

| 模型 | 体积 | 显存占用（含 KV/缓冲） | 内存够吗 |
|---|---|---|---|
| EmbeddingGemma 2 Text 270M（Q4_K） | ~176 MB | ~0.6 GB | ✅ 绰绰有余 |
| EmbeddingGemma 2 740M 全模态（Q4_K） | ~500 MB | ~1.2 GB | ✅ 够 |

**8GB 显存对嵌入模型完全不是瓶颈**——这类模型天生就是"小模型大吞吐"场景。

## 2. 软件栈可行性（决定性因素是 CC 6.1）

| 方案 | 最低 CC 要求 | P4 (6.1) | 说明 |
|---|---|---|---|
| **Ollama** | ≥ 5.0 | ✅ **可用** | 官方硬件支持表明确列出 CC 6.1（对应 GTX 1080/1080Ti 等） |
| **llama.cpp (CUDA)** | 支持 sm_61 | ✅ **可用** | 需指定 `-DCMAKE_CUDA_ARCHITECTURES=61` 编译 |
| **vLLM** | ≥ 7.0（近期文档已抬到 7.5） | ❌ 不可用 | PagedAttention / FlashAttention 依赖 Volta+ 指令 |
| **HuggingFace TEI** | ≥ 7.0 | ❌ 不可用 | 同上 |
| **TensorRT** | Pascal 已在新版逐步淘汰 | ⚠️ 不推荐 | 与 llama.cpp 相比没有额外收益 |

> ⚠️ 编译注意：**CUDA 13 已经移除 Pascal 支持**。在 P4 上请使用 **CUDA 12.x 工具链**（12.1~12.8 均可），配合 `-DCMAKE_CUDA_ARCHITECTURES=61`。
> 如果驱动过新导致 CUDA 12 也无法初始化，可退回 CPU 模式（`--no-gpu`），270M 模型在多核 CPU 上同样可用。

## 3. 性能预期

嵌入推理的瓶颈是**显存带宽**（192 GB/s，只有现代卡的四分之一），但由于模型只有 176MB：

- 单条查询编码（30~60 token）：**几十到一百多毫秒**，完全够用
- 批量建索引：P4 的优势在于可以常驻并同时服务多台设备。批处理（`n_batch` 拉到 2048）时权重每批只读一次，吞吐可达**数百至数千 token/s**
- 参考量级：5000 条画廊（~75 万 token）首次建索引 **约 10~40 分钟**，之后是增量

相比手机 CPU 的实际意义是：**把建索引这种重活从手机挪到服务器**，手机只做查询编码（几十毫秒），体验更好，也不占手机电和热。

## 4. 推荐部署方式（由简到繁）

### 方案 A：Ollama（最省事，推荐先试）

```bash
# 拉取模型（Ollama 官方已收录 embeddinggemma-2）
ollama pull embeddinggemma-2

# 直接暴露 OpenAI 兼容接口
ollama serve
# → http://<ip>:11434/v1/embeddings
```

App 里填：URL `http://<PC-IP>:11434/v1`，协议选 **OpenAI 兼容**。

### 方案 B：仓库内置的 embed_server.py（可控性最好）

```bash
pip install -r other/pc-server/requirements_embed.txt

# P4 上全量卸载到 GPU
python other/pc-server/embed_server.py \
    --model embeddinggemma-2-UD-Q4_K_XL.gguf \
    --gpu-layers 99 --ctx-size 2048 --port 5002
```

App 里填：URL `http://<PC-IP>:5002/v1`，协议选 **OpenAI 兼容**。
（llama-cpp-python 装 CUDA 版：`CMAKE_ARGS="-DGGML_CUDA=on -DCMAKE_CUDA_ARCHITECTURES=61" pip install llama-cpp-python --no-binary llama-cpp-python`）

### 方案 C：复用已有大显存服务（Proxy）

如果已有 vLLM / Ollama / llama.cpp server 在跑，本仓库脚本可以直接反代，不用改 App：

```bash
python other/pc-server/embed_server.py --proxy http://127.0.0.1:8000/v1 --port 5002
```

## 5. 与手机端方案的分工（推荐架构）

```
       ┌──────────────── 手机（K50 Pro / Y700）────────────────┐
       │  端侧后端 native_cpu / native_gpu                      │
       │  · 查询编码：50~150ms                                  │
       │  · 小库（<2000 条）直接本地索引即可，不必联网            │
       └───────────────┬────────────────────────────────────────┘
                       │ 仅在「远程档」或「批量建索引」时联网
                       ▼
       ┌──────────────── PC / 服务器（可无 GPU）────────────────┐
       │  embed_server.py  →  EmbeddingGemma 2 (GGUF Q4)         │
       │  · 大库首次索引在这里跑，手机只发请求                   │
       │  · Tesla P4 完全够用（8GB 显存，CC 6.1）              │
       └────────────────────────────────────────────────────────┘
```

**结论**：如果你手上已经有 P4（或想用极低功耗服务器常年挂着），它完全能承担"远程 GPU 嵌入服务"这个角色，且功耗仅 75W。但请注意它**不能**用 vLLM/TEI，只能 Ollama 或 llama.cpp——这一点在部署脚本里已经按 Ollama 协议和 OpenAI 协议两种方式支持好了。