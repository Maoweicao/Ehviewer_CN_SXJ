package com.hippo.ehviewer.lab.semantic.capability;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import com.hippo.ehviewer.lab.semantic.SemanticConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Locale;

/**
 * 运行时平台能力探测：决定语义搜索走哪一档后端。
 *
 * <p>探测项包括 ABI、内存档位、CPU 向量指令集、Vulkan 能力、GPU 家族与可用存储。
 * 结果带短时缓存，避免在搜索等热路径上反复读取系统属性。
 */
public final class CapabilityDetector {

    private static final String TAG = "SemanticCapability";

    /** {@code PackageManager.FEATURE_VULKAN_HARDWARE_VERSION} 的字面量，避免 API 级别编译依赖。 */
    private static final String VULKAN_VERSION_FEATURE = "android.hardware.vulkan.version";

    private static final long CACHE_TTL_MS = 30_000L;

    private static volatile CapabilityProfile sCached;
    private static volatile long sCachedAt;

    private CapabilityDetector() {
    }

    /** 获取能力快照（带缓存）。 */
    public static CapabilityProfile get(Context context) {
        CapabilityProfile cached = sCached;
        long now = System.currentTimeMillis();
        if (cached != null && now - sCachedAt < CACHE_TTL_MS) {
            return cached;
        }
        CapabilityProfile profile = detect(context.getApplicationContext());
        sCached = profile;
        sCachedAt = now;
        Log.i(TAG, "capability: " + profile.report().replace('\n', ' '));
        return profile;
    }

    /** 强制重新探测（设置页手动刷新时调用）。 */
    public static CapabilityProfile refresh(Context context) {
        sCached = null;
        sCachedAt = 0L;
        return get(context);
    }

    private static CapabilityProfile detect(Context app) {
        boolean arm64 = isArm64();
        String abi = pickAbi();
        long totalRam = totalRam(app);
        long availRam = availableRam(app);
        boolean[] simd = cpuSimdFeatures();
        int vulkanVersion = vulkanVersion(app);
        GpuInfo gpu = gpuInfo(vulkanVersion);
        long freeStorage = freeStorage();

        return new CapabilityProfile(abi, arm64, totalRam, availRam,
                simd[0], simd[1], vulkanVersion, gpu.family, gpu.name, freeStorage);
    }

    private static boolean isArm64() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null || abis.length == 0) {
            return false;
        }
        for (String abi : abis) {
            if (abi != null && abi.startsWith("arm64")) {
                return true;
            }
        }
        return false;
    }

    private static String pickAbi() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null || abis.length == 0) {
            return Build.CPU_ABI != null ? Build.CPU_ABI : "unknown";
        }
        // 优先 arm64（64 位），其次 armeabi-v7a
        for (String abi : abis) {
            if (abi != null && abi.startsWith("arm64")) {
                return abi;
            }
        }
        return abis[0];
    }

    private static long totalRam(Context app) {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return 0L;
            }
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            return info.totalMem;
        } catch (Throwable t) {
            Log.w(TAG, "totalRam failed: " + t);
            return 0L;
        }
    }

    private static long availableRam(Context app) {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return 0L;
            }
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            return info.availMem;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 读取 /proc/cpuinfo 的 Features 行，判断 dotprod / i8mm。
     *
     * @return {hasDotprod, hasI8mm}
     */
    private static boolean[] cpuSimdFeatures() {
        boolean hasDotprod = false;
        boolean hasI8mm = false;
        File cpuInfo = new File("/proc/cpuinfo");
        if (!cpuInfo.canRead()) {
            return new boolean[]{false, false};
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(cpuInfo))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int idx = line.indexOf("Features");
                if (idx < 0) {
                    // 有些机型把特性写在 model name 里（如 Cortex-X2）
                    String lower = line.toLowerCase(Locale.US);
                    if (lower.contains("cortex-x2") || lower.contains("cortex-a710")
                            || lower.contains("cortex-a715")) {
                        hasDotprod = true;
                        hasI8mm = true;
                    }
                    continue;
                }
                String lower = line.toLowerCase(Locale.US);
                hasDotprod = lower.contains("dotprod");
                hasI8mm = lower.contains("i8mm");
                break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "cpuinfo read failed: " + t);
        }
        return new boolean[]{hasDotprod, hasI8mm};
    }

    /**
     * 探测 Vulkan 驱动版本（VK_VERSION_MAJOR * 100 + VK_VERSION_MINOR）。
     *
     * <p>刻意使用反射而非直接调用：{@code getSystemFeatureVersion} 的可用性与
     * compileSdk 绑定，反射能让低版本构建也不会编译失败，探测不到就按“无 Vulkan”处理。
     */
    private static int vulkanVersion(Context app) {
        try {
            PackageManager pm = app.getPackageManager();
            java.lang.reflect.Method hasFeature =
                    PackageManager.class.getMethod("hasSystemFeature", String.class);
            Object supported = hasFeature.invoke(pm, VULKAN_VERSION_FEATURE);
            if (!Boolean.TRUE.equals(supported)) {
                return 0;
            }
            java.lang.reflect.Method getVersion =
                    PackageManager.class.getMethod("getSystemFeatureVersion", String.class);
            Object value = getVersion.invoke(pm, VULKAN_VERSION_FEATURE);
            int raw = value instanceof Integer ? (Integer) value : 0;
            return raw > 1000 ? raw / 100 : raw;
        } catch (Throwable t) {
            Log.w(TAG, "vulkan probe failed: " + t);
            return 0;
        }
    }

    /**
     * 判断 GPU 家族。
     *
     * <p>Android 上拿不到 vendor id 的稳定 API，这里用 SoC 家族做保守推断：
     * 高通平台通常是 Adreno（llama.cpp Vulkan 支持良好），联发科平台是 Mali（支持一般）。
     * 探测结果只影响「是否默认启用 GPU 批量索引」，误判代价可控，用户可手动覆盖。
     */
    private static GpuInfo gpuInfo(int vulkanVersion) {
        if (vulkanVersion <= 0) {
            return new GpuInfo("none", "");
        }
        String hardware = safe(Build.HARDWARE);
        String soc = safe(Build.SOC_MODEL);
        String board = safe(Build.BOARD);
        String blob = (hardware + " " + soc + " " + board).toLowerCase(Locale.US);

        String family;
        if (blob.contains("qcom") || blob.contains("qualcomm") || blob.contains("sdm") || blob.contains("sm8")) {
            family = "adreno";
        } else if (blob.contains("mtk") || blob.contains("mediatek") || blob.contains("dimensity")) {
            family = "mali";
        } else if (blob.contains("exynos") || blob.contains("samsung")) {
            family = "exynos";
        } else {
            family = "other";
        }
        return new GpuInfo(family, soc.isEmpty() ? hardware : soc);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static long freeStorage() {
        try {
            File dir = Environment.getDataDirectory();
            StatFs stat = new StatFs(dir.getAbsolutePath());
            return (long) stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        } catch (Throwable t) {
            Log.w(TAG, "freeStorage failed: " + t);
            return Long.MAX_VALUE;
        }
    }

    /** 仅用于单元测试与日志展示。 */
    public static String describeAbis() {
        return Arrays.toString(Build.SUPPORTED_ABIS);
    }

    private static final class GpuInfo {
        final String family;
        final String name;

        GpuInfo(String family, String name) {
            this.family = family;
            this.name = name;
        }
    }
}