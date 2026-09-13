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

package com.hippo.ehviewer.lab

/**
 * 实验室使用的端口辅助工具
 *
 * M1 阶段：返回 TransferServerManager 的默认端口（与 v2.x 一致 = 8080）。
 * M2+ 将支持可配置端口。
 */
object TransferPortHelper {

    /**
     * 获取 Transfer HTTP 服务端口。
     */
    @JvmStatic
    fun getPort(@Suppress("UNUSED_PARAMETER") context: android.content.Context?): Int {
        // 与 com.hippo.ehviewer.transfer.core.TransferServerManager.DEFAULT_PORT 保持一致
        return 8080
    }
}