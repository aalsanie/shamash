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

import io.shamash.psi.engine.FindingSeverity

/**
 * Exporter-facing finding model (schema v1).
 *
 * Contract:
 * - filePath must be project-root-relative and normalized to forward slashes.
 * - ordering must be deterministic at export time (see report building/exporters).
 * - fingerprint is a deterministic SHA-256 hex string used for baseline mode.
 */
data class ExportedFinding(
    val ruleId: String,
    val message: String,
    val severity: FindingSeverity,
    val filePath: String,
    val classFqn: String?,
    val memberName: String?,
    val fingerprint: String,
)
