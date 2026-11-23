package com.hippo.ehviewer.task

import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.unifile.UniFile

/**
 * 恢复流程的公共动作：目录统计、目录名解析、以及“先预约映射再入队”的提交。
 *
 * 两个恢复任务共用，保证映射写入规则一致：只有真正进入下载队列才保留映射，
 * 且绝不覆盖别的 gid 已占用的目录。
 */
object RestoreSupport {

    private val IMAGE_EXTENSIONS = setOf(
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".bmp"
    )

    fun countImageFiles(dir: UniFile?): Int {
        if (dir == null) return 0
        return try {
            dir.listFiles()?.count { file ->
                file.isFile && file.name?.let { name ->
                    IMAGE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
                } == true
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /** 目录名以 gid 开头（`gid-title`） */
    fun parseGidFromDirName(dirName: String): Long? {
        var index = 0
        while (index < dirName.length && dirName[index].isDigit()) {
            index++
        }
        if (index == 0) return null
        return try {
            dirName.substring(0, index).toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * 提交一个恢复项：
     * 1. 下载队列里已有该 gid → 跳过；
     * 2. 预约目录映射（目录被别的 gid 占用 / 该 gid 已映射到别处 → 跳过）；
     * 3. addDownload；
     * 4. 若最终没进入队列，回收本次写入的映射。
     *
     * @return 是否成功进入下载队列
     */
    fun commit(
        downloadManager: DownloadManager,
        info: GalleryInfo,
        candidate: RestoreCandidate,
        log: (String) -> Unit
    ): Boolean {
        val title = info.title ?: candidate.dirname
        if (downloadManager.containDownloadInfo(info.gid)) {
            log("跳过 $title：已被其他流程加入下载队列")
            return false
        }

        val hasDirname = candidate.dirname.isNotEmpty()
        var claim = EhDB.DIRNAME_CLAIM_SAME
        if (hasDirname) {
            claim = EhDB.claimDownloadDirname(
                info.gid, candidate.dirname, candidate.replaceMapping
            )
            if (claim == EhDB.DIRNAME_CLAIM_REJECTED_OTHER_GID ||
                claim == EhDB.DIRNAME_CLAIM_REJECTED_GID_ELSEWHERE
            ) {
                log("跳过 $title：目录 ${candidate.dirname} 已被占用")
                return false
            }
        }

        return try {
            downloadManager.addDownload(info, null)
            if (!downloadManager.containDownloadInfo(info.gid)) {
                if (hasDirname && claim == EhDB.DIRNAME_CLAIM_UPDATED) {
                    EhDB.removeDownloadDirname(info.gid)
                }
                log("添加失败: $title (GID: ${info.gid})：未进入下载队列")
                false
            } else {
                // 补上磁盘上实际已有的页数/完成度：addDownload 只按 GalleryInfo 建记录，
                // 会把 pages 之外的状态留成初始值，列表就会显示 0/0。
                applyRestoredProgress(downloadManager, info, candidate, log)
                true
            }
        } catch (e: Exception) {
            if (hasDirname && claim == EhDB.DIRNAME_CLAIM_UPDATED) {
                EhDB.removeDownloadDirname(info.gid)
            }
            log("添加失败: $title (GID: ${info.gid}): ${e.message}")
            false
        }
    }

    /** 把候选里已知的页数/已有文件数写回刚创建的下载记录。 */
    private fun applyRestoredProgress(
        downloadManager: DownloadManager,
        info: GalleryInfo,
        candidate: RestoreCandidate,
        log: (String) -> Unit
    ) {
        val added = downloadManager.getDownloadInfo(info.gid) ?: return
        val pages = if (candidate.pages > 0) candidate.pages else added.pages
        val finished = if (candidate.fileCount > 0) {
            if (pages > 0) minOf(candidate.fileCount, pages) else candidate.fileCount
        } else {
            added.finished
        }
        if (added.pages == pages && added.finished == finished) return
        added.pages = pages
        added.finished = finished
        if (pages > 0 && finished >= pages && added.state == DownloadInfo.STATE_NONE) {
            added.state = DownloadInfo.STATE_FINISH
        }
        try {
            EhDB.putDownloadInfo(added)
        } catch (e: Exception) {
            log("写入恢复进度失败: ${info.gid} - ${e.message}")
        }
    }
}
