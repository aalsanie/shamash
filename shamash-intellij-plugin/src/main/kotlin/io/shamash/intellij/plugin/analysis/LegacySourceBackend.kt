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
package io.shamash.intellij.plugin.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiDocumentManager
import io.shamash.artifacts.baseline.BaselineConfig
import io.shamash.intellij.plugin.ShamashPluginInfo
import io.shamash.psi.core.config.ValidationSeverity
import io.shamash.psi.core.scan.ShamashProjectScanRunner
import io.shamash.psi.core.scan.ShamashScanOptions
import java.io.StringReader

/**
 * Temporary compatibility backend until source-fact extraction is wired in.
 * Runs the existing explicit policy scan; does not claim source-graph coverage.
 */
internal class LegacySourceBackend(
    private val project: Project,
    private val yaml: String,
    private val runner: ShamashProjectScanRunner,
) : IdeAnalysisCoordinator.Backend {
    override fun analyze(
        request: AnalysisRequest,
        indicator: ProgressIndicator,
    ): AnalysisOutcome<IdeAnalysisCoordinator.SourcePresentation> {
        indicator.checkCanceled()
        if (!hasProductionSources(indicator)) {
            return AnalysisOutcome.Unavailable(
                "No supported Java/Kotlin production sources were found",
                AnalysisCoverage(eligibleFiles = 0, analyzedFiles = 0, reason = "NO_SOURCE"),
            )
        }
        val result =
            runner.scanProject(
                project = project,
                configReader = StringReader(yaml),
                options =
                    ShamashScanOptions.ide(
                        toolVersion = ShamashPluginInfo.version,
                        baseline = BaselineConfig.OFF,
                        exportReports = false,
                    ),
                indicator = indicator,
            )
        indicator.checkCanceled()
        val presentation =
            IdeAnalysisCoordinator.SourcePresentation(
                findings = result.findings.toList(),
                validationErrors = result.configErrors.toList(),
                engineErrors = result.engineErrors.toList(),
            )
        val diagnostics =
            result.configErrors.map { "${it.path}: ${it.message}" } +
                result.engineErrors.map { "${it.fileId}: ${it.message}" }
        // Unknown counts stay unknown; this old runner cannot prove a source-graph absence.
        val coverage =
            AnalysisCoverage(reason = "Legacy project-policy analysis; source relationship coverage unavailable")
        return if (!result.isComplete || result.configErrors.any { it.severity == ValidationSeverity.ERROR }) {
            AnalysisOutcome.Partial(
                presentation,
                coverage,
                diagnostics.ifEmpty { listOf("Incomplete source analysis") },
            )
        } else {
            AnalysisOutcome.Complete(presentation, coverage)
        }
    }

    private fun hasProductionSources(indicator: ProgressIndicator): Boolean {
        var found = false
        ApplicationManager.getApplication().runReadAction {
            val index = ProjectFileIndex.getInstance(project)
            index.iterateContent { file ->
                indicator.checkCanceled()
                if (!file.isDirectory && file.extension in setOf("java", "kt") && index.isInSourceContent(file)) {
                    found = true
                    false
                } else {
                    true
                }
            }
        }
        return found
    }
}
