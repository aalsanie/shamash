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
package io.shamash.intellij.plugin.e2e

import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import io.shamash.intellij.plugin.analysis.AnalysisOutcome
import io.shamash.intellij.plugin.analysis.AnalysisRequest
import io.shamash.intellij.plugin.analysis.IdeAnalysisCoordinator
import io.shamash.intellij.plugin.analysis.ProjectSourceBackend
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import org.jetbrains.kotlin.config.SourceKotlinRootType
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectSourceBackendScopeTest : ShamashPluginE2eTestBase() {
    fun testUncertainKotlinSourceIsUnavailableNotClean() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/kotlin")
        PsiTestUtil.addSourceRoot(myModule, root, SourceKotlinRootType, JavaSourceRootProperties("", false))
        myFixture.addFileToProject("src/main/kotlin/demo/Unknown.kt", "package demo\nclass Unknown")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val outcome = analyze()
        assertTrue(outcome is AnalysisOutcome.Unavailable, outcome.toString())
        val coverage = (outcome as AnalysisOutcome.Unavailable).coverage
        assertEquals("UNKNOWN_PLATFORM", coverage.reason)
        assertEquals(0, coverage.eligibleFiles)
        assertEquals(1, coverage.excludedFiles)
        assertEquals(1, coverage.excludedReasons["UNKNOWN_PLATFORM"])
    }

    fun testNoProductionSourceRemainsExplicitlyUnavailable() {
        val outcome = analyze()
        assertTrue(outcome is AnalysisOutcome.Unavailable, outcome.toString())
        val coverage = (outcome as AnalysisOutcome.Unavailable).coverage
        assertEquals("NO_SOURCE", coverage.reason)
        assertEquals(0, coverage.eligibleFiles)
        assertTrue(coverage.excludedReasons.isEmpty())
    }

    private fun analyze(): AnalysisOutcome<IdeAnalysisCoordinator.SourcePresentation> =
        ProjectSourceBackend(project, IdeAnalysisCoordinator.getInstance(project))
            .analyze(AnalysisRequest(1, 0, 0), ProgressIndicatorBase())
}
