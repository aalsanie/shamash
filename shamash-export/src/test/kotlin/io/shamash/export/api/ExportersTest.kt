/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
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
