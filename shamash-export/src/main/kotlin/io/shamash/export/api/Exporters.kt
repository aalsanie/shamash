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

object Exporters {
    enum class Format {
        JSON,
        SARIF,
        XML,
        HTML,
    }

    fun create(formats: Set<Format>): List<Exporter> {
        if (formats.isEmpty()) return emptyList()
        return Format.entries
            .asSequence()
            .filter { it in formats }
            .map {
                when (it) {
                    Format.JSON -> JsonExporter()
                    Format.SARIF -> SarifExporter()
                    Format.XML -> XmlExporter()
                    Format.HTML -> HtmlExporter()
                }
            }.toList()
    }

    fun createAll(): List<Exporter> = create(Format.entries.toSet())
}
