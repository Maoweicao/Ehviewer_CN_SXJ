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

package com.hippo.ehviewer.lab.relay

import android.content.Context
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.lab.LabConfig
import com.hippo.ehviewer.lab.LabManager
import java.util.ArrayDeque

/**
 * 自动接力判定器
 *
 * 协议对应：v3.0 §5.19.2 自动接力配置 + 整体设计文档的「卡顿判定」。
 *
 * 触发条件（全部满足才触发）：
 * <ol>
 *   <li>已下载时长 ≥ [LabConfig.AutoRelay.minElapsedSeconds]</li>
 *   <li>最近 [windowSize] 个样本（每 5s 一个）的平均速度 < [LabConfig.AutoRelay.speedThresholdBps]</li>
 *   <li>错误率 ≥ [LabConfig.AutoRelay.errorRateThreshold]</li>
 * </ol>
 */
class AutoRelayJudge(
    context: Context,
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS,
) {

    private val labManager = LabManager.getInstance(context)
    private val states = HashMap<Long, PerDownloadState>()

    /**
     * 判定结果
     */
    enum class Verdict {
        /** 速度正常，不触发接力 */
        OK,
        /** 已达到触发阈值，建议接力 */
        STALL,
        /** 尚未达到最小判定时长 */
        TOO_EARLY,
        /** 已超过单画廊最大接力次数 */
        MAX_RETRIES_REACHED,
    }

    /**
     * 接收 DownloadInfo 更新（由 DownloadManager.addDownloadInfoListener 调用）。
     * 返回判定结果；若返回 [Verdict.STALL] 表示触发自动接力。
     */
    @Synchronized
    fun onDownloadInfoUpdated(info: DownloadInfo): Verdict {
        val cfg = labManager.configStore.get()
        if (!cfg.isEnabled() || !cfg.isSubEnabled("autoRelay")) {
            return Verdict.OK
        }
        val ar = cfg.autoRelay

        val now = System.currentTimeMillis()
        val st = states.getOrPut(info.gid) { PerDownloadState(now) }

        // 超过最大次数
        if (st.retryCount >= ar.maxRetries) {
            return Verdict.MAX_RETRIES_REACHED
        }

        // 最小已下载时长
        val elapsedSec = (now - st.startMs) / 1000
        if (elapsedSec < ar.minElapsedSeconds) {
            return Verdict.TOO_EARLY
        }

        // 采集当前样本
        val bps = if (info.speed > 0) info.speed else 0L
        val failed = info.state == DownloadInfo.STATE_FAILED
        addSample(st, now, bps, failed)

        return analyzeVerdict(st, ar, elapsedSec)
    }

    private fun addSample(st: PerDownloadState, now: Long, bps: Long, failed: Boolean) {
        st.window.addLast(SpeedSample(now, bps, failed))
        while (st.window.size > windowSize) st.window.removeFirst()

        // 同时移除过期的样本
        val cutoff = now - sampleIntervalMs * windowSize * 2L
        while (st.window.isNotEmpty() && st.window.peekFirst().timestampMs < cutoff) {
            st.window.removeFirst()
        }
    }

    private fun analyzeVerdict(
        st: PerDownloadState,
        ar: LabConfig.AutoRelay,
        elapsedSec: Long,
    ): Verdict {
        if (st.window.size < windowSize) return Verdict.OK

        var sumBps = 0L
        var failedCount = 0
        for (s in st.window) {
            sumBps += s.bytesPerSec
            if (s.failed) failedCount++
        }
        val avgBps = sumBps / st.window.size
        val errorRate = failedCount.toDouble() / st.window.size

        if (avgBps < ar.speedThresholdBps && elapsedSec >= ar.minElapsedSeconds) {
            return Verdict.STALL
        }
        if (errorRate >= ar.errorRateThreshold) {
            return Verdict.STALL
        }
        return Verdict.OK
    }

    /**
     * 当一次接力被触发后调用，记录已接力次数。
     */
    @Synchronized
    fun recordRelayInvoked(gid: Long) {
        val st = states[gid] ?: return
        st.retryCount++
        st.window.clear()
        st.startMs = System.currentTimeMillis()
    }

    /**
     * 清理某画廊状态（删除/完成时调用）。
     */
    @Synchronized
    fun clear(gid: Long) {
        states.remove(gid)
    }

    /**
     * 重置所有状态（用户主动重置时调用）。
     */
    @Synchronized
    fun clearAll() {
        states.clear()
    }

    /**
     * 当前被监控的画廊数。
     */
    @Synchronized
    fun trackedCount(): Int = states.size

    /**
     * 给定画廊的当前 retryCount。
     */
    @Synchronized
    fun currentRetryCount(gid: Long): Int = states[gid]?.retryCount ?: 0

    /**
     * 速度样本（内部使用）
     */
    private data class SpeedSample(val timestampMs: Long, val bytesPerSec: Long, val failed: Boolean)

    /**
     * 单画廊的判定状态
     */
    private class PerDownloadState(var startMs: Long) {
        var retryCount: Int = 0
        val window = ArrayDeque<SpeedSample>()
    }

    companion object {
        private const val DEFAULT_WINDOW_SIZE = 6
        private const val DEFAULT_SAMPLE_INTERVAL_MS = 5_000L
    }
}