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
package io.shamash.intellij.plugin

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Element

/** The plugin's registered API surface must not re-enable retired mutating actions or registries. */
class PluginRegistrationTest {
    private val xml =
        requireNotNull(javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")).use { it.readBytes() }

    @Test
    fun `only current actions and four compatibility ids are registered`() {
        val document = parseDescriptor()
        val actions = document.getElementsByTagName("action")
        val ids =
            (0 until actions.length).map { index ->
                (actions.item(index) as Element).getAttribute("id")
            }
        assertEquals(
            setOf(
                "io.shamash.open",
                "io.shamash.analyze",
                "io.shamash.intellij.plugin.psi.ui.actions.OpenPsiDashboardAction",
                "io.shamash.intellij.plugin.psi.ui.actions.RunPsiScanAction",
                "io.shamash.asm.openDashboard",
                "io.shamash.asm.runScan",
            ),
            ids.toSet(),
        )
        assertEquals(ids.size, ids.toSet().size)
        for (index in 0 until actions.length) {
            val action = actions.item(index) as Element
            if (action.getAttribute("id") !in setOf("io.shamash.open")) {
                assertEquals(0, action.getElementsByTagName("add-to-group").length)
            }
        }
    }

    @Test
    fun `one source tool window defaults to bottom with original public identity`() {
        val document = parseDescriptor()
        val windows = document.getElementsByTagName("toolWindow")
        assertEquals(1, windows.length)
        val window = windows.item(0) as Element
        assertEquals("Shamash", window.getAttribute("id"))
        assertEquals("bottom", window.getAttribute("anchor"))
        assertEquals("io.shamash.intellij.plugin.ui.ShamashToolWindowFactory", window.getAttribute("factoryClass"))
        assertEquals(0, document.getElementsByTagName("extensionPoint").length)
        assertEquals(0, document.getElementsByTagName("applicationService").length)
    }

    @Test
    fun `descriptor explains source scope instead of old build and fix claims`() {
        val description = parseDescriptor().getElementsByTagName("description").item(0).textContent
        assertTrue(description.contains("source dependencies"))
        assertTrue(description.contains("not evaluated"))
        assertFalse(description.contains("and fixes"))
        assertFalse(description.contains("Build Analysis"))
    }

    private fun parseDescriptor() =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml))
}
