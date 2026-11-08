/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectSourceAnalyzerTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    override fun setUp() {
        super.setUp()
        PsiTestUtil.addSourceRoot(myFixture.module, myFixture.tempDirFixture.findOrCreateDir("src/main/java"))
        PsiTestUtil.addSourceRoot(myFixture.module, myFixture.tempDirFixture.findOrCreateDir("src/main/kotlin"))
    }

    fun testNestedJavaGenericPreservesInnerTargetWithPreciseEvidence() {
        java("Repository.java", "package demo; public class Repository {}")
        java(
            "Service.java",
            "package demo; class Service { java.util.List<java.util.Map<String, ? extends Repository[]>> repos; }",
        )
        val snapshot = analyze()
        assertEquals(2, snapshot.coverage.analyzedFiles)
        assertFalse(snapshot.coverage.truncated)
        val edge =
            snapshot.references.single {
                it.target is SourceTarget.Internal &&
                    (it.target as SourceTarget.Internal).entity.signature == "demo.Repository" &&
                    it.kind == SourceDependencyKind.TYPE
            }
        assertTrue(edge.evidence.offset > 0)
        assertEquals("demo.Repository", (edge.target as SourceTarget.Internal).entity.signature)
        assertTrue(snapshot.coverage.resolvedOccurrences >= 1)
    }

    fun testJavaToKotlinAndKotlinToJavaResolveDeclarations() {
        java("JavaTarget.java", "package demo; public class JavaTarget { public void ping() {} }")
        kotlin("KotlinTarget.kt", "package demo\nclass KotlinTarget { fun pong(j: JavaTarget) { j.ping() } }")
        java(
            "JavaCaller.java",
            "package demo; class JavaCaller { void call(KotlinTarget k) { k.pong(new JavaTarget()); } }",
        )
        val snapshot = analyze()
        val edges = snapshot.references.filter { it.target is SourceTarget.Internal }
        assertTrue(
            edges.any {
                it.from.signature.contains("JavaCaller") &&
                    (it.target as SourceTarget.Internal).entity.signature.contains("KotlinTarget")
            },
            snapshot.diagnostics.toString(),
        )
        assertTrue(
            edges.any {
                it.from.signature.contains("KotlinTarget") &&
                    (it.target as SourceTarget.Internal).entity.signature.contains("JavaTarget")
            },
            snapshot.diagnostics.toString(),
        )
    }

    fun testKotlinTopLevelDeclarationsRetainPackageAndFileIdentity() {
        java("Repository.java", "package demo; public class Repository {}")
        kotlin(
            "Functions.kt",
            "package demo\nfun helper(repo: Repository) = repo\nval singleton = Repository()",
        )
        kotlin(
            "Caller.kt",
            "package demo\nclass Caller { fun use() { helper(Repository()); val p = singleton } }",
        )
        val snapshot = analyze()
        val functions = snapshot.entities.filter { it.id.declarationKind == SourceEntityKind.FUNCTION }
        assertTrue(functions.any { it.id.signature.contains("demo.helper") && it.packageName == "demo" })
        val properties = snapshot.entities.filter { it.id.declarationKind == SourceEntityKind.PROPERTY }
        assertTrue(properties.any { it.id.signature == "demo.singleton" && it.packageName == "demo" })
        val callerTargets = snapshot.references.filter { it.from.signature.contains("Caller") }.map { it.target }
        assertTrue(
            callerTargets.any {
                it is SourceTarget.Internal && it.entity.declarationKind == SourceEntityKind.FUNCTION &&
                    it.entity.signature.contains("demo.helper")
            },
            callerTargets.toString(),
        )
        assertTrue(
            callerTargets.any {
                it is SourceTarget.Internal && it.entity.declarationKind == SourceEntityKind.PROPERTY &&
                    it.entity.signature == "demo.singleton"
            },
            callerTargets.toString(),
        )
    }

    fun testKotlinObjectsExtensionsCompanionsAndAliasesRemainDistinct() {
        java("Repository.java", "package demo; public class Repository {}")
        kotlin(
            "Declarations.kt",
            """
            package demo
            typealias Db = Repository
            object Registry { val repo: Db = Repository() }
            fun Repository.extension(): Repository = this
            class Factory {
                companion object { fun make(): Repository = Repository() }
            }
            """.trimIndent(),
        )
        kotlin(
            "Use.kt",
            """
            package demo
            class Use {
                fun call() {
                    val db: Db = Registry.repo
                    db.extension()
                    Factory.make()
                }
            }
            """.trimIndent(),
        )
        val snapshot = analyze()
        assertTrue(
            snapshot.entities.any {
                it.id.declarationKind == SourceEntityKind.TYPE_ALIAS && it.id.signature == "demo.Db"
            },
        )
        assertTrue(
            snapshot.entities.any {
                it.id.declarationKind == SourceEntityKind.OBJECT && it.id.signature.contains("Registry")
            },
        )
        assertTrue(
            snapshot.entities.any {
                it.id.declarationKind == SourceEntityKind.FUNCTION && it.id.signature.contains("extension")
            },
        )
        assertTrue(
            snapshot.entities.any {
                it.id.signature.contains("Factory.Companion.make")
            },
        )
        assertTrue(
            snapshot.references.any {
                it.from.signature.contains("Use") &&
                    (it.target as? SourceTarget.Internal)?.entity?.signature?.contains("demo.Repository") == true
            },
            snapshot.diagnostics.toString(),
        )
    }

    fun testGenericKotlinTypeAliasesRetainAllResolvedArguments() {
        java("Repository.java", "package demo; public class Repository {}")
        kotlin("Aliases.kt", "package demo\ntypealias Repositories = List<Repository>")
        kotlin(
            "AliasUser.kt",
            "package demo\nclass AliasUser { val items: Repositories = emptyList() }",
        )
        val result = analyze()
        assertTrue(
            result.references.any {
                it.from.signature == "demo.AliasUser.items" &&
                    (it.target as? SourceTarget.Internal)?.entity?.signature == "demo.Repository"
            },
            result.references.toString(),
        )
    }

    fun testTestRootAndNestedRootsAreExcludedOrDeduplicated() {
        val nested = myFixture.tempDirFixture.findOrCreateDir("src/main/java/demo")
        PsiTestUtil.addSourceRoot(myFixture.module, nested)
        val tests = myFixture.tempDirFixture.findOrCreateDir("src/test/java")
        PsiTestUtil.addSourceRoot(myFixture.module, tests, true)
        java("Production.java", "package demo; public class Production {}")
        myFixture.addFileToProject("src/test/java/demo/TestOnly.java", "package demo; class TestOnly {}")
        val snapshot = analyze()
        assertEquals(1, snapshot.coverage.eligibleFiles)
        assertEquals(1, snapshot.entities.count { it.displayName.contains("Production") })
        assertTrue(snapshot.entities.none { it.displayName.contains("TestOnly") })
        assertEquals(
            1,
            snapshot.entities
                .map { it.id.fileUrl }
                .distinct()
                .size,
        )
    }

    fun testUnresolvedJavaReferenceIsPartialNotClean() {
        java("MissingCaller.java", "package demo; class MissingCaller { MissingTarget dep; }")
        val snapshot = analyze()
        assertFalse(snapshot.complete)
        assertTrue(snapshot.coverage.unresolvedOccurrences > 0)
        assertTrue(snapshot.references.any { it.target is SourceTarget.Unresolved })
        assertTrue(snapshot.diagnostics.any { it.phase == "resolve" })
    }

    fun testUnsavedEditorChangesAffectEdgesWithoutSavingDiskBytes() {
        java("Target.java", "package demo; class Target {}")
        val file = java("Caller.java", "package demo; class Caller {}")
        val path =
            java.nio.file.Path
                .of(file.virtualFile.path)
        val bytes =
            java.nio.file.Files
                .readAllBytes(path)
                .toList()
        val before = analyze()
        assertTrue(before.relationships.isEmpty(), before.relationships.toString())
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file.virtualFile))
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("package demo; class Caller { Target target; }")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val after = analyze()
        assertTrue(after.relationships.any { it.target.signature == "demo.Target" }, after.diagnostics.toString())
        assertEquals(
            bytes,
            java.nio.file.Files
                .readAllBytes(path)
                .toList(),
        )
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
    }

    fun testGraphAssemblyStageIsReachedForSelectedProductionSources() {
        java("Repository.java", "package demo; class Repository {}")
        var graphAssemblyCalls = 0
        val result =
            ProjectSourceAnalyzer(project).analyze(ProgressIndicatorBase()) {
                graphAssemblyCalls++
            }
        assertEquals(1, graphAssemblyCalls)
        assertEquals(1, result.coverage.analyzedFiles)
    }

    fun testFileLimitMarksSnapshotIncomplete() {
        java("A.java", "package demo; class A {}")
        java("B.java", "package demo; class B {}")
        val snapshot =
            ProjectSourceAnalyzer(project, SourceAnalysisProfile(maxFiles = 1)).analyze(ProgressIndicatorBase())
        assertFalse(snapshot.complete)
        assertTrue(snapshot.coverage.truncated)
        assertEquals(1, snapshot.coverage.eligibleFiles)
        assertTrue(snapshot.diagnostics.any { it.phase == "limit" })
    }

    private fun analyze(): ProjectSourceSnapshot = ProjectSourceAnalyzer(project).analyze(ProgressIndicatorBase())

    private fun java(
        name: String,
        source: String,
    ) = myFixture.addFileToProject("src/main/java/demo/$name", source)

    private fun kotlin(
        name: String,
        source: String,
    ) = myFixture.addFileToProject("src/main/kotlin/demo/$name", source)
}
