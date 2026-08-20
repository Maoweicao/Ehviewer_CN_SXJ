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

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import com.hippo.ehviewer.transfer.log.TransferLogger;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * NFC 卡模拟服务（HCE）
 *
 * 将本机模拟成一张 ISO-DEP (Type 4 Tag) NDEF 标签，内容为本机连接 URI。
 * 另一台设备开启传输界面后，NFC 碰一碰即可读到该 URI 并自动连接。
 *
 * 替代已被 API 35 移除的 Android Beam (setNdefPushMessage)。
 */
public class TransferNfcService extends HostApduService {

    private static final String TAG = "TransferNfcService";

    /**
     * NFC Forum Type 4 Tag applet AID
     */
    public static final String NFC_AID = "D2760000850101";

    private static final byte[] AID_BYTES = hexToBytes(NFC_AID);

    private static final byte INS_SELECT = (byte) 0xA4;
    private static final byte INS_READ_BINARY = (byte) 0xB0;

    private static final byte[] CC_FILE_ID = {(byte) 0xE1, 0x03};
    private static final byte[] NDEF_FILE_ID = {(byte) 0xE1, 0x04};

    private static final byte[] SW_SUCCESS = {(byte) 0x90, 0x00};
    private static final byte[] SW_END_OF_FILE = {0x62, (byte) 0x82};
    private static final byte[] SW_FILE_NOT_FOUND = {0x6A, (byte) 0x82};
    private static final byte[] SW_INSTR_NOT_SUPPORTED = {0x6D, 0x00};

    /**
     * Capability Container 文件（NFC Forum Type 4 Tag 2.0）
     */
    private static final byte[] CC_FILE = {
            0x00, 0x0F,          // CC length = 15
            0x20,                // mapping version 2.0
            0x00, 0x40,          // MLe: max read 64 bytes
            0x00, 0x04,          // MLc: max write 4 bytes
            0x04,                // read access: read allowed
            0x04,                // write access: write never
            0x04, 0x06,          // NDEF file control TLV
            (byte) 0xE1, 0x04,   // NDEF file identifier
            0x00, (byte) 0xFF,   // NDEF file max size
            0x00,                // read access
            0x00                 // write access
    };

    private boolean ccSelected;
    private boolean ndefSelected;

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        if (commandApdu == null || commandApdu.length < 4) {
            TransferLogger.getInstance().w(TAG, "NFC APDU 无效，长度不足: " + (commandApdu == null ? 0 : commandApdu.length));
            return SW_INSTR_NOT_SUPPORTED;
        }
        byte ins = commandApdu[1];
        TransferLogger.getInstance().d(TAG, String.format("NFC 收到APDU: ins=0x%02X, len=%d", ins, commandApdu.length));
        if (ins == INS_SELECT) {
            return handleSelect(commandApdu);
        }
        if (ins == INS_READ_BINARY) {
            return handleReadBinary(commandApdu);
        }
        TransferLogger.getInstance().w(TAG, String.format("NFC 不支持的指令: ins=0x%02X", ins));
        return SW_INSTR_NOT_SUPPORTED;
    }

    @Override
    public void onDeactivated(int reason) {
        TransferLogger.getInstance().d(TAG, "NFC 已停用: reason=" + reason);
        ccSelected = false;
        ndefSelected = false;
    }

    private byte[] handleSelect(byte[] commandApdu) {
        byte[] data = getData(commandApdu);
        if (data == null) {
            TransferLogger.getInstance().w(TAG, "NFC SELECT 缺少数据字段");
            return SW_FILE_NOT_FOUND;
        }
        if (data.length == AID_BYTES.length && Arrays.equals(data, AID_BYTES)) {
            ccSelected = false;
            ndefSelected = false;
            TransferLogger.getInstance().i(TAG, "NFC 选择成功: AID " + NFC_AID);
            return SW_SUCCESS;
        }
        if (data.length == 2 && Arrays.equals(data, CC_FILE_ID)) {
            ccSelected = true;
            ndefSelected = false;
            TransferLogger.getInstance().i(TAG, "NFC 选择成功: CC 文件");
            return SW_SUCCESS;
        }
        if (data.length == 2 && Arrays.equals(data, NDEF_FILE_ID)) {
            ccSelected = false;
            ndefSelected = true;
            TransferLogger.getInstance().i(TAG, "NFC 选择成功: NDEF 文件");
            return SW_SUCCESS;
        }
        TransferLogger.getInstance().w(TAG, "NFC SELECT 文件未找到: data=" + Arrays.toString(data));
        return SW_FILE_NOT_FOUND;
    }

    private byte[] handleReadBinary(byte[] commandApdu) {
        byte p1 = commandApdu[2];
        byte p2 = commandApdu[3];
        int offset = ((p1 & 0xFF) << 8) | (p2 & 0xFF);

        byte[] file;
        if (ccSelected) {
            file = CC_FILE;
        } else if (ndefSelected) {
            file = buildNdefFile();
        } else {
            TransferLogger.getInstance().w(TAG, "NFC READ 未选择文件");
            return SW_FILE_NOT_FOUND;
        }

        if (offset >= file.length) {
            TransferLogger.getInstance().d(TAG, "NFC READ 超出文件末尾: offset=" + offset + ", fileLen=" + file.length);
            return SW_END_OF_FILE;
        }

        int le = 0;
        if (commandApdu.length >= 6) {
            le = commandApdu[5] & 0xFF;
        } else if (commandApdu.length == 5) {
            le = commandApdu[4] & 0xFF;
        }
        int length = file.length - offset;
        if (le > 0 && length > le) {
            length = le;
        }
        if (length > 255) {
            length = 255;
        }

        byte[] result = new byte[length + 2];
        System.arraycopy(file, offset, result, 0, length);
        result[length] = SW_SUCCESS[0];
        result[length + 1] = SW_SUCCESS[1];
        TransferLogger.getInstance().d(TAG, "NFC READ 成功: offset=" + offset + ", length=" + length);
        return result;
    }

    private byte[] getData(byte[] commandApdu) {
        if (commandApdu.length < 5) {
            return null;
        }
        int lc = commandApdu[4] & 0xFF;
        if (lc <= 0 || 5 + lc > commandApdu.length) {
            return null;
        }
        return Arrays.copyOfRange(commandApdu, 5, 5 + lc);
    }

    private byte[] buildNdefFile() {
        String host = NetworkUtils.getWifiIpAddress();
        String uri = ConnectInfoCodec.encode(host, TransferClientManager.DEFAULT_PORT, android.os.Build.MODEL);
        if (uri.getBytes(StandardCharsets.UTF_8).length > 250) {
            uri = ConnectInfoCodec.encode(host, TransferClientManager.DEFAULT_PORT, null);
        }
        TransferLogger.getInstance().i(TAG, "NFC NDEF 内容: " + uri);

        byte[] uriBytes = uri.getBytes(StandardCharsets.UTF_8);
        // NDEF message: MB|ME|SR|TNF=WELL_KNOWN(1), type length=1,
        // payload = URI record "U" + prefix(0x00) + full uri
        byte[] ndefMessage = new byte[5 + uriBytes.length];
        ndefMessage[0] = (byte) 0xD1;
        ndefMessage[1] = 0x01;
        ndefMessage[2] = (byte) (1 + uriBytes.length);
        ndefMessage[3] = 0x55;
        ndefMessage[4] = 0x00;
        System.arraycopy(uriBytes, 0, ndefMessage, 5, uriBytes.length);

        // NDEF file: [2 bytes msg length][TLV tag 0x03][TLV length][message][0xFE]
        byte[] file = new byte[5 + ndefMessage.length];
        file[0] = (byte) ((ndefMessage.length >> 8) & 0xFF);
        file[1] = (byte) (ndefMessage.length & 0xFF);
        file[2] = 0x03;
        file[3] = (byte) ndefMessage.length;
        System.arraycopy(ndefMessage, 0, file, 4, ndefMessage.length);
        file[file.length - 1] = (byte) 0xFE;
        return file;
    }

    private static byte[] hexToBytes(String hex) {
        int length = hex.length() / 2;
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
