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

object Exporters {
    enum class Format {
        JSON,
        SARIF,
        XML,
        HTML,
    }

    /**
     * Create exporters for the given set of formats.
     */
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
