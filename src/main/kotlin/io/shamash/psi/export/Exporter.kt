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

import io.shamash.psi.export.schema.v1.model.ExportedReport
import java.nio.file.Path

/**
 * Common exporter interface.
 *
 * Each exporter:
 * - consumes a fully-built ExportedReport
 * - writes artifacts into the provided output directory
 * - no reordering, stable formatting
 * - must not mutate the report
 */
interface Exporter {
    /**
     * Export the given report into the output directory.
     */
    fun export(
        report: ExportedReport,
        outputDir: Path,
    )
}
