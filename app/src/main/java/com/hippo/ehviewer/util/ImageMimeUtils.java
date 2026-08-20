/*
 * Copyright 2026 Hippo Seven
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

package com.hippo.ehviewer.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 根据文件头（魔数）探测图片的 MIME 类型。
 * <p>
 * E-Hentai 缩略图既有 JPEG 也有 WebP 等格式，
 * 而缓存中保存的是服务端返回的原始字节，无法仅凭 URL 或扩展名判断。
 * 在生成 data URI 或写入缓存元数据前，应使用本工具探测真实格式，
 * 避免把 WebP 数据错误标注为 image/jpeg。
 */
public final class ImageMimeUtils {

    private static final String MIME_JPEG = "image/jpeg";
    private static final String MIME_PNG = "image/png";
    private static final String MIME_GIF = "image/gif";
    private static final String MIME_WEBP = "image/webp";
    private static final String MIME_BMP = "image/bmp";

    private ImageMimeUtils() {
    }

    /**
     * 探测图片字节数组的真实 MIME 类型。
     *
     * @param data 图片原始字节
     * @return 探测到的 MIME；无法识别或数据过短时回退为 "image/jpeg"
     */
    @NonNull
    public static String detectImageMime(@Nullable byte[] data) {
        if (data == null || data.length < 3) {
            return MIME_JPEG;
        }

        // JPEG: FF D8 FF
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return MIME_JPEG;
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (data.length >= 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && (data[4] & 0xFF) == 0x0D && (data[5] & 0xFF) == 0x0A
                && (data[6] & 0xFF) == 0x1A && (data[7] & 0xFF) == 0x0A) {
            return MIME_PNG;
        }

        // GIF: "GIF87a" / "GIF89a"
        if (data.length >= 6
                && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8') {
            return MIME_GIF;
        }

        // WebP: "RIFF" .... "WEBP"
        if (data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return MIME_WEBP;
        }

        // BMP: "BM"
        if (data.length >= 2 && data[0] == 'B' && data[1] == 'M') {
            return MIME_BMP;
        }

        return MIME_JPEG;
    }
}
