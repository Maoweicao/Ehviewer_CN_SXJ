package com.hippo.ehviewer.task

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.cache.GalleryCacheManager
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.dao.DownloadHistory
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.unifile.UniFile
import com.hippo.util.ExceptionUtils
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

/**
 * 恢复下载候选的“一个文件夹”视图。
 *
 * 由 [RestoreDownloadTask]（扫描下载目录）和 [ResumeFromHistoryTask]（扫描下载历史）共同构建，
 * 交给 [RestoreCandidateValidator] 做四步过滤。
 */
class RestoreCandidate(
    val gid: Long,
    val dirname: String,
    /** 本地目录；从历史恢复但目录不存在时为 null（走完整下载） */
    val dir: UniFile?,
    var token: String?,
    var title: String? = null,
    var pages: Int = 0,
    /** `.ehviewer` 里读到的 pToken 集合 */
    var hashes: Set<String> = emptySet(),
    var fileCount: Int = 0,
    var modifiedAt: Long = 0L,
    /** 调用方私有载荷（RestoreItem / DownloadHistory） */
    var payload: Any? = null
) {
    /** 该 gid 的旧映射目录已不存在，恢复成功后应改指当前目录 */
    var replaceMapping: Boolean = false

    /** 本次校验是否联网补齐过元数据 */
    var repaired: Boolean = false
}

enum class RestoreRejectReason {
    /** 目录已被另一个 gid 占用 */
    DIR_CLAIMED_BY_OTHER,
    /** 该 gid 已映射到另一个仍然存在的目录 */
    GID_MAPPED_ELSEWHERE,
    /** 下载管理器里已经有这个任务 */
    ALREADY_DOWNLOADING,
    /** 同一 gid 的另一个目录更完整 */
    DUPLICATE_OF_OTHER,
    /** 被另一个更完整的画廊递进包含 */
    INCLUDED_BY_OTHER,
    /** 没有可用 token，无法联网修复 */
    NO_TOKEN,
    /** 联网修复失败（离线 / 限流 / 超时 / 画廊已下架） */
    METADATA_REPAIR_FAILED,
    /** 修复后元数据仍不齐全 */
    METADATA_INCOMPLETE
}

class RestoreRejection(
    val candidate: RestoreCandidate,
    val reason: RestoreRejectReason,
    val detail: String? = null
)

class RestoreValidationResult(
    val accepted: List<RestoreCandidate>,
    val rejected: List<RestoreRejection>
)

/**
 * 恢复候选校验器：① 目录映射占用 → ④ 下载管理器已有 → ② 递进包含 → ③ 元数据齐全（必要时联网重建）。
 *
 * 编号沿用需求里的四步；实际执行把联网放在最后，避免为注定被跳过的项发请求。
 *
 * 判定刻意保守：只有“候选的 pToken 集完整且被别的画廊严格包含”才判定为重复；
 * 集合不完整（未下载完）时不做包含判定，宁可多恢复一份，也不误丢内容。
 */
class RestoreCandidateValidator(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val httpClient: OkHttpClient,
    private val log: (String) -> Unit = {},
    /** 校验阶段进度回调：(current, total, detail)，避免 UI 一直停在不确定进度 */
    private val progress: (Int, Int, String) -> Unit = { _, _, _ -> }
) {

    companion object {
        private const val TAG = "RestoreValidator"

        /** 联网修复的请求间隔，避免触发限流 */
        private const val REQUEST_GAP_MS = 300L

        /** 超过该候选数时跳过 pToken 递进包含判定（O(n·m) 太慢） */
        private const val MAX_CONTAINMENT_CANDIDATES = 4000
    }

    private class Entry(
        val gid: Long,
        val dirname: String,
        val hashes: IntArray,
        val pages: Int,
        val inDownloadList: Boolean,
        val files: Int,
        val mtime: Long
    )

    private class Keeper(
        val gid: Long,
        val dirname: String,
        val hashes: Set<String>,
        val pages: Int,
        val files: Int,
        val mtime: Long,
        val inDownloadList: Boolean
    )

    private val downloadRoot by lazy { Settings.getDownloadLocation() }

    /** 下载列表与“有效历史 + 本地目录”的 hash 集，整轮任务只读一次 */
    private val keepers: List<Keeper> by lazy { loadKeepers() }

    suspend fun validate(candidates: List<RestoreCandidate>): RestoreValidationResult {
        val rejected = ArrayList<RestoreRejection>()
        val dirnameOwner = EhDB.getDownloadDirnameMap()
        // 反向表：gid → 已映射目录，避免每个候选再查一次库
        val gidToDirname = HashMap<Long, String>(dirnameOwner.size * 2)
        for (entry in dirnameOwner.entries) {
            gidToDirname[entry.value] = entry.key
        }
        val total = candidates.size

        // ① 映射占用 + ④ 下载管理器已有
        val stage1 = ArrayList<RestoreCandidate>()
        for ((index, candidate) in candidates.withIndex()) {
            if (index % 200 == 0) {
                progress(index, total, "校验候选 ${index}/$total")
            }
            // 没有本地目录时（历史恢复走完整下载），映射不属于本次判定范围
            if (candidate.dir != null) {
                val owner = dirnameOwner[candidate.dirname]
                if (owner != null && owner != candidate.gid) {
                    rejected.add(
                        RestoreRejection(
                            candidate, RestoreRejectReason.DIR_CLAIMED_BY_OTHER, "owner=$owner"
                        )
                    )
                    continue
                }

                val mapped = gidToDirname[candidate.gid]
                if (mapped != null && mapped != candidate.dirname) {
                    val mappedDir = downloadRoot?.subFile(mapped)
                    if (mappedDir != null && mappedDir.isDirectory) {
                        rejected.add(
                            RestoreRejection(
                                candidate, RestoreRejectReason.GID_MAPPED_ELSEWHERE, mapped
                            )
                        )
                        continue
                    }
                    // 旧目录已不存在：陈旧映射，允许恢复后改指当前目录
                    candidate.replaceMapping = true
                }
            }

            if (downloadManager.containDownloadInfo(candidate.gid)) {
                rejected.add(
                    RestoreRejection(
                        candidate, RestoreRejectReason.ALREADY_DOWNLOADING, null
                    )
                )
                continue
            }

            stage1.add(candidate)
        }
        progress(total, total, "候选去重与包含检查…")

        if (stage1.isEmpty()) {
            return RestoreValidationResult(emptyList(), rejected)
        }

        // ②-a 目录/ gid 身份去重（不看 pToken）
        val unique = dedupeByIdentity(stage1, dirnameOwner, rejected)

        // ②-b 递进包含
        val survivors = ArrayList<RestoreCandidate>()
        // pToken 两两包含判定在大批量下内存/CPU 开销极大（1.5 万条 × 上百 hash），
        // 超过阈值就跳过，只保留目录/gid 去重，避免任务长时间卡在“不确定进度”。
        val contained = if (unique.size <= MAX_CONTAINMENT_CANDIDATES) {
            findContainedLosers(unique)
        } else {
            log("候选 ${unique.size} 个，超过 $MAX_CONTAINMENT_CANDIDATES，跳过递进包含判定")
            emptyMap()
        }
        for (candidate in unique) {
            val keeperGid = contained[candidate]
            if (keeperGid != null) {
                rejected.add(
                    RestoreRejection(
                        candidate, RestoreRejectReason.INCLUDED_BY_OTHER, "keeper=$keeperGid"
                    )
                )
            } else {
                survivors.add(candidate)
            }
        }

        // ③ 元数据齐全（必要时联网重建）
        val accepted = ArrayList<RestoreCandidate>()
        for ((index, candidate) in survivors.withIndex()) {
            progress(index, survivors.size, "补全元数据 ${index}/${survivors.size}")
            val reason = ensureMetadata(candidate)
            if (reason != null) {
                rejected.add(RestoreRejection(candidate, reason, describe(candidate)))
            } else {
                accepted.add(candidate)
            }
        }
        progress(survivors.size, survivors.size, "元数据检查完成")

        // 修复过元数据的候选拿到了完整 pToken 集，需要再查一次包含关系
        if (accepted.any { it.repaired }) {
            val finalList = ArrayList<RestoreCandidate>()
            val containedAfterRepair = findContainedLosers(accepted)
            for (candidate in accepted) {
                val keeperGid = containedAfterRepair[candidate]
                if (keeperGid != null) {
                    rejected.add(
                        RestoreRejection(
                            candidate, RestoreRejectReason.INCLUDED_BY_OTHER, "keeper=$keeperGid"
                        )
                    )
                } else {
                    finalList.add(candidate)
                }
            }
            return RestoreValidationResult(finalList, rejected)
        }

        return RestoreValidationResult(accepted, rejected)
    }

    // ==================== ②-a 身份去重 ====================

    private fun dedupeByIdentity(
        candidates: List<RestoreCandidate>,
        dirnameOwner: Map<String, Long>,
        rejected: MutableList<RestoreRejection>
    ): List<RestoreCandidate> {
        val result = ArrayList<RestoreCandidate>()

        // 同一目录被不同 gid 认领：保留与映射一致 / 文件更多的那份
        // 注意：目录名为空的候选表示“目录已消失”，彼此不是同一目录，不参与这一轮去重
        val byDirname = LinkedHashMap<String, MutableList<RestoreCandidate>>()
        for (candidate in candidates) {
            if (candidate.dir == null || candidate.dirname.isEmpty()) {
                result.add(candidate)
                continue
            }
            byDirname.getOrPut(candidate.dirname) { ArrayList() }.add(candidate)
        }
        for ((dirname, group) in byDirname) {
            if (group.size == 1) {
                result.add(group[0])
                continue
            }
            val owner = dirnameOwner[dirname]
            val best = group.maxWithOrNull(
                compareBy<RestoreCandidate>(
                    { if (owner != null && it.gid == owner) 1 else 0 },
                    { it.fileCount },
                    { it.modifiedAt },
                    { -it.gid }
                )
            ) ?: group[0]
            for (candidate in group) {
                if (candidate !== best) {
                    rejected.add(
                        RestoreRejection(
                            candidate, RestoreRejectReason.DIR_CLAIMED_BY_OTHER,
                            "sameDir keeper=${best.gid}"
                        )
                    )
                }
            }
            result.add(best)
        }

        // 同一 gid 出现在多个目录：保留映射目录 / 更完整的那份
        val byGid = LinkedHashMap<Long, MutableList<RestoreCandidate>>()
        for (candidate in result) {
            byGid.getOrPut(candidate.gid) { ArrayList() }.add(candidate)
        }
        val deduped = ArrayList<RestoreCandidate>(byGid.size)
        for ((gid, group) in byGid) {
            if (group.size == 1) {
                deduped.add(group[0])
                continue
            }
            val mapped = EhDB.getDownloadDirname(gid)
            val best = group.maxWithOrNull(
                compareBy<RestoreCandidate>(
                    { if (mapped != null && it.dirname == mapped) 1 else 0 },
                    { it.fileCount },
                    { it.modifiedAt },
                    { -it.gid }
                )
            ) ?: group[0]
            for (candidate in group) {
                if (candidate !== best) {
                    rejected.add(
                        RestoreRejection(
                            candidate, RestoreRejectReason.DUPLICATE_OF_OTHER,
                            "sameGid keeper=${best.dirname}"
                        )
                    )
                }
            }
            deduped.add(best)
        }

        return deduped
    }

    // ==================== ②-b 递进包含 ====================

    /**
     * 候选被别的更完整画廊包含时返回 candidate -> keeperGid。
     *
     * 只有候选的 pToken 集“完整”（hash 数不小于页数）才参与判定：
     * 未下载完的画廊 token 集天然是别的画廊的子集，拿它做包含判断会误杀。
     */
    private fun findContainedLosers(candidates: List<RestoreCandidate>): Map<RestoreCandidate, Long> {
        val losers = HashMap<RestoreCandidate, Long>()
        if (candidates.isEmpty()) return losers

        val ids = HashMap<String, Int>()
        fun toIds(set: Collection<String>): IntArray {
            if (set.isEmpty()) return IntArray(0)
            val arr = IntArray(set.size)
            var i = 0
            for (token in set) {
                arr[i++] = ids.getOrPut(token) { ids.size }
            }
            arr.sort()
            return arr
        }

        val entries = ArrayList<Entry>()
        val candidateEntry = HashMap<RestoreCandidate, Int>()
        val candidateKeys = HashSet<String>()
        for (candidate in candidates) {
            candidateKeys.add(keyOf(candidate.gid, candidate.dirname))
            if (!isCompleteSet(candidate)) continue
            candidateEntry[candidate] = entries.size
            entries.add(
                Entry(
                    candidate.gid, candidate.dirname, toIds(candidate.hashes),
                    candidate.pages, false, candidate.fileCount, candidate.modifiedAt
                )
            )
        }

        for (keeper in keepers) {
            // 同一份目录已经作为候选参与，不重复加入
            if (candidateKeys.contains(keyOf(keeper.gid, keeper.dirname))) continue
            entries.add(
                Entry(
                    keeper.gid, keeper.dirname, toIds(keeper.hashes),
                    keeper.pages, keeper.inDownloadList, keeper.files, keeper.mtime
                )
            )
        }

        if (entries.size <= 1) return losers

        val supersets = SubsetContainment.findStrictSupersets(
            Array(entries.size) { entries[it].hashes }
        )

        // 等集去重：包含判定只处理“hash 数更多”，等集需要单独比一次
        val bestBySignature = HashMap<HashKey, Int>()
        for (i in entries.indices) {
            val signature = HashKey(entries[i].hashes)
            val current = bestBySignature[signature]
            if (current == null || compareEntry(entries[i], entries[current]) > 0) {
                bestBySignature[signature] = i
            }
        }

        for ((candidate, index) in candidateEntry) {
            val self = entries[index]
            var best: Entry? = null
            for (j in supersets.supersets[index]) {
                val other = entries[j]
                if (best == null || compareEntry(other, best) > 0) best = other
            }
            val equalBestIndex = bestBySignature[HashKey(self.hashes)]
            if (equalBestIndex != null && equalBestIndex != index) {
                val other = entries[equalBestIndex]
                if (compareEntry(other, self) > 0 && (best == null || compareEntry(other, best) > 0)) {
                    best = other
                }
            }
            if (best != null) {
                losers[candidate] = best.gid
            }
        }

        return losers
    }

    private fun isCompleteSet(candidate: RestoreCandidate): Boolean {
        return candidate.hashes.isNotEmpty() &&
            candidate.pages > 0 &&
            candidate.hashes.size >= candidate.pages
    }

    /**
     * 以 hash 集合内容做键，用真正的集合相等代替哈希签名，
     * 避免不同集合碰撞后被当成“等集”误判为重复。
     */
    private class HashKey(val values: IntArray) {
        override fun hashCode(): Int = values.contentHashCode()

        override fun equals(other: Any?): Boolean {
            return other is HashKey && values.contentEquals(other.values)
        }
    }    private fun keyOf(gid: Long, dirname: String): String = "$gid|$dirname"

    /** 正数表示 a 更完整/更应保留 */
    private fun compareEntry(a: Entry, b: Entry): Int = when {
        a.inDownloadList != b.inDownloadList -> if (a.inDownloadList) 1 else -1
        a.hashes.size != b.hashes.size -> a.hashes.size.compareTo(b.hashes.size)
        a.pages != b.pages -> a.pages.compareTo(b.pages)
        a.files != b.files -> a.files.compareTo(b.files)
        a.mtime != b.mtime -> a.mtime.compareTo(b.mtime)
        else -> b.gid.compareTo(a.gid)
    }

    // ==================== ③ 元数据齐全 / 联网重建 ====================

    private suspend fun ensureMetadata(candidate: RestoreCandidate): RestoreRejectReason? {
        val dir = candidate.dir ?: return null

        val cacheManager = GalleryCacheManager.getInstance(context)
        val cache = try {
            cacheManager.readGalleryCache(candidate.gid, dir)
        } catch (e: Throwable) {
            null
        }

        var token = candidate.token?.takeIf { it.isNotEmpty() }
            ?: cache?.token?.takeIf { it.isNotEmpty() }
            ?: EhDB.getDownloadHistory(candidate.gid)?.token?.takeIf { it.isNotEmpty() }
            ?: EhDB.getDownloadInfo(candidate.gid)?.token?.takeIf { it.isNotEmpty() }

        if (token.isNullOrEmpty()) {
            log("跳过 ${candidate.dirname}：缺少 token，无法联网修复元数据")
            return RestoreRejectReason.NO_TOKEN
        }
        candidate.token = token

        var pages = candidate.pages
        if (pages <= 0) pages = cache?.pages ?: 0

        // 只有“页数未知”时才联网；`.ehviewer.extra.json` 缺失不再强制补，
        // 否则 1.5 万个历史记录会触发 1.5 万次详情请求，任务永远跑不完。
        if (pages <= 0) {
            val detail = fetchDetail(candidate.gid, token) ?: return RestoreRejectReason.METADATA_REPAIR_FAILED
            pages = detail.pages
            if (pages <= 0) return RestoreRejectReason.METADATA_INCOMPLETE
            candidate.pages = pages
            candidate.repaired = true
            // 顺手把详情缓存写回目录，下次重建/恢复可以直接复用
            try {
                cacheManager.saveGalleryCacheToDir(detail, dir)
            } catch (e: Throwable) {
                Log.w(TAG, "save gallery cache failed: ${candidate.dirname}", e)
            }
            delay(REQUEST_GAP_MS)
        }

        if (pages > 0) candidate.pages = pages

        // `.ehviewer` 缺失时补一份，保证本地画廊能正常打开；
        // 已有 .ehviewer 但缺 pToken 的（未下载完）不联网补，避免逐个请求。
        if (candidate.hashes.isEmpty()) {
            val siFile = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME)
            if (siFile == null || !siFile.exists()) {
                val info = DownloadInfo(candidate.gid).apply {
                    this.token = token
                    this.pages = candidate.pages
                }
                val spiderInfo = SpiderTokenUtils.fetchSpiderInfoFromRemote(context, info)
                if (spiderInfo != null && writeSpiderInfo(dir, spiderInfo)) {
                    candidate.pages = spiderInfo.pages
                    candidate.hashes = SpiderTokenUtils.toHashSet(spiderInfo)
                    candidate.repaired = true
                    delay(REQUEST_GAP_MS)
                }
            }
        }

        return null
    }

    private suspend fun fetchDetail(gid: Long, token: String): GalleryDetail? {
        return try {
            EhEngine.getGalleryDetail(
                null, httpClient, EhUrl.getGalleryDetailUrl(gid, token, 0, false)
            )
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
            Log.w(TAG, "getGalleryDetail failed: gid=$gid", e)
            log("获取画廊详情失败 gid=$gid：${e.message}")
            null
        }
    }

    private fun writeSpiderInfo(dir: UniFile, spiderInfo: SpiderInfo): Boolean {
        return try {
            val file = dir.createFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return false
            file.openOutputStream().use { spiderInfo.write(it) }
            true
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
            Log.w(TAG, "write .ehviewer failed: ${dir.name}", e)
            false
        }
    }

    private fun describe(candidate: RestoreCandidate): String {
        return "gid=${candidate.gid} dir=${candidate.dirname} " +
            "pages=${candidate.pages} hashes=${candidate.hashes.size}"
    }

    // ==================== 保留下载列表 / 有效历史的 hash 集 ====================

    private fun loadKeepers(): List<Keeper> {
        val result = ArrayList<Keeper>()
        val downloadGids = HashSet<Long>()

        // 只对“有目录映射”的下载项读取 hash：以前对每条下载都查一次库，
        // 2 万条下载时 validation 会卡在不确定进度上好几分钟。
        val gidToDirname = HashMap<Long, String>()
        try {
            for (entry in EhDB.getDownloadDirnameMap().entries) {
                gidToDirname[entry.value] = entry.key
            }
        } catch (e: Exception) {
            Log.w(TAG, "load dirname map failed", e)
        }

        try {
            val infos = downloadManager.getAllDownloadInfoSnapshot()
            for (info in infos) {
                downloadGids.add(info.gid)
                val dirname = gidToDirname[info.gid] ?: continue
                val dir = downloadRoot?.subFile(dirname) ?: continue
                if (!dir.isDirectory) continue
                val hashes = readHashes(info.gid, dir) ?: continue
                if (hashes.isEmpty()) continue
                result.add(
                    Keeper(
                        gid = info.gid,
                        dirname = dirname,
                        hashes = hashes,
                        pages = info.pages,
                        files = 0,
                        mtime = dir.lastModified(),
                        inDownloadList = true
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "load download keepers failed", e)
        }

        try {
            val histories = EhDB.getAllDownloadHistory()
            for (history in histories) {
                if (history.deletionType != DownloadHistory.DELETION_NONE) continue
                if (downloadGids.contains(history.gid)) continue
                val dirname = history.filePath ?: continue
                val dir = downloadRoot?.subFile(dirname) ?: continue
                if (!dir.isDirectory) continue
                val hashes = readHashes(history.gid, dir) ?: continue
                if (hashes.isEmpty()) continue
                result.add(
                    Keeper(
                        gid = history.gid,
                        dirname = dirname,
                        hashes = hashes,
                        pages = 0,
                        files = history.totalFiles,
                        mtime = dir.lastModified(),
                        inDownloadList = false
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "load history keepers failed", e)
        }

        return result
    }

    /** 不联网：SimpleScanCache → PtokenIndex → 本地 .ehviewer */
    private fun readHashes(gid: Long, dir: UniFile): Set<String>? {
        SimpleScanCache.getHashSet(gid)?.let { return it }
        try {
            val index = EhDB.getPtokensIndex(gid)
            if (index != null) {
                val set = SpiderTokenUtils.parsePtokenString(index.ptokens)
                if (set.isNotEmpty()) return set
            }
        } catch (e: Exception) {
            Log.w(TAG, "read ptokens index failed: gid=$gid", e)
        }
        val file = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) ?: return null
        val spiderInfo = SpiderInfo.read(file) ?: return null
        return SpiderTokenUtils.toHashSet(spiderInfo)
    }
}
