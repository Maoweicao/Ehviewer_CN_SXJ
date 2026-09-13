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

package com.hippo.ehviewer.lab.log

import com.hippo.ehviewer.transfer.log.TransferLogger

/**
 * 实验室快照模块日志代理
 *
 * 设计动机：快照模块产生的日志希望自带 "Snapshot" 前缀便于在 TransferLogger 输出里区分，
 * 但避免新增一套独立的 logger 系统造成日志基础设施碎片化。
 */
object SnapshotLogger {

    private const val PREFIX = "Snapshot/"

    @JvmStatic
    fun d(tag: String, msg: String) {
        TransferLogger.getInstance().d("$PREFIX$tag", msg)
    }

    @JvmStatic
    fun i(tag: String, msg: String) {
        TransferLogger.getInstance().i("$PREFIX$tag", msg)
    }

    @JvmStatic
    fun w(tag: String, msg: String) {
        TransferLogger.getInstance().w("$PREFIX$tag", msg)
    }

    @JvmStatic
    fun w(tag: String, msg: String, t: Throwable) {
        TransferLogger.getInstance().w("$PREFIX$tag", msg, t)
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        TransferLogger.getInstance().e("$PREFIX$tag", msg)
    }

    @JvmStatic
    fun e(tag: String, msg: String, t: Throwable) {
        TransferLogger.getInstance().e("$PREFIX$tag", msg, t)
    }
}