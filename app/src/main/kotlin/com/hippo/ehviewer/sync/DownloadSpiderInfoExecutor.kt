package com.hippo.ehviewer.sync

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hippo.ehviewer.callBack.SpiderInfoReadCallBack
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DownloadSpiderInfoExecutor(
    private val mList: MutableList<DownloadInfo>,
    private val callBack: SpiderInfoReadCallBack?
) {
    var handler: Handler = Handler(Looper.getMainLooper())
    private val service: ExecutorService = Executors.newSingleThreadExecutor()

    fun execute() {
        service.execute(Runnable {
            try {
                val batchSize = BATCH_SIZE
                val total = mList.size
                var offset = 0
                while (offset < total) {
                    val end = minOf(offset + batchSize, total)
                    val batchMap = HashMap<Long, SpiderInfo>(minOf(batchSize, end - offset))
                    for (i in offset until end) {
                        try {
                            val info = mList[i]
                            val spiderInfo = getSpiderInfo(info)
                            if (spiderInfo != null) {
                                batchMap[info.gid] = spiderInfo
                            }
                        } catch (e: OutOfMemoryError) {
                            Log.w(TAG, "OOM while reading spider info, skipping remaining batch", e)
                            System.gc()
                            break
                        } catch (e: Exception) {
                            Log.w(TAG, "Error reading spider info", e)
                        }
                    }
                    if (batchMap.isNotEmpty()) {
                        handler.post(Runnable {
                            callBack?.resultCallBack(batchMap)
                        })
                    }
                    offset = end
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM during spider info batch processing", e)
                System.gc()
            } catch (e: Exception) {
                Log.e(TAG, "Error during spider info batch processing", e)
            } finally {
                service.shutdown()
            }
        })
    }

    private fun getSpiderInfo(info: GalleryInfo): SpiderInfo? {
        try {
            val mDownloadDir = SpiderDen.getGalleryDownloadDir(info)
            if (mDownloadDir != null && mDownloadDir.isDirectory()) {
                val file = mDownloadDir.findFile(SpiderQueen.SPIDER_INFO_FILENAME)
                val spiderInfo = SpiderInfo.read(file)
                if (spiderInfo != null && spiderInfo.gid == info.gid &&
                    spiderInfo.token == info.token
                ) {
                    return spiderInfo
                }
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OOM while getting spider info for gid=${info.gid}", e)
            System.gc()
        } catch (e: Exception) {
            Log.w(TAG, "Error getting spider info for gid=${info.gid}", e)
        }
        return null
    }

    companion object {
        private const val TAG = "DownloadSpiderInfoExec"
        private const val BATCH_SIZE = 50
    }
}
