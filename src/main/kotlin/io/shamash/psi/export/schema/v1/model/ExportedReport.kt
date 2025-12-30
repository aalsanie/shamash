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
package io.shamash.psi.export.schema.v1.model

/**
 * Exporter-facing report model (schema v1).
 *
 * This is the single canonical artifact consumed by all exporters
 * JSON, SARIF, XML, HTML, CLI, CI, dashboard.
 *
 * Contract:
 * - basePath is the normalized absolute project root (forward slashes).
 * - findings are already filtered (exceptions applied), normalized and
 *   deterministically ordered before export.
 * - exporters must not reorder or mutate findings.
 */
data class ExportedReport(
    val tool: ToolMetadata,
    val project: ProjectMetadata,
    val findings: List<ExportedFinding>,
)

/**
 * Metadata describing tool invocation.
 */
data class ToolMetadata(
    val name: String,
    val version: String,
    val schemaVersion: String,
    val generatedAtEpochMillis: Long,
)

/**
 * Metadata describing the analyzed project.
 */
data class ProjectMetadata(
    val name: String,
    val basePath: String,
)
