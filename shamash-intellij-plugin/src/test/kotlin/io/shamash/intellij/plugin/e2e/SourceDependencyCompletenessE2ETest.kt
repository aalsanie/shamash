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

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
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
import io.shamash.psi.core.source.SourceDependencyExtractor
import io.shamash.psi.core.source.SourceDependencyKind
import io.shamash.psi.core.source.SourceEntityKind
import io.shamash.psi.core.source.SourceGraphAnalyzer
import io.shamash.psi.core.source.SourceGraphSnapshot
import io.shamash.psi.core.source.SourcePackageId
import io.shamash.psi.core.source.SourceTarget
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import kotlin.test.assertFailsWith
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
                    public void run() { b.B.ping(); }
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
        assertEquals(
            setOf("a", "b"),
            graph.cycles
                .single()
                .members
                .map { it.packageName }
                .toSet(),
        )
        val edge = dependency(snapshot, "a", "b", "ping")
        assertEquals(SourceEntityKind.FIELD, edge.from.declarationKind)
        assertEquals("a.A#callback", edge.from.signature)
        assertEquals(SourceDependencyKind.CALL, edge.kind)
        assertTrue(
            snapshot.references.any {
                (it.target as? SourceTarget.Internal)?.entity?.signature == "a.Callback" &&
                    it.kind == SourceDependencyKind.TYPE
            },
            "An anonymous implementation must preserve the referenced interface type",
        )
        assertTrue(
            snapshot.references.none {
                (it.target as? SourceTarget.Internal)?.entity?.signature?.contains("B#ping(") == true &&
                    it.kind == SourceDependencyKind.CONSTRUCTOR
            },
            "Nested method calls must not inherit their enclosing constructor's kind: ${snapshot.references.filter {
                (it.target as? SourceTarget.Internal)?.entity?.signature?.contains(
                    "B#ping(",
                ) == true
            }}",
        )
        assertNavigates(edge)
    }

    fun testConcreteJavaConstructorRemainsDistinctFromMethodCall() {
        myFixture.addFileToProject(
            "src/main/java/a/A.java",
            "package a; public class A { b.B instance = new b.B(); void run() { b.B.ping(); } }",
        )
        myFixture.addFileToProject(
            "src/main/java/b/B.java",
            "package b; public class B { public static void ping() {} }",
        )
        val (snapshot, _) = analyze()
        val toB =
            snapshot.references.filter {
                (it.target as? SourceTarget.Internal)?.entity?.signature == "b.B"
            }
        assertTrue(
            toB.any { it.kind == SourceDependencyKind.CONSTRUCTOR },
            "The concrete new-expression must retain constructor semantics: $toB",
        )
        val ping = dependency(snapshot, "a", "b", "ping")
        assertEquals(SourceDependencyKind.CALL, ping.kind)
    }

    fun testNestedAnonymousAndLocalJavaBodiesUseNearestRepresentableOwner() {
        myFixture.addFileToProject(
            "src/main/java/a/A.java",
            """
            package a;
            public class A {
                interface LocalCallback { void run(); }
                public void call() {
                    class Local {
                        void work() {
                            LocalCallback r = new LocalCallback() {
                                public void run() { b.B.ping(); }
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

    fun testAnonymousJavaMembersAndParametersStayLocal() {
        myFixture.addFileToProject(
            "src/main/java/a/A.java",
            """
            package a;
            public class A {
                interface Callback { void run(); }
                Callback callback = new Callback() {
                    public void run() { helper(); }
                    void helper() {}
                };
                void use(A parameter) {
                    A local = parameter;
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject("src/main/java/b/Unused.java", "package b; public class Unused {}")
        val (snapshot, graph) = analyze()
        assertTrue(
            snapshot.references.none { it.target is SourceTarget.Unresolved },
            "Local Java members and parameters cannot become unresolved architectural targets: ${snapshot.references}; ${snapshot.diagnostics}",
        )
        assertTrue(snapshot.relationships.none { targetPackage(it) == "b" })
        assertTrue(snapshot.entities.none { it.id.signature.contains("helper(") })
        assertTrue(graph.cycles.isEmpty())
    }

    fun testJavaInitializersAndAnonymousFieldDeclarationsKeepDependencies() {
        myFixture.addFileToProject(
            "src/main/java/a/A.java",
            """
            package a;
            public class A {
                interface Callback { void run(); }
                static { b.B.ping(); }
                int count = b.B.answer();
                Callback action = new Callback() {
                    public void run() { b.B.ping(); }
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

    fun testJavaAnnotationsInheritanceConstructorsAndTypesRemainDistinct() {
        myFixture.addFileToProject("src/main/java/b/Marker.java", "package b; public @interface Marker {}")
        myFixture.addFileToProject("src/main/java/b/Base.java", "package b; public class Base {}")
        myFixture.addFileToProject("src/main/java/b/Contract.java", "package b; public interface Contract {}")
        myFixture.addFileToProject("src/main/java/b/Result.java", "package b; public class Result {}")
        myFixture.addFileToProject(
            "src/main/java/a/Owner.java",
            """
            package a;
            @b.Marker
            public class Owner extends b.Base implements b.Contract {
                b.Result result = new b.Result();
                public b.Result create() { return new b.Result(); }
            }
            """.trimIndent(),
        )
        val (snapshot, _) = analyze()
        val references = snapshot.references.filter { fromPackage(it) == "a" && targetPackage(it) == "b" }

        fun has(
            signature: String,
            kind: SourceDependencyKind,
        ): Boolean =
            references.any {
                (it.target as? SourceTarget.Internal)?.entity?.signature == signature && it.kind == kind
            }
        assertTrue(has("b.Marker", SourceDependencyKind.ANNOTATION), references.toString())
        assertTrue(has("b.Base", SourceDependencyKind.INHERITANCE), references.toString())
        assertTrue(has("b.Contract", SourceDependencyKind.INHERITANCE), references.toString())
        assertTrue(has("b.Result", SourceDependencyKind.TYPE), references.toString())
        assertTrue(has("b.Result", SourceDependencyKind.CONSTRUCTOR), references.toString())
        assertTrue(snapshot.complete, snapshot.diagnostics.toString())
    }

    fun testKotlinAnnotationsInheritancePropertyAccessAndTypesRemainDistinct() {
        myFixture.addFileToProject(
            "src/main/kotlin/b/Types.kt",
            """
            package b
            annotation class Marker
            interface Contract
            class Value
            object Registry { val value: Value? = null }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/kotlin/a/Owner.kt",
            """
            package a
            @b.Marker
            class Owner : b.Contract {
                val value: b.Value? = b.Registry.value
            }
            """.trimIndent(),
        )
        val (snapshot, _) = analyze()
        val references = snapshot.references.filter { fromPackage(it) == "a" && targetPackage(it) == "b" }

        fun has(
            signature: String,
            kind: SourceDependencyKind,
        ): Boolean =
            references.any {
                (it.target as? SourceTarget.Internal)?.entity?.signature == signature && it.kind == kind
            }
        assertTrue(has("b.Marker", SourceDependencyKind.ANNOTATION), references.toString())
        assertTrue(has("b.Contract", SourceDependencyKind.INHERITANCE), references.toString())
        assertTrue(has("b.Value", SourceDependencyKind.TYPE), references.toString())
        assertTrue(has("b.Registry.value", SourceDependencyKind.ACCESS), references.toString())
    }

    fun testJavaResolvedDeclarationsOutsideSourceScopeRemainExternal() {
        val source =
            myFixture.addFileToProject(
                "src/main/java/a/Owner.java",
                "package a; public class Owner { b.Remote field; void call() { b.Remote.ping(); } }",
            )
        myFixture.addFileToProject(
            "src/main/java/b/Remote.java",
            "package b; public class Remote { public static void ping() {} }",
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val location = SourceDependencyExtractor.SourceLocation("main", "src/main/java", source.virtualFile.url)
        val extractor = SourceDependencyExtractor { null }
        val extracted = extractor.extract(source, location)
        val external = extracted.references.mapNotNull { it.target as? SourceTarget.External }
        assertTrue(external.any { it.name == "b.Remote" }, extracted.references.toString())
        assertTrue(external.any { it.name == "b.Remote.ping" }, extracted.references.toString())
        assertTrue(
            extracted.references.none { it.target is SourceTarget.Unresolved },
            "Excluding a resolved target from scope cannot make it unresolved: ${extracted.diagnostics}",
        )
    }

    fun testKotlinResolvedDeclarationsOutsideSourceScopeRemainExternal() {
        val source =
            myFixture.addFileToProject(
                "src/main/kotlin/a/Owner.kt",
                """
                package a
                import b.Remote
                import b.ping
                class Owner {
                    val remote: Remote? = null
                    fun call() { ping() }
                }
                """.trimIndent(),
            )
        myFixture.addFileToProject(
            "src/main/kotlin/b/Remote.kt",
            """
            package b
            class Remote
            fun ping() {}
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val location = SourceDependencyExtractor.SourceLocation("main", "src/main/kotlin", source.virtualFile.url)
        val extractor = SourceDependencyExtractor { null }
        var extracted: SourceDependencyExtractor.FileResult? = null
        DumbService.getInstance(project).runReadActionInSmartMode {
            extracted = extractor.extract(source, location)
        }
        val external = requireNotNull(extracted).references.mapNotNull { it.target as? SourceTarget.External }
        assertTrue(external.any { it.name == "b.Remote" }, extracted.references.toString())
        assertTrue(external.any { it.name == "b.ping" }, extracted.references.toString())
    }

    fun testResolutionScopeFailureIsDiagnosedWithoutReportingCleanCoverage() {
        val source =
            myFixture.addFileToProject(
                "src/main/java/a/Owner.java",
                "package a; public class Owner { void call() { b.Remote.ping(); } }",
            )
        myFixture.addFileToProject(
            "src/main/java/b/Remote.java",
            "package b; public class Remote { public static void ping() {} }",
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val location = SourceDependencyExtractor.SourceLocation("main", "src/main/java", source.virtualFile.url)
        val extracted =
            SourceDependencyExtractor { target ->
                if (target.name == "Remote.java") error("scope unavailable")
                location
            }.extract(source, location)
        assertTrue(
            extracted.diagnostics.any { it.phase == "resolve" && it.message.contains("scope unavailable") },
            extracted.diagnostics.toString(),
        )
        assertTrue(
            extracted.references.any { it.target is SourceTarget.Unresolved },
            extracted.references.toString(),
        )
    }

    fun testResolutionCancellationIsPropagated() {
        val source =
            myFixture.addFileToProject(
                "src/main/java/a/Owner.java",
                "package a; public class Owner { void call() { b.Remote.ping(); } }",
            )
        myFixture.addFileToProject(
            "src/main/java/b/Remote.java",
            "package b; public class Remote { public static void ping() {} }",
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val location = SourceDependencyExtractor.SourceLocation("main", "src/main/java", source.virtualFile.url)
        val extractor =
            SourceDependencyExtractor { target ->
                if (target.name == "Remote.java") throw ProcessCanceledException()
                location
            }
        assertFailsWith<ProcessCanceledException> { extractor.extract(source, location) }
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
        assertEquals(1, graph.cycles.size)
        assertEquals(
            setOf("a", "b"),
            graph.cycles
                .single()
                .members
                .map { it.packageName }
                .toSet(),
        )
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
        assertTrue(graph.cycles.isEmpty())
        assertTrue(snapshot.relationships.none { targetPackage(it) == "b" })
        assertTrue(
            snapshot.references.none { it.target is SourceTarget.Unresolved },
            "Unexpected unresolved references: ${snapshot.references.filter {
                it.target is SourceTarget.Unresolved
            }}; diagnostics: ${snapshot.diagnostics}",
        )
        assertTrue(
            snapshot.entities.none { it.id.signature.contains("copy") || it.id.signature.contains("choose") },
            "Local declarations appeared as graph entities: ${snapshot.entities.map { it.id.signature }}",
        )
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
        val edges = snapshot.references.filter { fromPackage(it) == "a" && targetPackage(it) == "b" }
        assertTrue(edges.any { it.from.signature.contains("operatorUse") })
        assertTrue(edges.any { it.from.signature.contains("explicitUse") })
        assertEquals(2, edges.filter { it.target.toString().contains(".plus(") }.size)
    }

    fun testKotlinInvokeExtensionRetainsCrossPackageCall() {
        val source =
            myFixture.addFileToProject(
                "src/main/kotlin/a/Token.kt",
                """
                package a
                import b.invoke
                class Token
                fun use(token: Token): Token = token()
                fun explicit(token: Token): Token = token.invoke()
                """.trimIndent(),
            )
        myFixture.addFileToProject(
            "src/main/kotlin/b/Invoke.kt",
            """
            package b
            operator fun a.Token.invoke(): a.Token = this
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val call =
            PsiTreeUtil
                .findChildrenOfType(source, org.jetbrains.kotlin.psi.KtCallExpression::class.java)
                .single { it.text == "token()" }
        assertTrue(call.references.any { it.resolve() != null }, "Fixture must expose an implicit invoke reference")
        val (snapshot, graph) = analyze()
        assertEquals(1, graph.cycles.size)
        val calls =
            snapshot.references.filter {
                fromPackage(it) == "a" && targetPackage(it) == "b" &&
                    it.target.toString().contains(".invoke(")
            }
        assertEquals(2, calls.size)
        calls.forEach(::assertNavigates)
    }

    fun testLocalKotlinClassMethodsRetainEnclosingFunctionAndDoNotInventTargets() {
        myFixture.addFileToProject(
            "src/main/kotlin/a/Owner.kt",
            """
            package a
            import b.Remote
            class Owner {
                fun run() {
                    class Local {
                        fun call() { Remote.ping() }
                    }
                    Local().call()
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/kotlin/b/Remote.kt",
            """
            package b
            object Remote {
                val back: a.Owner? = null
                fun ping() {}
            }
            """.trimIndent(),
        )
        val (snapshot, graph) = analyze()
        assertEquals(1, graph.cycles.size)
        val edge = dependency(snapshot, "a", "b", "ping")
        assertTrue(edge.from.signature.contains("Owner.run("), edge.from.toString())
        assertTrue(
            snapshot.entities.none { it.id.signature.contains("Local") },
            "Local class declarations appeared as graph entities: ${snapshot.entities.map { it.id.signature }}",
        )
        assertTrue(
            snapshot.references.none { it.target is SourceTarget.Unresolved },
            "Unexpected unresolved references: ${snapshot.references.filter {
                it.target is SourceTarget.Unresolved
            }}; diagnostics: ${snapshot.diagnostics}",
        )
        assertNavigates(edge)
    }

    fun testUnresolvedKotlinOperatorDoesNotProduceFalseCompleteResult() {
        myFixture.addFileToProject(
            "src/main/kotlin/a/Token.kt",
            """
            package a
            class Token
            fun use(left: Token, right: Token) = left + right
            """.trimIndent(),
        )
        val (snapshot, graph) = analyze()
        assertFalse(snapshot.complete)
        assertFalse(graph.cycleAbsenceEstablished)
        assertTrue(snapshot.diagnostics.any { it.phase == "resolve" })
        assertTrue(snapshot.references.any { it.target is SourceTarget.Unresolved })
    }

    fun testChainedKotlinAliasesPreserveResolvedProjectDependency() {
        myFixture.addFileToProject(
            "src/main/kotlin/a/Owner.kt",
            """
            package a
            import b.Target
            typealias TargetAlias = Target
            typealias ChainedAlias = TargetAlias
            class Owner { val dependency: ChainedAlias? = null }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/kotlin/b/Target.kt",
            "package b\nclass Target(val owner: a.Owner? = null)",
        )
        val (snapshot, graph) = analyze()
        val edges =
            snapshot.references.filter {
                fromPackage(it) == "a" && targetPackage(it) == "b"
            }
        assertTrue(
            edges.any { (it.target as? SourceTarget.Internal)?.entity?.signature == "b.Target" },
            "Aliases must resolve to the real project declaration: $edges; diagnostics: ${snapshot.diagnostics}",
        )
        assertTrue(
            graph.cycles.any { it.members.map { member -> member.packageName }.toSet() == setOf("a", "b") },
            "Resolved alias dependencies must retain the package cycle: ${graph.cycles}",
        )
        edges.first().let(::assertNavigates)
    }

    fun testCyclicKotlinAliasesRemainIncompleteWithoutFabricatedEntities() {
        myFixture.addFileToProject(
            "src/main/kotlin/a/Aliases.kt",
            """
            package a
            typealias First = Second
            typealias Second = First
            class Owner { val value: First? = null }
            """.trimIndent(),
        )
        val (snapshot, graph) = analyze()
        assertFalse(snapshot.complete)
        assertFalse(graph.cycleAbsenceEstablished)
        assertTrue(
            snapshot.references.any { it.target is SourceTarget.Unresolved },
            "Cyclic aliases must not be treated as resolved: ${snapshot.references}; ${snapshot.diagnostics}",
        )
    }

    fun testUnresolvedTypeAliasCannotBeReportedAsComplete() {
        myFixture.addFileToProject(
            "src/main/kotlin/a/Aliases.kt",
            """
            package a
            typealias Alias = MissingType
            class UsesAlias { val value: Alias? = null }
            """.trimIndent(),
        )
        val (snapshot, graph) = analyze()
        assertFalse(snapshot.complete)
        assertFalse(graph.cycleAbsenceEstablished)
        assertTrue(snapshot.references.any { it.target is SourceTarget.Unresolved })
        assertTrue(snapshot.diagnostics.any { it.phase == "resolve" })
    }

    fun testKotlinConstructorAndMemberCallHaveDistinctKinds() {
        myFixture.addFileToProject(
            "src/main/kotlin/a/Owner.kt",
            """
            package a
            class Owner {
                fun create(): b.Target {
                    val target = b.Target()
                    target.ping()
                    return target
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/kotlin/b/Target.kt",
            "package b\nclass Target { fun ping() {} }",
        )
        val (snapshot, _) = analyze()
        val references = snapshot.references.filter { fromPackage(it) == "a" && targetPackage(it) == "b" }
        assertTrue(
            references.any {
                (it.target as? SourceTarget.Internal)?.entity?.signature == "b.Target" &&
                    it.kind == SourceDependencyKind.CONSTRUCTOR
            },
            references.toString(),
        )
        assertTrue(
            references.any {
                it.target.toString().contains("Target.ping(") &&
                    it.kind == SourceDependencyKind.CALL
            },
            references.toString(),
        )
        assertTrue(snapshot.complete, snapshot.diagnostics.toString())
    }

    fun testKotlinExtensionPropertyPreservesReceiverAndAccess() {
        myFixture.addFileToProject(
            "src/main/kotlin/a/Owner.kt",
            """
            package a
            import b.flag
            class Owner
            fun read(owner: Owner): Owner = owner.flag
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/kotlin/b/Extensions.kt",
            """
            package b
            val a.Owner.flag: a.Owner get() = this
            """.trimIndent(),
        )
        val (snapshot, graph) = analyze()
        val references = snapshot.references.filter { fromPackage(it) == "a" && targetPackage(it) == "b" }
        assertTrue(
            references.any {
                it.target.toString().contains("flag") &&
                    it.kind == SourceDependencyKind.ACCESS
            },
            references.toString(),
        )
        assertTrue(
            graph.cycles.any { cycle ->
                cycle.members.map { it.packageName }.toSet() == setOf("a", "b")
            },
            graph.cycles.toString(),
        )
        assertTrue(snapshot.complete, snapshot.diagnostics.toString())
    }

    fun testJavaLocalBindingsAndUnusedImportsDoNotManufactureDependencies() {
        myFixture.addFileToProject(
            "src/main/java/a/Owner.java",
            """
            package a;
            import b.Unused;
            public class Owner {
                void use(Owner argument) {
                    Owner local = argument;
                    Owner copy = local;
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject("src/main/java/b/Unused.java", "package b; public class Unused {}")
        val (snapshot, graph) = analyze()
        assertTrue(snapshot.complete, snapshot.diagnostics.toString())
        assertTrue(snapshot.relationships.none { targetPackage(it) == "b" })
        assertTrue(snapshot.references.none { it.target is SourceTarget.Unresolved })
        assertTrue(graph.cycles.isEmpty())
    }

    fun testJavaExplicitSuperConstructorAndStaticCallRetainKinds() {
        myFixture.addFileToProject(
            "src/main/java/b/Base.java",
            """
            package b;
            public class Base {
                public Base() {}
                public static void ping() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/a/Derived.java",
            """
            package a;
            public class Derived extends b.Base {
                public Derived() { super(); }
                void use() { b.Base.ping(); }
            }
            """.trimIndent(),
        )
        val (snapshot, _) = analyze()
        val references = snapshot.references.filter { fromPackage(it) == "a" && targetPackage(it) == "b" }
        assertTrue(
            references.any {
                (it.target as? SourceTarget.Internal)?.entity?.signature == "b.Base" &&
                    it.kind == SourceDependencyKind.INHERITANCE
            },
            references.toString(),
        )
        assertTrue(
            references.any {
                it.target.toString().contains("Base#Base(") &&
                    it.kind == SourceDependencyKind.CONSTRUCTOR
            },
            references.toString(),
        )
        assertTrue(
            references.any {
                it.target.toString().contains("Base#ping(") &&
                    it.kind == SourceDependencyKind.CALL
            },
            references.toString(),
        )
        assertTrue(snapshot.complete, snapshot.diagnostics.toString())
    }

    fun testJavaLocalClassInsideAnonymousCallbackUsesFieldOwner() {
        myFixture.addFileToProject(
            "src/main/java/a/Owner.java",
            """
            package a;
            public class Owner {
                interface Task { void run(); }
                Task task = new Task() {
                    public void run() {
                        class Local {
                            b.Remote remote;
                            void execute() { b.Remote.ping(); }
                        }
                        new Local().execute();
                    }
                };
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/b/Remote.java",
            """
            package b;
            public class Remote {
                public a.Owner owner;
                public static void ping() {}
            }
            """.trimIndent(),
        )
        val (snapshot, graph) = analyze()
        val edges = snapshot.references.filter { fromPackage(it) == "a" && targetPackage(it) == "b" }
        assertTrue(edges.any { it.from.signature == "a.Owner#task" && it.kind == SourceDependencyKind.TYPE }, edges.toString())
        assertTrue(edges.any { it.from.signature == "a.Owner#task" && it.kind == SourceDependencyKind.CALL }, edges.toString())
        assertTrue(snapshot.entities.none { it.id.signature.contains("Local") })
        assertTrue(graph.cycles.any { it.members.map { member -> member.packageName }.toSet() == setOf("a", "b") })
        assertTrue(snapshot.complete, snapshot.diagnostics.toString())
    }

    fun testKotlinLocalClassesAndObjectsInsideLambdasUseMethodOwner() {
        myFixture.addFileToProject(
            "src/main/kotlin/a/Owner.kt",
            """
            package a
            class Owner {
                fun execute() {
                    val first = {
                        class Local {
                            fun run() { b.Remote.ping() }
                        }
                        Local().run()
                    }
                    val second = {
                        object {
                            fun run() { b.Remote.ping() }
                        }.run()
                    }
                    first()
                    second()
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/kotlin/b/Remote.kt",
            "package b\nobject Remote { val back: a.Owner? = null; fun ping() {} }",
        )
        val (snapshot, graph) = analyze()
        val edges = snapshot.references.filter { fromPackage(it) == "a" && targetPackage(it) == "b" }
        assertTrue(edges.count { it.kind == SourceDependencyKind.CALL } >= 2, edges.toString())
        assertTrue(edges.all { it.from.signature.contains("a.Owner.execute(") }, edges.toString())
        assertTrue(snapshot.entities.none { it.id.signature.contains("Local") || it.id.signature.contains(".run(") })
        assertTrue(graph.cycles.any { it.members.map { member -> member.packageName }.toSet() == setOf("a", "b") })
    }

    fun testKotlinNestedMembersOfAnonymousObjectRemainLexical() {
        myFixture.addFileToProject(
            "src/main/kotlin/a/Owner.kt",
            """
            package a
            class Owner {
                val handler = object {
                    class Local {
                        fun execute() { b.Remote.ping() }
                    }
                    val member: Local = Local()
                    fun run() { member.execute() }
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/kotlin/b/Remote.kt",
            "package b\nobject Remote { val back: a.Owner? = null; fun ping() {} }",
        )
        val (snapshot, graph) = analyze()
        val edges = snapshot.references.filter { fromPackage(it) == "a" && targetPackage(it) == "b" }
        assertTrue(edges.any { it.from.signature == "a.Owner.handler" && it.kind == SourceDependencyKind.CALL }, edges.toString())
        assertTrue(snapshot.entities.none { it.id.signature.contains("Local") || it.id.signature.contains("handler.run(") })
        assertTrue(graph.cycles.any { it.members.map { member -> member.packageName }.toSet() == setOf("a", "b") })
    }

    fun testKotlinShadowedParametersAndLocalFunctionsDoNotInventTargets() {
        myFixture.addFileToProject(
            "src/main/kotlin/a/Owner.kt",
            """
            package a
            class Owner(val value: b.Target) {
                fun apply(value: b.Target): b.Target {
                    val local = value
                    fun choose(value: b.Target): b.Target = value
                    return choose(local)
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/kotlin/b/Target.kt",
            "package b\nclass Target(val owner: a.Owner? = null)",
        )
        val (snapshot, graph) = analyze()
        assertTrue(snapshot.complete, snapshot.diagnostics.toString())
        assertTrue(snapshot.references.none { it.target is SourceTarget.Unresolved }, snapshot.references.toString())
        assertTrue(snapshot.entities.none { it.id.signature.contains(".choose(") || it.id.signature.contains(".local") })
        assertTrue(
            snapshot.references.any { fromPackage(it) == "a" && targetPackage(it) == "b" },
            snapshot.references.toString(),
        )
        assertTrue(graph.cycles.any { it.members.map { member -> member.packageName }.toSet() == setOf("a", "b") })
    }

    fun testUnresolvedJavaReferenceWithinLocalClassCannotProduceCompleteGraph() {
        myFixture.addFileToProject(
            "src/main/java/a/Owner.java",
            """
            package a;
            public class Owner {
                void execute() {
                    class Local {
                        void run() { missing.Remote.ping(); }
                    }
                    new Local().run();
                }
            }
            """.trimIndent(),
        )
        val (snapshot, graph) = analyze()
        assertTrue(!snapshot.complete)
        assertTrue(!graph.cycleAbsenceEstablished)
        assertTrue(
            snapshot.references.any {
                it.from.signature.contains("a.Owner#execute(") && it.target is SourceTarget.Unresolved
            },
            snapshot.references.toString(),
        )
        assertTrue(snapshot.diagnostics.any { it.phase == "resolve" }, snapshot.diagnostics.toString())
    }

    fun testJavaPackageAnnotationWithoutDeclarationOwnerIsDiagnosed() {
        myFixture.addFileToProject(
            "src/main/java/b/Marker.java",
            "package b; public @interface Marker {}",
        )
        val source =
            myFixture.addFileToProject(
                "src/main/java/a/package-info.java",
                "@b.Marker\npackage a;",
            )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val location = SourceDependencyExtractor.SourceLocation("main", "src/main/java", source.virtualFile.url)
        val extracted = SourceDependencyExtractor { location }.extract(source, location)
        assertTrue(
            extracted.diagnostics.any { it.phase == "owner" && it.message.contains("No supported declaration owns reference") },
            extracted.diagnostics.toString(),
        )
        assertTrue(extracted.references.none { it.target is SourceTarget.Internal }, extracted.references.toString())
    }

    fun testUnresolvedKotlinIndexedAccessCannotEstablishCycleAbsence() {
        myFixture.addFileToProject(
            "src/main/kotlin/a/Owner.kt",
            """
            package a
            class Token
            fun read(token: Token): Token = token[token]
            fun update(token: Token) { token[token] = token }
            """.trimIndent(),
        )
        val (snapshot, graph) = analyze()
        assertTrue(!snapshot.complete, snapshot.diagnostics.toString())
        assertTrue(!graph.cycleAbsenceEstablished)
        assertTrue(
            snapshot.references.any { it.target is SourceTarget.Unresolved },
            snapshot.references.toString(),
        )
        assertTrue(snapshot.diagnostics.any { it.phase == "resolve" }, snapshot.diagnostics.toString())
    }

    fun testLocalKotlinTypeAliasDoesNotBecomeArchitecturalTarget() {
        val source =
            myFixture.addFileToProject(
                "src/main/kotlin/a/Owner.kt",
                """
                package a
                class Owner {
                    fun use() {
                        typealias LocalAlias = Owner
                        val copy: LocalAlias? = null
                    }
                }
                """.trimIndent(),
            )
        assertNotNull(PsiTreeUtil.findChildOfType(source, KtTypeAlias::class.java))
        val location = SourceDependencyExtractor.SourceLocation("main", "src/main/kotlin", source.virtualFile.url)
        val extracted = SourceDependencyExtractor { location }.extract(source, location)
        assertTrue(extracted.entities.any { it.id.signature == "a.Owner" }, extracted.entities.toString())
        assertTrue(
            extracted.entities.none { it.id.signature.contains("LocalAlias") },
            "A typealias nested in a function cannot become a project declaration: DUMMY",
        )
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
        (reference.target as? SourceTarget.Internal)
            ?.entity
            ?.fileUrl
            ?.let { url ->
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
        val file = requireNotNull(VirtualFileManager.getInstance().findFileByUrl(reference.evidence.fileUrl))
        assertTrue(reference.evidence.offset >= 0)
        assertTrue(reference.evidence.offset + reference.evidence.length <= file.length)
        requireNotNull(reference.target as? SourceTarget.Internal) { reference.toString() }
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
