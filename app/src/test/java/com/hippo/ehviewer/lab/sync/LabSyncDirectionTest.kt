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

package com.hippo.ehviewer.lab.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议 §5.30.1：方向与对端自动角色，以及同对端会话替换。
 */
class LabSyncDirectionTest {

    @Test
    fun peerRoleSwitchesAutomatically() {
        assertEquals(LabSyncDirection.ROLE_SERVE, LabSyncDirection.PULL.peerRole())
        assertEquals(LabSyncDirection.ROLE_PULL, LabSyncDirection.PUSH.peerRole())
        assertEquals(LabSyncDirection.ROLE_BIDIRECTIONAL, LabSyncDirection.BIDIRECTIONAL.peerRole())
    }

    @Test
    fun directionWireParsing() {
        assertEquals(LabSyncDirection.PULL, LabSyncDirection.fromWire("pull"))
        assertEquals(LabSyncDirection.PUSH, LabSyncDirection.fromWire("push"))
        assertEquals(LabSyncDirection.BIDIRECTIONAL, LabSyncDirection.fromWire("bidirectional"))
        assertEquals(null, LabSyncDirection.fromWire("sideways"))
    }

    @Test
    fun newSessionSupersedesRunningOneForSamePeer() {
        val store = LabSyncSessionStore.getInstance()
        val first = store.create("peer-1", "Phone B", LabSyncDirection.PULL, listOf("downloads"))
        val second = store.create("peer-1", "Phone B", LabSyncDirection.PUSH, listOf("downloads"))

        assertEquals("superseded", first.state)
        assertEquals("running", second.state)
        assertEquals(LabSyncDirection.ROLE_PULL, second.role)
        assertNotNull(store.find(second.id))

        store.finish(second.id, "completed", "test")
        assertEquals("completed", store.find(second.id)?.state)
        assertTrue(store.running().isEmpty())
        LabSyncSessionStore.resetForTest()
    }
}
