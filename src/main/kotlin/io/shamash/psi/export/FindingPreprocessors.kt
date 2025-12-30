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
 * Helpers to wire finding preprocessors without leaking internal engine types
 * into the exporter public surface.
 */
object FindingPreprocessors {
    /**
     * Create a [FindingPreprocessor] from a function that needs both base path and findings.
     */
    fun from(projectScoped: (projectBasePath: Path, findings: List<Finding>) -> List<Finding>): FindingPreprocessor =
        FindingPreprocessor { projectBasePath, findings ->
            projectScoped(projectBasePath, findings)
        }

    /**
     * Create a [FindingPreprocessor] from a function that only needs findings.
     *
     * The projectBasePath is intentionally ignored.
     */
    fun from(findingsOnly: (findings: List<Finding>) -> List<Finding>): FindingPreprocessor =
        FindingPreprocessor { _, findings ->
            findingsOnly(findings)
        }
}
