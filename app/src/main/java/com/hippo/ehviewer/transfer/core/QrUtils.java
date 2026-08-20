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

package com.hippo.ehviewer.transfer.core;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.util.HashMap;
import java.util.Map;

/**
 * 二维码生成工具（ZXing）
 */
public class QrUtils {

    private static final String TAG = "QrUtils";

    /**
     * 生成指定尺寸的二维码位图
     *
     * @param content 二维码内容
     * @param sizePx  输出像素尺寸
     */
    public static Bitmap generateQrBitmap(String content, int sizePx) {
        TransferLogger.getInstance().d(TAG, "生成二维码: sizePx=" + sizePx + ", content=" + content);
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);

            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
            for (int x = 0; x < sizePx; x++) {
                for (int y = 0; y < sizePx; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            TransferLogger.getInstance().i(TAG, "二维码生成成功: " + sizePx + "x" + sizePx + "px, content=" + content);
            return bitmap;
        } catch (WriterException e) {
            TransferLogger.getInstance().e(TAG, "二维码生成失败: sizePx=" + sizePx + ", content=" + content, e);
            return null;
        }
    }
}
