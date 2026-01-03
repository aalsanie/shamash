/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
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
package io.shamash.psi.export

import io.shamash.psi.export.html.HtmlExporter
import io.shamash.psi.export.json.JsonExporter
import io.shamash.psi.export.sarif.SarifExporter
import io.shamash.psi.export.xml.XmlExporter

object DefaultExportPipelineFactory {
    /**
     * This method intentionally depends only on exporter-layer types.
     * callers plugin UI, CLI, inspection integration are responsible for building preprocessors
     * using their available engine/schema context.
     */
    fun create(
        exceptionsPreprocessor: FindingPreprocessor? = null,
        baselinePreprocessor: FindingPreprocessor? = null,
    ): ExportOrchestrator {
        val preprocessors = ArrayList<FindingPreprocessor>(2)

        if (exceptionsPreprocessor != null) {
            preprocessors.add(exceptionsPreprocessor)
        }
        if (baselinePreprocessor != null) {
            preprocessors.add(baselinePreprocessor)
        }

        val reportBuilder = ReportBuilder(preprocessors)

        val exporters =
            listOf(
                JsonExporter(),
                SarifExporter(),
                XmlExporter(),
                HtmlExporter(),
            )

        return ExportOrchestrator(
            reportBuilder = reportBuilder,
            exporters = exporters,
        )
    }
}
