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
package io.shamash.psi.core.facts

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import io.shamash.psi.core.facts.model.v1.DependencyKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FactExtractorCacheTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    fun testUnchangedJavaFileReusesSuccessfulFacts() {
        val caller = javaFile("Caller.java", "package example; class Caller { void call() {} }")

        val first = FactExtractor.extractResult(caller)
        assertTrue(first.errors.isEmpty(), first.errors.toString())
        assertSame(first, FactExtractor.extractResult(caller))
    }

    fun testJavaCallerRefreshesWhenReferencedDeclarationChanges() {
        val target = javaFile("Target.java", "package example; class Target { void ping() {} }")
        val caller = javaFile("Caller.java", "package example; class Caller { void call(Target target) { target.ping(); } }")

        val first = FactExtractor.extractResult(caller)
        assertTrue(first.errors.isEmpty(), first.errors.toString())
        assertTrue(
            first.facts.dependencies.any {
                it.kind == DependencyKind.METHOD_CALL &&
                    it.toTypeFqn == "example.Target" &&
                    it.detail == "call:ping"
            },
            first.facts.dependencies.toString(),
        )
        assertSame(first, FactExtractor.extractResult(caller))

        replaceSource(target, "package example; class Target { void pong() {} }")

        val refreshed = FactExtractor.extractResult(caller)
        assertNotSame(first, refreshed)
        assertTrue(refreshed.errors.isEmpty(), refreshed.errors.toString())
        assertFalse(
            refreshed.facts.dependencies.any {
                it.kind == DependencyKind.METHOD_CALL &&
                    it.toTypeFqn == "example.Target" &&
                    it.detail == "call:ping"
            },
            refreshed.facts.dependencies.toString(),
        )
    }

    fun testKotlinCallerRefreshesWhenAnotherKotlinSourceChanges() {
        val target =
            kotlinFile(
                "Target.kt",
                "package example\nclass Target { fun ping() {} }",
            )
        val caller =
            kotlinFile(
                "Caller.kt",
                "package example\nclass Caller { fun call(target: Target) { target.ping() } }",
            )

        val first = FactExtractor.extractResult(caller)
        assertTrue(first.errors.isEmpty(), first.errors.toString())
        assertTrue(first.facts.classes.any { it.fqName == "example.Caller" })
        assertSame(first, FactExtractor.extractResult(caller))

        replaceSource(target, "package example\nclass Target { fun pong() {} }")

        val refreshed = FactExtractor.extractResult(caller)
        assertNotSame(first, refreshed)
        assertTrue(refreshed.facts.classes.any { it.fqName == "example.Caller" })
        assertTrue(refreshed.errors.isEmpty(), refreshed.errors.toString())
    }

    fun testEditingAnalyzedFileInvalidatesCachedFacts() {
        val caller = javaFile("Before.java", "package example; class Before {}")
        val first = FactExtractor.extractResult(caller)
        assertSame(first, FactExtractor.extractResult(caller))

        replaceSource(caller, "package example; class After {}")

        val refreshed = FactExtractor.extractResult(caller)
        assertNotSame(first, refreshed)
        assertEquals(listOf("example.After"), refreshed.facts.classes.map { it.fqName })
    }

    fun testAddingUnrelatedSourceInvalidatesCachedFacts() {
        val caller = javaFile("Caller.java", "package example; class Caller {}")
        val first = FactExtractor.extractResult(caller)
        assertSame(first, FactExtractor.extractResult(caller))

        javaFile("NewFile.java", "package example; class NewFile {}")

        val refreshed = FactExtractor.extractResult(caller)
        assertNotSame(first, refreshed)
        assertEquals(first.facts.classes, refreshed.facts.classes)
    }

    fun testChangingProjectRootsInvalidatesCachedFacts() {
        val caller = javaFile("Caller.java", "package example; class Caller {}")
        val additionalRoot = myFixture.tempDirFixture.findOrCreateDir("additional-source-root")
        val first = FactExtractor.extractResult(caller)
        assertSame(first, FactExtractor.extractResult(caller))

        val tracker = ProjectRootModificationTracker.getInstance(project)
        val countBefore = tracker.modificationCount
        ApplicationManager.getApplication().runWriteAction {
            val model = ModuleRootManager.getInstance(myFixture.module).modifiableModel
            try {
                model.contentEntries.first().addSourceFolder(additionalRoot, false)
                model.commit()
            } finally {
                if (!model.isDisposed) model.dispose()
            }
        }

        assertTrue(tracker.modificationCount > countBefore)
        val refreshed = FactExtractor.extractResult(caller)
        assertNotSame(first, refreshed)
        assertEquals(first.facts.classes, refreshed.facts.classes)
    }

    private fun javaFile(
        name: String,
        source: String,
    ): PsiFile = myFixture.addFileToProject("src/main/java/example/$name", source)

    private fun kotlinFile(
        name: String,
        source: String,
    ): PsiFile = myFixture.addFileToProject("src/main/kotlin/example/$name", source)

    private fun replaceSource(
        file: PsiFile,
        text: String,
    ) {
        val manager = PsiDocumentManager.getInstance(project)
        val document = requireNotNull(manager.getDocument(file))
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(text)
            manager.commitDocument(document)
        }
    }
}
