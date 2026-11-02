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

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import io.shamash.psi.core.source.ProjectSourceAnalyzer
import io.shamash.psi.core.source.SourceRevisionChangedException

/** No config, build invocation, disk write, policy rules or precomputed ASM facts. */
internal class ProjectSourceBackend(
    private val project: Project,
    private val coordinator: IdeAnalysisCoordinator,
) : IdeAnalysisCoordinator.Backend {
    override fun analyze(
        request: AnalysisRequest,
        indicator: ProgressIndicator,
    ): AnalysisOutcome<IdeAnalysisCoordinator.SourcePresentation> {
        val snapshot =
            try {
                ProjectSourceAnalyzer(project).analyze(indicator)
            } catch (_: SourceRevisionChangedException) {
                return AnalysisOutcome.Unavailable(
                    "Sources changed during analysis; analyze again",
                    AnalysisCoverage(reason = "REVISION_CHANGED"),
                )
            }
        indicator.checkCanceled()
        if (snapshot.coverage.eligibleFiles == 0 && !snapshot.coverage.truncated) {
            return AnalysisOutcome.Unavailable(
                "No supported production Java/Kotlin source files",
                AnalysisCoverage(
                    eligibleFiles = 0,
                    analyzedFiles = 0,
                    failedFiles = 0,
                    reason = "NO_SOURCE",
                ),
            )
        }
        if (!coordinator.graphStarted(request)) {
            throw com.intellij.openapi.progress.ProcessCanceledException()
        }
        val coverage =
            AnalysisCoverage(
                eligibleFiles = snapshot.coverage.eligibleFiles,
                analyzedFiles = snapshot.coverage.analyzedFiles,
                failedFiles = snapshot.coverage.failedFiles,
                resolvedOccurrences = snapshot.coverage.resolvedOccurrences,
                unresolvedOccurrences = snapshot.coverage.unresolvedOccurrences,
                reason = if (snapshot.coverage.truncated) "Internal limit or revision change" else null,
            )
        val payload = IdeAnalysisCoordinator.SourcePresentation(sourceFacts = snapshot)
        val diagnostics = snapshot.diagnostics.map { "${it.fileUrl ?: "project"}: ${it.message}" }
        return if (snapshot.complete) {
            AnalysisOutcome.Complete(payload, coverage)
        } else {
            AnalysisOutcome.Partial(payload, coverage, diagnostics.ifEmpty { listOf("Source resolution incomplete") })
        }
    }
}
