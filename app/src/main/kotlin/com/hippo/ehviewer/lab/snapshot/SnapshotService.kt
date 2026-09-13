/*
 * Copyright 2025 EhViewer Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.lab.snapshot

import android.content.Context
import android.util.Base64
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.transfer.log.TransferLogger
import com.hippo.unifile.UniFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * 快照服务：负责扫描本地画廊并读取缩略图。
 *
 * 协议对应：v3.0 §5.22 的服务端数据源。
 *
 * 缩略图定位：
 * <ul>
 *   <li>优先查找 {@code <downloadDir>/<gid> - <title>/.thumb}</li>
 *   <li>不存在则查找 {@code <downloadDir>/<gid> - <title>/.ehviewer.extra.json} 中的 thumbBase64 字段</li>
 *   <li>M3+ 将支持 SpiderQueen 的 thumbnail API</li>
 * </ul>
 */
class SnapshotService(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * 读取某画廊的缩略图二进制；找不到返回 null。
     */
    fun readThumbnailBytes(gid: Long): ByteArray? {
        val info = EhApplication.getDownloadManager(appContext)
            .getDownloadInfo(gid) ?: return null
        val dir = SpiderDen.getGalleryDownloadDir(info) ?: return null
        val thumb = dir.findFile(".thumb") ?: return null
        if (!thumb.isFile) return null
        if (thumb.length() > THUMB_MAX_READ) {
            TransferLogger.getInstance().w(
                TAG, "Thumbnail too large, skipping: gid=$gid size=${thumb.length()}")
            return null
        }
        return try {
            thumb.openInputStream().use { input ->
                val buf = ByteArray(thumb.length().toInt())
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read != buf.size) {
                    val exact = ByteArray(read)
                    System.arraycopy(buf, 0, exact, 0, read)
                    exact
                } else buf
            }
        } catch (e: IOException) {
            TransferLogger.getInstance().w(TAG, "Failed to read thumb: gid=$gid", e)
            null
        }
    }

    /**
     * 读取缩略图并编码为 base64；找不到返回 null。
     */
    fun readThumbnailBase64(info: DownloadInfo): String? {
        if (info.gid <= 0) return null
        val data = readThumbnailBytes(info.gid) ?: return null
        if (data.isEmpty() || data.size > THUMB_MAX_READ) return null
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /**
     * 测试辅助：直接根据文件路径构造缩略图 base64。
     */
    fun readThumbnailBase64FromFile(f: File?): String? {
        if (f == null || !f.isFile) return null
        if (f.length() > THUMB_MAX_READ) return null
        return try {
            FileInputStream(f).use { input ->
                val buf = ByteArray(f.length().toInt())
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read == 0) return null
                val exact = if (read != buf.size) {
                    val e = ByteArray(read)
                    System.arraycopy(buf, 0, e, 0, read)
                    e
                } else buf
                Base64.encodeToString(exact, Base64.NO_WRAP)
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * 当前下载根目录（用于调试与文档）。
     */
    fun getDownloadRootPath(): String =
        com.hippo.ehviewer.Settings.getDownloadLocation()?.getUri()?.toString() ?: ""

    companion object {
        private const val TAG = "SnapshotService"

        /** 缩略图最大读取字节数（超过则视为异常文件跳过） */
        private const val THUMB_MAX_READ = 512 * 1024
    }
}