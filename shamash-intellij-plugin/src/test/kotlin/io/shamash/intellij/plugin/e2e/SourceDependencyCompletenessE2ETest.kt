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
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import io.shamash.intellij.plugin.ui.source.SourceEvidence
import io.shamash.intellij.plugin.ui.source.SourceNavigationService
import io.shamash.psi.core.source.ProjectSourceAnalyzer
import io.shamash.psi.core.source.ProjectSourceSnapshot
import io.shamash.psi.core.source.SourceDependency
import io.shamash.psi.core.source.SourceDependencyKind
import io.shamash.psi.core.source.SourceEntityKind
import io.shamash.psi.core.source.SourceGraphAnalyzer
import io.shamash.psi.core.source.SourceGraphSnapshot
import io.shamash.psi.core.source.SourcePackageId
import io.shamash.psi.core.source.SourceTarget
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceDependencyCompletenessE2ETest : ShamashPluginE2eTestBase() {
    override fun setUp() {
        super.setUp()
        PsiTestUtil.addSourceRoot(myModule, myFixture.tempDirFixture.findOrCreateDir("src/main/java"))
        PsiTestUtil.addSourceRoot(myModule, myFixture.tempDirFixture.findOrCreateDir("src/main/kotlin"))
    }

    fun testAnonymousJavaCallbackClosesRealPackageCycleWithNavigableWitness() {
        myFixture.addFileToProject("src/main/java/a/Callback.java", "package a; public interface Callback { void run(); }")
        myFixture.addFileToProject(
            "src/main/java/a/A.java",
            """
                package a;
                public class A {
                    Callback callback = new Callback() {
                        @Override public void run() { b.B.ping(); }
                    };
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/b/B.java",
            "package b; public class B { a.A back; public static void ping() {} }",
        )
        val (snapshot, graph) = analyze()
        assertTrue(snapshot.complete, snapshot.diagnostics.toString())
        assertTrue(graph.complete, graph.diagnostics.toString())
        assertEquals(1, graph.cycles.size)
        assertEquals(setOf("a", "b"), graph.cycles.single().members.map { it.packageName }.toSet())
        val edge = dependency(snapshot, "a", "b", "ping")
        assertEquals(SourceEntityKind.FIELD, edge.from.declarationKind)
        assertEquals("a.A#callback", edge.from.signature)
        assertEquals(SourceDependencyKind.CALL, edge.kind)
        assertNavigates(edge)
    }

    fun testNestedAnonymousAndLocalJavaBodiesUseNearestRepresentableOwner() {
        myFixture.addFileToProject(
            "src/main/java/a/A.java",
            """
                package a;
                public class A {
                    public void call() {
                        class Local {
                            void work() {
                                Runnable r = new Runnable() {
                                    @Override public void run() { b.B.ping(); }
                                };
                                r.run();
                            }
                        }
                        new Local().work();
                    }
                    public void unrelated() { int x = 1; }
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/b/B.java",
            "package b; public class B { a.A reverse; public static void ping() {} }",
        )
        val (snapshot, graph) = analyze()
        assertEquals(1, graph.cycles.size)
        val edge = dependency(snapshot, "a", "b", "ping")
        assertEquals(SourceEntityKind.METHOD, edge.from.declarationKind)
        assertTrue(edge.from.signature.startsWith("a.A#call("), edge.from.toString())
        assertTrue(snapshot.entities.none { it.id.signature.contains("Local#") })
        assertTrue(snapshot.relationships.none { it.from.signature.contains("unrelated") && it.target.signature == "b.B" })
        assertNavigates(edge)
    }

    fun testJavaInitializersAndAnonymousFieldDeclarationsKeepDependencies() {
        myFixture.addFileToProject(
            "src/main/java/a/A.java",
            """
                package a;
                public class A {
                    static { b.B.ping(); }
                    int count = b.B.answer();
                    Runnable action = new Runnable() {
                        @Override public void run() { b.B.ping(); }
                        b.B extra;
                    };
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/b/B.java",
            "package b; public class B { public static void ping() {} public static int answer() { return 1; } }",
        )
        val (snapshot, _) = analyze()
        assertTrue(snapshot.complete, snapshot.diagnostics.toString())
        val edges = snapshot.references.filter { it.target is SourceTarget.Internal && targetPackage(it) == "b" }
        assertTrue(edges.any { it.from.signature == "a.A" })
        assertTrue(edges.any { it.from.signature == "a.A#count" })
        assertTrue(edges.any { it.from.signature == "a.A#action" })
        assertTrue(edges.none { it.from.signature.contains("null") })
    }

    fun testKotlinResolvedExtensionOperatorCreatesCycleAndNavigableCall() {
        val source =
            myFixture.addFileToProject(
                "src/main/kotlin/a/Token.kt",
                """
                    package a
                    import b.plus
                    class Token
                    fun use(left: Token, right: Token): Token = left + right
                """.trimIndent(),
            )
        myFixture.addFileToProject(
            "src/main/kotlin/b/Operators.kt",
            """
                package b
                operator fun a.Token.plus(other: a.Token): a.Token = this
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val operator = requireNotNull(PsiTreeUtil.findChildOfType(source, KtOperationReferenceExpression::class.java))
        assertNotNull(operator.references.firstOrNull()?.resolve())

        val (snapshot, graph) = analyze()
        assertTrue(snapshot.complete, snapshot.diagnostics.toString())
        assertEquals(1, graph.cycles.size)
        assertEquals(setOf("a", "b"), graph.cycles.single().members.map { it.packageName }.toSet())
        val edge = dependency(snapshot, "a", "b", "plus")
        assertEquals(SourceDependencyKind.CALL, edge.kind)
        assertNavigates(edge)
    }

    fun testKotlinIndexedGetAndSetResolveExtensionFunctions() {
        val source =
            myFixture.addFileToProject(
                "src/main/kotlin/a/Token.kt",
                """
                    package a
                    import b.get
                    import b.set
                    class Token
                    fun inspect(token: Token): Token = token[0]
                    fun update(token: Token) { token[0] = token }
                """.trimIndent(),
            )
        myFixture.addFileToProject(
            "src/main/kotlin/b/Operators.kt",
            """
                package b
                operator fun a.Token.get(index: Int): a.Token = this
                operator fun a.Token.set(index: Int, value: a.Token) {}
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val indexed = PsiTreeUtil.findChildrenOfType(source, KtArrayAccessExpression::class.java)
        assertEquals(2, indexed.size)
        assertTrue(indexed.all { it.references.any { reference -> reference.resolve() != null } })

        val (snapshot, graph) = analyze()
        val edges = snapshot.references.filter { fromPackage(it) == "a" && targetPackage(it) == "b" }
        assertTrue(edges.any { it.target.toString().contains(".get(") }, edges.toString())
        assertTrue(edges.any { it.target.toString().contains(".set(") }, edges.toString())
        assertEquals(1, graph.cycles.size)
        assertTrue(snapshot.complete, snapshot.diagnostics.toString())
        edges.filter { it.kind == SourceDependencyKind.CALL }.forEach(::assertNavigates)
    }

    fun testKotlinLambdaLocalFunctionAndObjectExpressionRetainLexicalOwner() {
        myFixture.addFileToProject(
            "src/main/kotlin/a/Owner.kt",
            """
                package a
                import b.Remote
                class Owner {
                    val callback = object {
                        fun execute() { Remote.ping() }
                    }
                    fun run() {
                        fun local() { Remote.ping() }
                        val action = { Remote.ping() }
                        local()
                        action()
                    }
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/kotlin/b/Remote.kt",
            """
                package b
                object Remote { fun ping() {} }
            """.trimIndent(),
        )
        val (snapshot, _) = analyze()
        assertTrue(snapshot.complete, snapshot.diagnostics.toString())
        val edges = snapshot.references.filter { fromPackage(it) == "a" && targetPackage(it) == "b" }
        assertTrue(edges.any { it.from.signature.contains("callback") }, edges.toString())
        assertTrue(edges.any { it.from.signature.contains("Owner.run(") }, edges.toString())
        assertTrue(snapshot.entities.none { it.id.signature.contains(".local(") })
        assertTrue(edges.all { it.evidence.length > 0 })
    }

    fun testLocalParametersAndUnusedImportsDoNotManufactureEdgesOrDiagnostics() {
        myFixture.addFileToProject(
            "src/main/kotlin/a/Local.kt",
            """
                package a
                import b.Unused
                class Local
                fun apply(value: Local): Local {
                    val copy = value
                    fun choose(parameter: Local): Local = parameter
                    return choose(copy)
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject("src/main/kotlin/b/Unused.kt", "package b; class Unused")
        val (snapshot, graph) = analyze()
        assertTrue(snapshot.complete, snapshot.diagnostics.toString())
        assertTrue(graph.cycles.isEmpty())
        assertTrue(snapshot.relationships.none { targetPackage(it) == "b" })
        assertTrue(snapshot.references.none { it.target is SourceTarget.Unresolved })
        assertTrue(snapshot.entities.none { it.id.signature.contains("copy") || it.id.signature.contains("choose") })
    }

    fun testKotlinExplicitCallAndOperatorSyntaxHaveSamePackageDirection() {
        myFixture.addFileToProject(
            "src/main/kotlin/a/Token.kt",
            """
                package a
                import b.plus
                class Token
                fun operatorUse(left: Token, right: Token) = left + right
                fun explicitUse(left: Token, right: Token) = left.plus(right)
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/kotlin/b/Operators.kt",
            """
                package b
                operator fun a.Token.plus(other: a.Token): a.Token = this
            """.trimIndent(),
        )
        val (snapshot, _) = analyze()
        assertTrue(snapshot.complete, snapshot.diagnostics.toString())
        val edges = snapshot.references.filter { fromPackage(it) == "a" && targetPackage(it) == "b" }
        assertTrue(edges.any { it.from.signature.contains("operatorUse") })
        assertTrue(edges.any { it.from.signature.contains("explicitUse") })
        assertEquals(2, edges.filter { it.target.toString().contains(".plus(") }.size)
    }

    fun testGenuineMissingJavaTypeLeavesIncompleteCoverage() {
        myFixture.addFileToProject(
            "src/main/java/a/Owner.java",
            "package a; public class Owner { missing.Nonexistent value; }",
        )
        val (snapshot, graph) = analyze()
        assertFalse(snapshot.complete)
        assertFalse(graph.complete)
        assertTrue(snapshot.diagnostics.isNotEmpty())
        assertTrue(snapshot.references.any { it.target is SourceTarget.Unresolved })
        assertFalse(graph.cycleAbsenceEstablished)
    }

    private fun dependency(
        snapshot: ProjectSourceSnapshot,
        fromPackage: String,
        toPackage: String,
        method: String,
    ): SourceDependency =
        assertNotNull(
            snapshot.references.firstOrNull {
                fromPackage(it) == fromPackage &&
                    targetPackage(it) == toPackage &&
                    it.kind == SourceDependencyKind.CALL &&
                    it.target.toString().contains(method)
            },
            snapshot.references.toString(),
        )

    private fun fromPackage(reference: SourceDependency): String =
        reference.from.fileUrl.let { url ->
            when {
                url.contains("/a/") -> "a"
                url.contains("/b/") -> "b"
                else -> ""
            }
        }

    private fun targetPackage(reference: SourceDependency): String =
        (reference.target as? SourceTarget.Internal)?.entity?.fileUrl?.let { url ->
            when {
                url.contains("/a/") -> "a"
                url.contains("/b/") -> "b"
                else -> ""
            }
        }.orEmpty()

    private fun targetPackage(relationship: io.shamash.psi.core.source.SourceRelationship): String =
        when {
            relationship.target.fileUrl.contains("/a/") -> "a"
            relationship.target.fileUrl.contains("/b/") -> "b"
            else -> ""
        }

    private fun assertNavigates(reference: SourceDependency) {
        assertTrue(reference.evidence.length > 0, reference.toString())
        val file = assertNotNull(VirtualFileManager.getInstance().findFileByUrl(reference.evidence.fileUrl))
        assertTrue(reference.evidence.offset >= 0)
        assertTrue(reference.evidence.offset + reference.evidence.length <= file.length)
        val target = reference.target as? SourceTarget.Internal
        assertNotNull(target, reference.toString())
        assertTrue(
            SourceNavigationService(project).open(
                SourceEvidence(
                    reference.evidence,
                    reference.from,
                    "Source reference",
                    reference.target,
                    reference.kind,
                ),
            ),
            reference.toString(),
        )
    }

    private fun analyze(): Pair<ProjectSourceSnapshot, SourceGraphSnapshot> {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val snapshot = ProjectSourceAnalyzer(project).analyze(ProgressIndicatorBase())
        return snapshot to SourceGraphAnalyzer().analyze(snapshot)
    }
}
