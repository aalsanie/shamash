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
package io.shamash.psi.ui.actions

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.shamash.psi.config.ValidationError
import io.shamash.psi.engine.Finding
import io.shamash.psi.export.schema.v1.model.ExportedReport
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class ShamashPsiUiStateService(
    private val project: Project,
) {
    @Volatile var lastFindings: List<Finding> = emptyList()
        private set

    @Volatile var lastValidationErrors: List<ValidationError> = emptyList()
        private set

    @Volatile var lastExportDir: Path? = null
        private set

    @Volatile var lastExportedReport: ExportedReport? = null
        private set

    fun updateFindings(findings: List<Finding>) {
        lastFindings = findings
    }

    fun updateValidation(errors: List<ValidationError>) {
        lastValidationErrors = errors
    }

    fun updateExport(
        outputDir: Path?,
        report: ExportedReport?,
    ) {
        lastExportDir = outputDir
        lastExportedReport = report
    }

    companion object {
        fun getInstance(project: Project): ShamashPsiUiStateService = project.getService(ShamashPsiUiStateService::class.java)
    }
}
