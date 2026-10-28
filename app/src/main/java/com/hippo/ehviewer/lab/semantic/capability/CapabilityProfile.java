package com.hippo.ehviewer.lab.semantic.capability;

import com.hippo.ehviewer.lab.semantic.SemanticConfig;

/**
 * 一次平台能力探测的结论快照。
 * 由 {@link CapabilityDetector} 生成，供 {@link com.hippo.ehviewer.lab.semantic.backend.BackendFactory}
 * 决定后端与模型。所有字段不可变，便于在设置界面直接展示。
 */
public final class CapabilityProfile {

    /** 首选 ABI，例如 arm64-v8a。 */
    public final String abi;
    /** 是否 64 位 ARM（可运行端侧 llama.cpp）。 */
    public final boolean arm64;
    /** 物理内存总量。 */
    public final long totalRamBytes;
    /** 当前可用内存。 */
    public final long availableRamBytes;
    /** CPU 是否支持 dotprod（NEON 加速点积）。 */
    public final boolean hasDotprod;
    /** CPU 是否支持 i8mm（llama.cpp 在 Android 上的 int8 快速路径要求）。 */
    public final boolean hasI8mm;
    /** Vulkan 驱动版本，0 表示不可用。 */
    public final int vulkanVersion;
    /** GPU 家族：adreno / mali / other / none。 */
    public final String gpuFamily;
    /** GPU 型号描述（尽力而为，可能为空）。 */
    public final String gpuName;
    /** data 分区剩余空间。 */
    public final long freeStorageBytes;

    public CapabilityProfile(String abi, boolean arm64, long totalRamBytes, long availableRamBytes,
                             boolean hasDotprod, boolean hasI8mm, int vulkanVersion,
                             String gpuFamily, String gpuName, long freeStorageBytes) {
        this.abi = abi;
        this.arm64 = arm64;
        this.totalRamBytes = totalRamBytes;
        this.availableRamBytes = availableRamBytes;
        this.hasDotprod = hasDotprod;
        this.hasI8mm = hasI8mm;
        this.vulkanVersion = vulkanVersion;
        this.gpuFamily = gpuFamily;
        this.gpuName = gpuName;
        this.freeStorageBytes = freeStorageBytes;
    }

    /** 能力档位。 */
    public String tier() {
        if (!arm64) {
            // 模拟器与 32 位设备无法运行端侧模型，只能走远程
            return SemanticConfig.TIER_REMOTE;
        }
        if (totalRamBytes >= SemanticConfig.RAM_10GB) {
            return SemanticConfig.TIER_ENHANCED;
        }
        if (totalRamBytes >= SemanticConfig.RAM_4GB) {
            return SemanticConfig.TIER_STANDARD;
        }
        return SemanticConfig.TIER_REMOTE;
    }

    /** 是否具备端侧运行能力（不考虑用户手动关闭）。 */
    public boolean nativeCapable() {
        return arm64 && totalRamBytes >= SemanticConfig.RAM_4GB;
    }

    /** 是否允许使用多模态模型（增强档 + 存储充足）。 */
    public boolean multimodalCapable() {
        return tier().equals(SemanticConfig.TIER_ENHANCED) && freeStorageBytes >= SemanticConfig.MIN_FREE_STORAGE;
    }

    /** Vulkan 是否可用且属于已知良好实现（Adreno）。 */
    public boolean gpuAccelAvailable() {
        return vulkanVersion > 0 && "adreno".equals(gpuFamily);
    }

    /** 推荐的后端模式。 */
    public String recommendedBackend() {
        if (!nativeCapable()) {
            return SemanticConfig.BACKEND_REMOTE;
        }
        if (gpuAccelAvailable()) {
            return SemanticConfig.BACKEND_NATIVE_GPU;
        }
        return SemanticConfig.BACKEND_NATIVE_CPU;
    }

    /** 推荐向量维度：低内存设备降维以节省索引存储。 */
    public int recommendedDim() {
        String tier = tier();
        if (SemanticConfig.TIER_ENHANCED.equals(tier)) {
            return SemanticConfig.DIM_256;
        }
        if (SemanticConfig.TIER_STANDARD.equals(tier)) {
            return totalRamBytes >= 6L * 1024 * 1024 * 1024
                    ? SemanticConfig.DIM_256 : SemanticConfig.DIM_128;
        }
        return SemanticConfig.DIM_256;
    }

    /** 估算端侧推理的内存开销（权重 + KV cache + 计算缓冲）。 */
    public long estimatedNativeMemory(long modelSizeBytes) {
        return (long) (modelSizeBytes * 2.2f);
    }

    /** 供实验室设置页展示的中文能力报告。 */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("ABI：").append(abi).append('\n');
        sb.append("内存：").append(toGb(totalRamBytes)).append(" GB（可用 ")
                .append(toGb(availableRamBytes)).append(" GB）\n");
        sb.append("CPU：dotprod ").append(hasDotprod ? "✓" : "✗")
                .append(" / i8mm ").append(hasI8mm ? "✓" : "✗").append('\n');
        if (vulkanVersion > 0) {
            sb.append("GPU：").append(gpuFamily).append(' ')
                    .append(gpuName == null ? "" : gpuName)
                    .append("（Vulkan ").append(vulkanVersion).append("）")
                    .append(gpuAccelAvailable() ? " ✓ 可加速批量索引" : "（CPU 为主）")
                    .append('\n');
        } else {
            sb.append("GPU：无 Vulkan，端侧仅走 CPU\n");
        }
        sb.append("存储剩余：").append(toGb(freeStorageBytes)).append(" GB\n");
        sb.append("判定档位：").append(tier()).append(" → 推荐后端：")
                .append(recommendedBackend()).append("，维度 ").append(recommendedDim());
        return sb.toString();
    }

    private static String toGb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f", bytes / (1024f * 1024f * 1024f));
    }
}