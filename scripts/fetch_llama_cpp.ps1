# 拉取 llama.cpp 源码用于端侧嵌入推理（实验室功能 · 语义搜索）
#
# 用法（PowerShell）：
#   .\scripts\fetch_llama_cpp.ps1
#   .\scripts\fetch_llama_cpp.ps1 -Ref b6100        # 指定版本，保证可复现
#
# 拉取后重新构建 APK，app/src/main/cpp/semantic/CMakeLists.txt 会自动检测并启用真正的
# 端侧推理；未拉取时构建的是桩库，Java 层自动降级到远程后端。

param(
    [string]$Ref = "master",
    [int]$Depth = 1
)

$ErrorActionPreference = "Stop"

$target = Join-Path $PSScriptRoot "..\app\src\main\cpp\third_party\llama.cpp"
$target = [System.IO.Path]::GetFullPath($target)

Write-Host "==> Target: $target"

if (Test-Path (Join-Path $target "CMakeLists.txt")) {
    Write-Host "[skip] llama.cpp already vendored."
    Write-Host "       Delete the folder first if you want to update."
    exit 0
}

$parent = Split-Path -Parent $target
if (-not (Test-Path $parent)) {
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
}

Write-Host "==> Cloning llama.cpp ($Ref, depth $Depth) ..."
& git clone --depth $Depth --branch $Ref https://github.com/ggml-org/llama.cpp.git $target
if ($LASTEXITCODE -ne 0) {
    throw "git clone failed with exit code $LASTEXITCODE"
}

# 浅克隆无法切到具体 commit 时给个提示
$head = & git -C $target rev-parse --short HEAD
Write-Host "[ok] llama.cpp ready at $target (HEAD $head)"
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. Rebuild the app -> semantic/CMakeLists.txt detects llama.cpp automatically"
Write-Host "  2. Download a GGUF model in Settings > Lab > Semantic search"
Write-Host "  3. Verify EmbeddingGemma 2 support in your llama.cpp version:"
Write-Host "     https://github.com/ggml-org/llama.cpp/pull/30054"
Write-Host "  4. Vulkan is enabled by default; set -DEHVIEWER_SEMANTIC_VULKAN=OFF if it breaks"