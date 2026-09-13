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

package com.hippo.ehviewer.lab.source

import androidx.annotation.NonNull

/**
 * 图片读取结果
 *
 * 封装图片二进制 + 来源元信息，供 UI 层做徽标渲染。
 */
class ImageResult private constructor(
    @NonNull val data: ByteArray,
    @NonNull val mimeType: String,
    @NonNull val source: ImageSource,
    @NonNull val chainAfterHit: List<String>,
) {
    companion object {
        @JvmStatic
        fun of(@NonNull data: ByteArray, @NonNull mimeType: String,
               @NonNull source: ImageSource): ImageResult =
            ImageResult(data, mimeType, source, emptyList())

        @JvmStatic
        fun of(@NonNull data: ByteArray, @NonNull mimeType: String,
               @NonNull source: ImageSource, @NonNull chainAfterHit: List<String>): ImageResult =
            ImageResult(data, mimeType, source, ArrayList(chainAfterHit))
    }
}