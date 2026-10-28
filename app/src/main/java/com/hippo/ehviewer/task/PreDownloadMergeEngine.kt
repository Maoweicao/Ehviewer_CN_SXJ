package com.hippo.ehviewer.task

import android.graphics.BitmapFactory
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.unifile.UniFile
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * 「简单画廊合并」的共用文件操作。
 *
 * 从 [PreDownloadMergeTask] 中抽取，供预下载合并与手动「下载中重预检」[DownloadingRecheckTask] 复用，
 * 只负责文件层面的复制/校验/删除，不涉及数据库记录与下载队列（由调用方收尾）。
 */
object PreDownloadMergeEngine {

    /** 一次文件合并的统计结果。 */
    data class FileMergeStats(
        val processed: Int,
        val moved: Int,
        val invalid: Int
    )

    /** 取扩展名（含点），无扩展名时回退 .jpg。 */
    fun extensionOf(name: String?): String {
        if (name.isNullOrEmpty()) return ".jpg"
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot) else ".jpg"
    }

    fun closeQuietly(closeable: AutoCloseable?) {
        if (closeable == null) return
        try {
            closeable.close()
        } catch (_: Exception) {
        }
    }

    fun copyFile(source: UniFile, target: UniFile): Boolean {
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        return try {
            inputStream = BufferedInputStream(source.openInputStream())
            outputStream = target.openOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = inputStream.read(buffer)
                if (count == -1) break
                outputStream?.write(buffer, 0, count)
            }
            outputStream?.flush()
            true
        } catch (_: Throwable) {
            false
        } finally {
            closeQuietly(inputStream)
            closeQuietly(outputStream)
        }
    }

    /** 校验图片完整性：解码头部 + 非空。 */
    fun isValidImage(file: UniFile): Boolean {
        var inputStream: InputStream? = null
        return try {
            inputStream = file.openInputStream()
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeStream(inputStream, null, opts)
            opts.outWidth > 0 && opts.outHeight > 0 && opts.outMimeType != null
        } catch (_: Throwable) {
            false
        } finally {
            closeQuietly(inputStream)
        }
    }

    fun deleteRecursively(file: UniFile?): Boolean {
        if (file == null || !file.exists()) return true
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    if (!deleteRecursively(child)) return false
                }
            }
        }
        return file.delete()
    }

    fun readSpiderInfo(dir: UniFile): SpiderInfo? {
        val f = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return null
        return SpiderInfo.read(f)
    }

    fun writeSpiderInfo(dir: UniFile, spi: SpiderInfo) {
        try {
            val f = dir.createFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return
            f.openOutputStream().use { os -> spi.write(os) }
        } catch (_: Throwable) {
        }
    }

    /**
     * 取得（必要时创建）目标画廊下载目录。
     *
     * @param writeInfo 是否把 [spi] 预写入目标目录的 .ehviewer。
     *        预下载合并需要在下载开始前写入完整 token；而对正在下载的目标做重预检时
     *        目标已有自己的 .ehviewer，应传 false，避免覆盖正在使用的元数据。
     */
    fun ensureTargetDir(info: DownloadInfo, spi: SpiderInfo?, writeInfo: Boolean): UniFile? {
        val dir = SpiderDen.getGalleryDownloadDir(info) ?: return null
        if (!dir.ensureDir()) return null
        if (writeInfo && spi != null) {
            writeSpiderInfo(dir, spi)
        }
        return dir
    }

    /**
     * 把 [candidateDir] 中 hash 命中 [hashToNewIndex] 的图片复制到 [target]，
     * 复制成功且通过完整性校验后删除源文件；目标已存在同名文件则跳过。
     *
     * 不处理数据库记录，由调用方在成功后收尾（记录历史 / 删除源记录）。
     */
    suspend fun copyMatchingFiles(
        candidateDir: UniFile,
        candidateSpi: SpiderInfo,
        target: UniFile,
        hashToNewIndex: Map<String, Int>,
        onProgress: (processed: Int, total: Int, moved: Int) -> Unit = { _, _, _ -> },
        checkCancelled: suspend () -> Unit = {}
    ): FileMergeStats {
        val totalFiles = maxOf(candidateSpi.pTokenMap.size(), 1)
        var moved = 0
        var invalid = 0
        var processed = 0
        for (i in 0 until candidateSpi.pTokenMap.size()) {
            checkCancelled()
            val srcIndex = candidateSpi.pTokenMap.keyAt(i)
            val hash = candidateSpi.pTokenMap.valueAt(i)
            if (hash.isNullOrEmpty() || hash == SpiderInfo.TOKEN_FAILED) continue
            val newIndex = hashToNewIndex[hash] ?: continue
            val srcFile = SpiderDen.findImageFile(candidateDir, srcIndex) ?: continue
            val ext = extensionOf(srcFile.name)
            val targetName = String.format(Locale.US, "%08d%s", newIndex + 1, ext)
            if (target.findFile(targetName) != null) {
                continue
            }
            val targetFile = target.createFile(targetName) ?: continue
            if (copyFile(srcFile, targetFile)) {
                if (isValidImage(targetFile)) {
                    srcFile.delete()
                    moved++
                } else {
                    targetFile.delete()
                    invalid++
                }
            } else {
                targetFile.delete()
            }
            processed++
            onProgress(processed, totalFiles, moved)
        }
        return FileMergeStats(processed, moved, invalid)
    }
}
