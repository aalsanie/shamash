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
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory UI state for Shamash PSI tool window.
 *
 * Contract:
 * - Project-level service (supports multiple open projects).
 * - Stores only the most recent results (scan + validation + export).
 * - Updates are atomic to avoid panels observing mixed state.
 */
@Service(Service.Level.PROJECT)
class ShamashPsiUiStateService {
    data class Snapshot(
        val findings: List<Finding> = emptyList(),
        val validationErrors: List<ValidationError> = emptyList(),
        val exportDir: Path? = null,
        val exportedReport: ExportedReport? = null,
    )

    private val ref = AtomicReference(Snapshot())

    val lastFindings: List<Finding>
        get() = ref.get().findings

    val lastValidationErrors: List<ValidationError>
        get() = ref.get().validationErrors

    val lastExportDir: Path?
        get() = ref.get().exportDir

    val lastExportedReport: ExportedReport?
        get() = ref.get().exportedReport

    fun updateFromScan(
        findings: List<Finding>,
        validationErrors: List<ValidationError>,
        exportDir: Path?,
        exportedReport: ExportedReport?,
    ) {
        // Defensive copies so UI never observes a mutable backing list.
        ref.set(
            Snapshot(
                findings = findings.toList(),
                validationErrors = validationErrors.toList(),
                exportDir = exportDir,
                exportedReport = exportedReport,
            ),
        )
    }

    fun updateFindings(findings: List<Finding>) {
        ref.updateAndGet { s ->
            s.copy(
                findings = findings.toList(),
                // successful scan/refresh typically means old errors are not relevant anymore
                validationErrors = emptyList(),
            )
        }
    }

    fun updateValidation(errors: List<ValidationError>) {
        ref.updateAndGet { s -> s.copy(validationErrors = errors.toList()) }
    }

    fun updateExport(
        outputDir: Path?,
        report: ExportedReport?,
    ) {
        ref.updateAndGet { s ->
            s.copy(
                exportDir = outputDir,
                exportedReport = report,
            )
        }
    }

    fun clearExport() {
        ref.updateAndGet { s -> s.copy(exportDir = null, exportedReport = null) }
    }

    companion object {
        fun getInstance(project: Project): ShamashPsiUiStateService = project.getService(ShamashPsiUiStateService::class.java)
    }
}
