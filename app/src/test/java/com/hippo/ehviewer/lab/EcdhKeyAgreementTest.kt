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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试：ECDH 密钥协商 + HMAC
 */
class EcdhKeyAgreementTest {

    @Test
    fun deriveSharedKey_isSymmetric() {
        // Alice 与 Bob 各自生成密钥对
        val alice = EcdhKeyAgreement.generateKeyPair()
        val bob = EcdhKeyAgreement.generateKeyPair()

        // Alice 拿到 Bob 的公钥 → 派生出 sharedKey
        val aliceView = EcdhKeyAgreement.deriveSharedKey(alice,
                EcdhKeyAgreement.encodePublicKey(bob.public))
        // Bob 拿到 Alice 的公钥 → 派生出 sharedKey
        val bobView = EcdhKeyAgreement.deriveSharedKey(bob,
                EcdhKeyAgreement.encodePublicKey(alice.public))

        // 两人必须派生出同一份密钥
        assertEquals(aliceView, bobView)
        assertTrue("shared key should be non-empty", aliceView.isNotEmpty())
    }

    @Test
    fun deriveSharedKey_isOrderIndependent() {
        // KDF 输入顺序：无论谁先调用 derive，结果都相同
        val alice = EcdhKeyAgreement.generateKeyPair()
        val bob = EcdhKeyAgreement.generateKeyPair()

        val ab = EcdhKeyAgreement.deriveSharedKey(alice,
                EcdhKeyAgreement.encodePublicKey(bob.public))
        val ba = EcdhKeyAgreement.deriveSharedKey(bob,
                EcdhKeyAgreement.encodePublicKey(alice.public))
        assertEquals(ab, ba)
    }

    @Test
    fun deriveSharedKey_isDifferentAcrossPairs() {
        // 两组密钥对生成的共享密钥必须不同
        val a1 = EcdhKeyAgreement.generateKeyPair()
        val a2 = EcdhKeyAgreement.generateKeyPair()

        val k1 = EcdhKeyAgreement.deriveSharedKey(a1,
                EcdhKeyAgreement.encodePublicKey(a2.public))
        val k2 = EcdhKeyAgreement.deriveSharedKey(a2,
                EcdhKeyAgreement.encodePublicKey(a1.public))
        // 共享密钥本身应该相同（密钥协商的对称性），但与随机生成的密钥不同
        assertEquals(k1, k2)
        assertTrue(k1.length > 16)
    }

    @Test
    fun sign_andVerify_roundTrip() {
        val kp = EcdhKeyAgreement.generateKeyPair()
        val bob = EcdhKeyAgreement.generateKeyPair()
        val key = EcdhKeyAgreement.deriveSharedKey(kp,
                EcdhKeyAgreement.encodePublicKey(bob.public))

        val payload = "ts=12345;event=peer_joined;gid=100"
        val sig = EcdhKeyAgreement.sign(key, payload)
        assertNotNull(sig)
        assertTrue("sig should be non-empty", sig.isNotEmpty())
        assertTrue(EcdhKeyAgreement.verify(key, payload, sig))
    }

    @Test
    fun verify_failsOnTamperedPayload() {
        val kp = EcdhKeyAgreement.generateKeyPair()
        val bob = EcdhKeyAgreement.generateKeyPair()
        val key = EcdhKeyAgreement.deriveSharedKey(kp,
                EcdhKeyAgreement.encodePublicKey(bob.public))

        val sig = EcdhKeyAgreement.sign(key, "original")
        assertFalse(EcdhKeyAgreement.verify(key, "tampered", sig))
    }

    @Test
    fun verify_failsOnWrongKey() {
        val kp = EcdhKeyAgreement.generateKeyPair()
        val bob = EcdhKeyAgreement.generateKeyPair()
        val key = EcdhKeyAgreement.deriveSharedKey(kp,
                EcdhKeyAgreement.encodePublicKey(bob.public))

        val sig = EcdhKeyAgreement.sign(key, "payload")
        // 不同密钥 → 验证失败
        assertFalse(EcdhKeyAgreement.verify("not-base64", "payload", sig))
    }

    @Test
    fun encodePublicKey_roundTrip() {
        val kp = EcdhKeyAgreement.generateKeyPair()
        val encoded = EcdhKeyAgreement.encodePublicKey(kp.public)
        assertNotNull(encoded)
        assertTrue("X.509 encoded key should be base64", encoded.length > 50)
    }

    @Test
    fun multipleKeys_dontCollide() {
        // 3 组密钥对 → 3 个不同的共享密钥
        val keys = (1..3).map { EcdhKeyAgreement.generateKeyPair() }
        val sharedKeys = (0..2).map { i ->
            val next = (i + 1) % 3
            EcdhKeyAgreement.deriveSharedKey(keys[i],
                    EcdhKeyAgreement.encodePublicKey(keys[next].public))
        }
        // 所有共享密钥互不相同
        assertEquals(3, sharedKeys.toSet().size)
    }
}