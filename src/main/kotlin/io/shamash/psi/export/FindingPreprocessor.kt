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

import io.shamash.psi.engine.Finding
import java.nio.file.Path

/**
 * Export-layer preprocessing hook for findings prior to report generation.
 *
 * This is intentionally owned by the export layer to avoid coupling baseline/engine to export.
 *
 * Typical implementations:
 * - exceptions/ignore suppression (provided by caller: CLI/plugin)
 * - baseline suppression (provided by baseline layer via BaselineCoordinator)
 *
 * Requirements:
 * - Must be deterministic (same inputs -> same outputs).
 * - Must not mutate the incoming list instances in-place; return a new list or the same reference.
 * - Must not perform IO.
 */
fun interface FindingPreprocessor {
    fun process(
        projectBasePath: Path,
        findings: List<Finding>,
    ): List<Finding>
}
