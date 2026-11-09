/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
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
package io.shamash.intellij.plugin.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShamashSourceMigrationStateTest {
    @Test
    fun `legacy project gets a one-time versioned acknowledgement`() {
        val state = ShamashSourceMigrationState()
        assertEquals(0, state.getState().acknowledgedVersion)
        assertTrue(state.acknowledge())
        assertEquals(1, state.getState().acknowledgedVersion)
        assertFalse(state.acknowledge())
        assertEquals(1, state.getState().acknowledgedVersion)
    }

    @Test
    fun `reopened and future-version workspaces never downgrade`() {
        val state = ShamashSourceMigrationState()
        state.loadState(ShamashSourceMigrationState.State(acknowledgedVersion = 1))
        assertFalse(state.acknowledge())
        state.loadState(ShamashSourceMigrationState.State(acknowledgedVersion = 3))
        assertFalse(state.acknowledge())
        assertEquals(3, state.getState().acknowledgedVersion)
    }

    @Test
    fun `fresh state is not shared with persisted input objects`() {
        val loaded = ShamashSourceMigrationState.State()
        val state = ShamashSourceMigrationState()
        state.loadState(loaded)
        assertTrue(state.acknowledge())
        assertEquals(0, loaded.acknowledgedVersion)
    }
}
