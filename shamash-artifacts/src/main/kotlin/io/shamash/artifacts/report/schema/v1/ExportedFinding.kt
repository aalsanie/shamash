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
package io.shamash.artifacts.report.schema.v1

import io.shamash.artifacts.contract.FindingSeverity

/**
 * [filePath] must be project-relative with forward slashes; [fingerprint] is SHA-256 hex.
 * Exporters must preserve the report builder's deterministic ordering.
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
