/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
 *
 * Author: @aalsanie
 *
 * Plugin: https://plugins.jetbrains.com/plugin/29504-shamash
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
package io.shamash.export.api

import io.shamash.export.writers.html.HtmlExporter
import io.shamash.export.writers.json.JsonExporter
import io.shamash.export.writers.sarif.SarifExporter
import io.shamash.export.writers.xml.XmlExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportersTest {
    @Test
    fun create_emptyFormats_returnsEmptyList() {
        val exporters = Exporters.create(emptySet())
        assertTrue(exporters.isEmpty())
    }

    @Test
    fun create_returnsExportersInFormatEntriesOrder() {
        // Format.entries order is: JSON, SARIF, XML, HTML
        val exporters =
            Exporters.create(
                setOf(
                    Exporters.Format.XML,
                    Exporters.Format.JSON,
                    Exporters.Format.HTML,
                    Exporters.Format.SARIF,
                ),
            )

        assertEquals(4, exporters.size)
        assertTrue(exporters[0] is JsonExporter)
        assertTrue(exporters[1] is SarifExporter)
        assertTrue(exporters[2] is XmlExporter)
        assertTrue(exporters[3] is HtmlExporter)
    }

    @Test
    fun create_filtersOnlyRequestedFormats_preservingEntriesOrder() {
        val exporters = Exporters.create(setOf(Exporters.Format.XML, Exporters.Format.JSON))

        assertEquals(2, exporters.size)
        assertTrue(exporters[0] is JsonExporter)
        assertTrue(exporters[1] is XmlExporter)
    }

    @Test
    fun createAll_returnsAllExporters() {
        val exporters = Exporters.createAll()

        assertEquals(4, exporters.size)
        assertTrue(exporters[0] is JsonExporter)
        assertTrue(exporters[1] is SarifExporter)
        assertTrue(exporters[2] is XmlExporter)
        assertTrue(exporters[3] is HtmlExporter)
    }
}
