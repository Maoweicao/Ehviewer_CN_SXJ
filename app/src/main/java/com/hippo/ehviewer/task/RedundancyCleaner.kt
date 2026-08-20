package com.hippo.ehviewer.task

import android.content.Context
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.Settings
import com.hippo.lib.yorozuya.NumberUtils
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 清理结果汇总。
 */
data class CleanSummary(
    val scanned: Int,
    val cleaned: Int,
    val kept: Int,
    val ignored: Int
)

/**
 * 共享的「清除不在下载记录中的本地画廊文件」逻辑。
 *
 * 从 CleanRedundancyTask 中抽取，供以下场景复用：
 * - CleanRedundancyTask（设置页「清理冗余」）
 * - 批量删除后台任务（deleteFiles=true 时，删除数据库记录后清理孤儿目录）
 *
 * 以内存中的下载记录构建已知 gid 集合，再遍历磁盘条目做 O(1) 判断；
 * 进度与日志通过回调节流上报，避免每个条目都触发一次全量 JSON 持久化。
 */
object RedundancyCleaner {

    private const val PROGRESS_THROTTLE = 25

    /**
     * 扫描下载目录，清理不在下载记录中的本地画廊文件/目录（永久删除）。
     *
     * @param onProgress 进度回调：参数为 (scanned, total, cleaned, kept, ignored)
     * @param onLog      日志回调
     * @param isCancelled 是否被取消（抛出 CancellationException 中断）
     * @param isPaused    是否被暂停（阻塞等待恢复）
     */
    suspend fun cleanOrphanedGalleries(
        context: Context,
        onProgress: (scanned: Int, total: Int, cleaned: Int, kept: Int, ignored: Int) -> Unit,
        onLog: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        isPaused: () -> Boolean = { false }
    ): CleanSummary {
        val downloadManager = EhApplication.getDownloadManager(context)
        val downloadDir = Settings.getDownloadLocation()

        if (downloadDir == null || !downloadDir.isDirectory) {
            onLog("下载目录不存在")
            return CleanSummary(0, 0, 0, 0)
        }

        val files = downloadDir.listFiles()
        if (files == null) {
            onLog("无法列出下载目录文件")
            return CleanSummary(0, 0, 0, 0)
        }

        // 一次性构建已知 gid 集合，避免对每个磁盘条目反复查询下载记录
        val knownGids = HashSet<Long>(downloadManager.allDownloadInfoList.size * 2 + 8)
        for (info in downloadManager.allDownloadInfoList) {
            knownGids.add(info.gid)
        }

        val total = files.size
        var scanned = 0
        var cleaned = 0
        var kept = 0
        var ignored = 0
        var lastPercent = -1

        for (file in files) {
            if (isCancelled()) {
                throw CancellationException("任务已取消")
            }
            coroutineContext[Job]?.ensureActive()

            // 暂停检查点：暂停时阻塞等待恢复
            while (isPaused()) {
                delay(100)
            }

            when (classifyAndClean(file, knownGids)) {
                ClassifyResult.DELETED -> {
                    cleaned++
                    scanned++
                    onLog("删除冗余: ${file.name}")
                }
                ClassifyResult.KEPT -> {
                    kept++
                    scanned++
                }
                ClassifyResult.IGNORED -> {
                    ignored++
                    scanned++
                }
            }

            // 节流进度上报
            val percent = if (total > 0) (scanned * 100L / total).toInt() else 100
            if (scanned % PROGRESS_THROTTLE == 0 || percent != lastPercent || scanned == total) {
                onProgress(scanned, total, cleaned, kept, ignored)
                lastPercent = percent
            }
        }

        return CleanSummary(scanned, cleaned, kept, ignored)
    }

    /**
     * 判断并清理单个条目。
     * @return 处理结果：删除 / 保留 / 忽略（非画廊或删除失败）
     */
    private fun classifyAndClean(file: UniFile, knownGids: Set<Long>): ClassifyResult {
        var name = file.name ?: return ClassifyResult.IGNORED

        val index = name.indexOf('-')
        if (index >= 0) {
            name = name.substring(0, index)
        }

        val gid = NumberUtils.parseLongSafely(name, -1L)
        if (gid == -1L) {
            return ClassifyResult.IGNORED
        }

        // 在下载记录中则保留
        if (gid in knownGids) {
            return ClassifyResult.KEPT
        }

        // 不在下载记录中，删除
        return if (file.delete()) {
            ClassifyResult.DELETED
        } else {
            ClassifyResult.IGNORED
        }
    }

    private enum class ClassifyResult {
        DELETED,
        KEPT,
        IGNORED
    }
}
