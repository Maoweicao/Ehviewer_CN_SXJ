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

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ECDH 预共享密钥协商
 *
 * 协议对应：v3.0 §5.19.4 安全模型 + §5.23 心跳签名。
 *
 * 算法：P-256 (secp256r1) — 所有 Android 4.3+ / Node 12+ / 现代 PC 都支持。
 * 派生密钥：SHA-256(ECDH_shared || publicKeyA || publicKeyB)，按字典序拼接。
 */
object EcdhKeyAgreement {

    private const val TAG = "EcdhKeyAgreement"
    private const val KEY_ALGORITHM = "EC"
    private const val CURVE = "secp256r1"
    private const val KDF_DIGEST = "SHA-256"

    /**
     * 生成本机 ECDH 密钥对。
     */
    @JvmStatic
    @Throws(java.security.GeneralSecurityException::class)
    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        kpg.initialize(ECGenParameterSpec(CURVE), java.security.SecureRandom())
        return kpg.generateKeyPair()
    }

    /**
     * 从对端公钥（X.509 编码）派生 32 字节共享密钥。
     */
    @JvmStatic
    @Throws(java.security.GeneralSecurityException::class)
    fun deriveSharedKey(myKeyPair: KeyPair, peerPublicKeyB64: String): String {
        val peerPubBytes = Base64.decode(peerPublicKeyB64, Base64.NO_WRAP)
        val kf = KeyFactory.getInstance(KEY_ALGORITHM)
        val peerPub = kf.generatePublic(X509EncodedKeySpec(peerPubBytes))

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myKeyPair.private)
        ka.doPhase(peerPub, true)
        val sharedSecret = ka.generateSecret()

        // KDF：SHA-256(sharedSecret || pubA || pubB) 按字典序拼接
        val pubA = myKeyPair.public.encoded
        val pubB = peerPubBytes
        val ordered = if (compareLexicographically(pubA, pubB) <= 0)
            concat(concat(sharedSecret, pubA), pubB)
        else
            concat(concat(sharedSecret, pubB), pubA)
        val md = MessageDigest.getInstance(KDF_DIGEST)
        val derived = md.digest(ordered)

        return Base64.encodeToString(derived, Base64.NO_WRAP)
    }

    /**
     * 公钥 X.509 → Base64
     */
    @JvmStatic
    fun encodePublicKey(publicKey: PublicKey): String =
        Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)

    /**
     * HMAC-SHA256 签名（用于心跳 X-Lab-Signature）。
     */
    @JvmStatic
    fun sign(presharedKey: String, payload: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            val key = SecretKeySpec(
                Base64.decode(presharedKey, Base64.NO_WRAP), "HmacSHA256")
            mac.init(key)
            val sig = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(sig, Base64.NO_WRAP)
        } catch (e: Exception) {
            com.hippo.ehviewer.transfer.log.TransferLogger.getInstance()
                .w(TAG, "Failed to sign: ${e.message}")
            ""
        }
    }

    /**
     * 验证 HMAC 签名（远端心跳用）。
     */
    @JvmStatic
    fun verify(presharedKey: String, payload: String, signatureB64: String): Boolean {
        val expected = sign(presharedKey, payload)
        if (expected.isEmpty()) return false
        return constantTimeEquals(expected, signatureB64)
    }

    // ==================== 工具方法 ====================

    private fun compareLexicographically(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    private fun concat(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}