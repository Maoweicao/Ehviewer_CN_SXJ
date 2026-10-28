/*
 * Copyright 2026 EhViewer Contributors
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

package com.hippo.ehviewer.task

/**
 * 可由「自动化」传入参数的 BackgroundTask。
 *
 * <p>自动化动作的 `params` 是一段 JSON；任务在 {@link #execute()} 前接收。
 * 目前用于设备同步的方向（`{"direction":"pull"}`）。
 */
interface ParamAwareTask {

    fun setAutomationParams(params: String?)
}
