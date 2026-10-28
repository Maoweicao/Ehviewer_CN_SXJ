"""
Embedding Server for EhViewer Android App (remote GPU backend)
============================================================
Runs an embedding model (default: EmbeddingGemma 2) on a PC / GPU box and
exposes an OpenAI-compatible endpoint that the app's lab "semantic search"
remote backend can talk to directly.

Why this script exists
----------------------
The Android client speaks three protocols (openai / ollama / tei). This server
implements `openai` and `tei`, plus a proxy mode so a stronger engine (vLLM /
Ollama / llama.cpp server) can be reused without changing the app.

Usage
-----
  # 1) Self-contained (llama.cpp via llama-cpp-python), CUDA optional
  python embed_server.py --model embeddinggemma-2-Q4_K_M.gguf

  # 2) Force CPU, keep a core free for the OS
  python embed_server.py --model xxx.gguf --no-gpu --threads 6

  # 3) Proxy an existing OpenAI-compatible endpoint (e.g. vLLM on a big GPU)
  python embed_server.py --proxy http://127.0.0.1:8000/v1

  # 4) Old but supported GPUs (Tesla P4, compute capability 6.1)
  python embed_server.py --model xxx.gguf --gpu-layers 99 --ctx-size 2048

API
---
  GET  /health          -> {"status":"ok","service":"embed"}
  GET  /model/status    -> {"loaded":bool,"model":...,"dim":768,...}
  POST /v1/embeddings   -> OpenAI compatible: {"data":[{"embedding":[...]}]}
  POST /embed           -> TEI compatible: [[...]]
  POST /v1/embeddings   also accepts {"input": [...]} (batch)

Notes
-----
* EmbeddingGemma 2 needs *task prefixes* on both query and document side.
  The Android client already prepends them ("task: search result | query: ..." /
  "title: none | text: ..."). This server intentionally does NOT add its own,
  otherwise the two sides would get double prefixes and recall would collapse.
* Matrioshka truncation: pass {"dimensions": 256} and the server truncates then
  re-normalizes (L2), which is what the model was trained for.
* Do NOT run the model in FP16 -- EmbeddingGemma 2 can emit NaN. Use Q4_K/Q8/BF16.
"""

import argparse
import json
import logging
import os
import sys
import threading
import time

try:
    from flask import Flask, request, jsonify
except ImportError:
    print("[ERROR] flask is required:  pip install flask")
    sys.exit(1)

app = Flask(__name__)
logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(LEVELNAME)s] %(message)s')
logger = logging.getLogger(__name__)

_lock = threading.Lock()
_llm = None
_model_path = None
_n_ctx = 2048
_gpu_layers = 0
_threads = 4
_proxy_base = None
_api_key = None
_dim_default = 768


# --------------------------------------------------------------------------
# model handling
# --------------------------------------------------------------------------
def load_model():
    """Lazily load the GGUF model (first request pays the warm-up cost)."""
    global _llm
    with _lock:
        if _llm is not None:
            return _llm
        if _proxy_base:
            logger.info("proxy mode: %s (no local model)", _proxy_base)
            return None
        try:
            from llama_cpp import Llama
        except ImportError:
            raise RuntimeError(
                "llama-cpp-python not installed. Either install it "
                "(pip install llama-cpp-python) or use --proxy to point at an "
                "existing OpenAI-compatible server."
            )
        if not _model_path or not os.path.isfile(_model_path):
            raise RuntimeError("model file not found: %s" % _model_path)
        logger.info("loading model: %s (ctx=%d, threads=%d, gpu_layers=%d)",
                    _model_path, _n_ctx, _threads, _gpu_layers)
        t0 = time.time()
        _llm = Llama(
            model_path=_model_path,
            n_ctx=_n_ctx,
            n_threads=_threads,
            n_gpu_layers=_gpu_layers,
            embedding=True,
            verbose=False,
        )
        logger.info("model ready in %.1fs", time.time() - t0)
        return _llm


def _truncate_normalize(vec, dim=None):
    """Matrioshka truncation + L2 renormalization."""
    import math
    if dim and 0 < dim < len(vec):
        vec = vec[:dim]
    norm = math.sqrt(sum(v * v for v in vec))
    if norm <= 1e-12:
        raise ValueError("degenerate embedding (all zeros / NaN)")
    return [v / norm for v in vec]


def _encode(inputs):
    """Encode a list of strings to normalized vectors."""
    if _proxy_base:
        return _proxy_encode(inputs)
    llm = load_model()
    out = []
    for text in inputs:
        # llama-cpp-python exposes embeddings via create_embedding
        resp = llm.create_embedding(input=text)
        vec = resp["data"][0]["embedding"]
        out.append(_truncate_normalize(vec))
    return out


def _proxy_encode(inputs):
    import urllib.request
    payload = json.dumps({"model": "proxy", "input": inputs}).encode("utf-8")
    url = _proxy_base.rstrip("/")
    if not url.endswith("/embeddings"):
        url += "/embeddings"
    req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"})
    if _api_key:
        req.add_header("Authorization", "Bearer " + _api_key)
    with urllib.request.urlopen(req, timeout=120) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    return [_truncate_normalize(item["embedding"]) for item in body.get("data", [])]


def _auth_ok():
    if not _api_key:
        return True
    header = request.headers.get("Authorization", "")
    return header == "Bearer " + _api_key


# --------------------------------------------------------------------------
# endpoints
# --------------------------------------------------------------------------
@app.route("/health")
def health():
    return jsonify({"status": "ok", "service": "embed"})


@app.route("/model/status")
def status():
    return jsonify({
        "loaded": _llm is not None or _proxy_base is not None,
        "model": _model_path or ("proxy:" + _proxy_base if _proxy_base else None),
        "dim": _dim_default,
        "mode": "proxy" if _proxy_base else "local",
        "n_ctx": _n_ctx,
        "gpu_layers": _gpu_layers,
    })


def _handle_embeddings():
    if not _auth_ok():
        return jsonify({"error": "unauthorized"}), 401
    body = request.get_json(force=True, silent=True) or {}
    inputs = body.get("input", body.get("inputs"))
    if isinstance(inputs, str):
        inputs = [inputs]
    if not inputs:
        return jsonify({"error": "missing 'input'"}), 400
    dim = body.get("dimensions")
    try:
        vectors = _encode(inputs)
        if dim:
            vectors = [_truncate_normalize(v, int(dim)) for v in vectors]
    except Exception as exc:  # noqa: BLE001 - surface the real reason to the app
        logger.exception("encode failed")
        return jsonify({"error": str(exc)}), 500
    return jsonify({
        "object": "list",
        "model": body.get("model", "embedding"),
        "data": [{"object": "embedding", "index": i, "embedding": v}
                 for i, v in enumerate(vectors)],
        "usage": {"prompt_tokens": 0, "total_tokens": 0},
    })


@app.route("/v1/embeddings", methods=["POST"])
def openai_embeddings():
    return _handle_embeddings()


@app.route("/embed", methods=["POST"])
def tei_embeddings():
    """TEI-compatible: returns a bare array of vectors."""
    resp = _handle_embeddings()
    if isinstance(resp, tuple):
        return resp
    return jsonify([item["embedding"] for item in resp.get_json()["data"]])


def main():
    global _model_path, _n_ctx, _gpu_layers, _threads, _proxy_base, _api_key, _dim_default

    parser = argparse.ArgumentParser(description="EhViewer embedding server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=5002)
    parser.add_argument("--model", default=os.environ.get("EMBED_MODEL", ""),
                        help="path to the GGUF model")
    parser.add_argument("--proxy", default=os.environ.get("EMBED_PROXY", ""),
                        help="base URL of an existing OpenAI-compatible server")
    parser.add_argument("--ctx-size", type=int, default=2048)
    parser.add_argument("--threads", type=int,
                        default=max(2, (os.cpu_count() or 4) - 1))
    parser.add_argument("--gpu-layers", type=int,
                        default=int(os.environ.get("EMBED_GPU_LAYERS", "0")),
                        help="0 = CPU only; 99 = offload everything (needs CUDA build)")
    parser.add_argument("--no-gpu", action="store_true", help="force CPU")
    parser.add_argument("--api-key", default=os.environ.get("EMBED_API_KEY", ""))
    parser.add_argument("--dim", type=int, default=768)
    args = parser.parse_args()

    _model_path = args.model
    _n_ctx = args.ctx_size
    _gpu_layers = 0 if args.no_gpu else args.gpu_layers
    _threads = args.threads
    _proxy_base = args.proxy or None
    _api_key = args.api_key or None
    _dim_default = args.dim

    if not _proxy_base and not _model_path:
        logger.error("either --model or --proxy is required")
        sys.exit(1)

    logger.info("listening on http://%s:%d", args.host, args.port)
    logger.info("in the app: Settings > Lab > Semantic search > Remote API, "
                "protocol = OpenAI, URL = http://<this-ip>:%d/v1", args.port)
    app.run(host=args.host, port=args.port, threaded=True)


if __name__ == "__main__":
    main()