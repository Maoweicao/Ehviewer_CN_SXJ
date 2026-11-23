package com.hippo.ehviewer.spider

import android.util.Log
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.unifile.UniFile

/**
 * 单个画廊下载目录的完整性判定结果。
 *
 * 这里的「完整」沿用下载管理器的既有语义：每个期望页都存在对应的图片文件，
 * 且文件是普通文件、长度大于 0。不做解码或哈希校验。
 */
data class GalleryIntegrityResult(
    val expectedPages: Int,
    val validPages: Int,
    /** [SpiderDen.findImageFile] 找不到对应页。 */
    val missingIndices: List<Int>,
    /** 文件存在且是普通文件，但长度为 0。 */
    val emptyIndices: List<Int>,
    /** 文件存在但不是普通文件。 */
    val nonFileIndices: List<Int>,
    /** 下载目录不存在或不是目录。 */
    val dirMissing: Boolean,
    /** 目录下没有可用的 .ehviewer（页数回退到 [DownloadInfo.getPages]）。 */
    val spiderInfoMissing: Boolean,
) {

    /** 所有不可用的页索引，升序。 */
    val invalidIndices: List<Int>
        get() = (missingIndices + emptyIndices + nonFileIndices).sorted()

    val isComplete: Boolean
        get() = !dirMissing && expectedPages > 0 && invalidIndices.isEmpty()

    /** 与 [DownloadInfo.legacy] 语义一致：完成时为 0，否则为缺失页数量。 */
    val legacy: Int
        get() = if (isComplete) 0 else (expectedPages - validPages).coerceAtLeast(0)
}

/** 单页判定状态，仅用于把扫描结果汇总成 [GalleryIntegrityResult]，便于单测。 */
enum class PageStatus {
    MISSING,
    EMPTY,
    NON_FILE,
    VALID,
}

/**
 * 下载完整性检查的唯一实现。
 *
 * 内置下载引擎完成时（[com.hippo.ehviewer.download.DownloadManager]）、
 * 手动校验任务（[com.hippo.ehviewer.task.VerifyDownloadIntegrityTask]）以及
 * 委托系统下载后的完成回调都调用这里，避免三处语义漂移。
 *
 * 本类只做文件系统读取，不注册后台任务，也不操作下载状态。
 *
 * 性能约定（大库校验的主要成本就在这里）：
 *  - 每个画廊只做一次 `listFiles()`，再在内存里按第 1..N 页找文件名；
 *    逐页 `findFile()` 在 SAF 上是每页 × 扩展名个 binder 查询，16k 画廊会很慢。
 *  - 只读取 `.ehviewer` 头部（[SpiderInfo.readHeader]），不解析 pTokenMap。
 *  - 只对确实存在的候选文件做 `isFile()` / `length()`。
 */
object GalleryIntegrityChecker {

    private const val TAG = "GalleryIntegrity"

    /**
     * 单次 `listFiles()` 结果的上限。超过后退回逐页查询，
     * 避免异常大的目录把整张表一下读进内存。
     */
    private const val MAX_LISTED_CHILDREN = 5000

    /**
     * 自动检查：期望页数优先取目录内 .ehviewer 的 pages，读取失败时回退 [DownloadInfo.pages]。
     */
    @JvmStatic
    fun check(info: DownloadInfo): GalleryIntegrityResult =
        checkIn(resolveDir(info), info, 0)

    /**
     * 指定期望页数的检查。
     *
     * 系统下载在入队时已用 [DownloadInfo.pages] 确定页数，这里显式传入，
     * 避免与磁盘上的 .ehviewer 版本不一致时产生误判。
     */
    @JvmStatic
    fun check(info: DownloadInfo, expectedPages: Int): GalleryIntegrityResult =
        checkIn(resolveDir(info), info, expectedPages)

    /**
     * 使用调用方已解析好的目录做检查，避免重复解析目录（每次解析都会读写一次目录名）。
     *
     * @param expectedPages 大于 0 时作为期望页数（系统委托下载路径）；
     *        小于等于 0 时按 .ehviewer 头部 → [DownloadInfo.pages] → [DownloadInfo.total] 推导。
     */
    @JvmStatic
    @JvmOverloads
    fun checkIn(
        dir: UniFile?,
        info: DownloadInfo,
        expectedPages: Int = 0,
    ): GalleryIntegrityResult {
        val explicitPages = expectedPages > 0
        if (dir == null || !dir.isDirectory) {
            return summarize(
                expectedPages = if (explicitPages) expectedPages else fallbackPages(info),
                statuses = emptyList(),
                dirMissing = true,
                spiderInfoMissing = true,
            )
        }

        val header = readSpiderInfoHeader(dir)
        val spiderInfoMissing = header == null
        if (!explicitPages && spiderInfoMissing) {
            // 期望页数只能来自 .ehviewer，头部又不可用：调用方只会据此重置状态，
            // 继续按回退页数逐页扫描纯属浪费（旧实现同样在这里直接返回）。
            return summarize(
                expectedPages = fallbackPages(info),
                statuses = emptyList(),
                spiderInfoMissing = true,
            )
        }

        val pages = if (explicitPages) expectedPages else header!!.pages
        return summarize(pages, scanStatuses(dir, pages), spiderInfoMissing = spiderInfoMissing)
    }

    /**
     * 只删除 0 字节文件（原 cleanupEmptyFiles 语义），返回删除数量。
     */
    @JvmStatic
    fun deleteEmptyFiles(info: DownloadInfo, result: GalleryIntegrityResult): Int =
        deleteFiles(resolveDir(info), result.emptyIndices)

    /** 删除所有存在但不可用的文件（原手动校验任务语义），返回删除数量。 */
    @JvmStatic
    fun deleteInvalidFiles(info: DownloadInfo, result: GalleryIntegrityResult): Int =
        deleteFiles(resolveDir(info), result.emptyIndices + result.nonFileIndices)

    @JvmStatic
    fun deleteEmptyFiles(dir: UniFile?, result: GalleryIntegrityResult): Int =
        deleteFiles(dir, result.emptyIndices)

    @JvmStatic
    fun deleteInvalidFiles(dir: UniFile?, result: GalleryIntegrityResult): Int =
        deleteFiles(dir, result.emptyIndices + result.nonFileIndices)

    /**
     * 把逐页状态汇总为结果。纯函数，方便单测。
     */
    fun summarize(
        expectedPages: Int,
        statuses: List<PageStatus>,
        dirMissing: Boolean = false,
        spiderInfoMissing: Boolean = false,
    ): GalleryIntegrityResult {
        val pages = expectedPages.coerceAtLeast(0)
        val missing = ArrayList<Int>()
        val empty = ArrayList<Int>()
        val nonFile = ArrayList<Int>()
        var valid = 0
        for (index in 0 until pages) {
            when (statuses.getOrElse(index) { PageStatus.MISSING }) {
                PageStatus.MISSING -> missing.add(index)
                PageStatus.EMPTY -> empty.add(index)
                PageStatus.NON_FILE -> nonFile.add(index)
                PageStatus.VALID -> valid++
            }
        }
        return GalleryIntegrityResult(
            expectedPages = pages,
            validPages = valid,
            missingIndices = missing,
            emptyIndices = empty,
            nonFileIndices = nonFile,
            dirMissing = dirMissing,
            spiderInfoMissing = spiderInfoMissing,
        )
    }

    private fun resolveDir(info: DownloadInfo): UniFile? =
        try {
            SpiderDen.getExistingGalleryDownloadDir(info)
        } catch (e: Exception) {
            Log.w(TAG, "resolveDownloadDir failed for gid=${info.gid}", e)
            null
        }

    private fun fallbackPages(info: DownloadInfo): Int =
        if (info.pages > 0) info.pages else info.total

    private fun readSpiderInfoHeader(dir: UniFile): SpiderInfo? =
        try {
            val file = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME)
            if (file != null) SpiderInfo.readHeader(file) else null
        } catch (e: Exception) {
            Log.w(TAG, "readSpiderInfoHeader failed", e)
            null
        }

    private fun scanStatuses(dir: UniFile, expectedPages: Int): List<PageStatus> {
        if (expectedPages <= 0) return emptyList()
        // 一次列目录，之后全部在内存里判断；listFiles() 不可用时才逐页查询。
        val children = listChildrenByName(dir)
        val statuses = ArrayList<PageStatus>(expectedPages)
        for (index in 0 until expectedPages) {
            statuses.add(
                if (children != null) statusOf(children, index) else readPageStatus(dir, index)
            )
        }
        return statuses
    }

    private fun listChildrenByName(dir: UniFile): Map<String, UniFile>? =
        try {
            val files = dir.listFiles()
            if (files == null || files.size > MAX_LISTED_CHILDREN) {
                null
            } else {
                val map = HashMap<String, UniFile>(files.size * 2)
                for (file in files) {
                    val name = file.name ?: continue
                    if (!map.containsKey(name)) {
                        map[name] = file
                    }
                }
                map
            }
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "listFiles failed for ${dir.name}", e)
            null
        }

    private fun statusOf(children: Map<String, UniFile>, index: Int): PageStatus {
        for (extension in GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            val file = children[SpiderDen.generateImageFilename(index, extension)] ?: continue
            return classifyPage(file, index)
        }
        return PageStatus.MISSING
    }

    private fun readPageStatus(dir: UniFile, index: Int): PageStatus =
        try {
            val file = SpiderDen.findImageFile(dir, index)
            if (file == null) PageStatus.MISSING else classifyPage(file, index)
        } catch (e: Exception) {
            Log.w(TAG, "readPageStatus failed at index=$index", e)
            PageStatus.MISSING
        }

    private fun classifyPage(file: UniFile, index: Int): PageStatus =
        try {
            when {
                !file.isFile -> PageStatus.NON_FILE
                file.length() <= 0L -> PageStatus.EMPTY
                else -> PageStatus.VALID
            }
        } catch (e: Exception) {
            Log.w(TAG, "classify page failed at index=$index", e)
            PageStatus.MISSING
        }

    private fun deleteFiles(dir: UniFile?, indices: List<Int>): Int {
        if (dir == null || indices.isEmpty()) return 0
        var deleted = 0
        for (index in indices) {
            try {
                val file = SpiderDen.findImageFile(dir, index)
                if (file != null && file.isFile && file.delete()) {
                    deleted++
                }
            } catch (e: Exception) {
                Log.w(TAG, "delete file failed at index=$index", e)
            }
        }
        if (deleted > 0) {
            Log.i(TAG, "deleted $deleted invalid file(s) in ${dir.name}")
        }
        return deleted
    }
}
