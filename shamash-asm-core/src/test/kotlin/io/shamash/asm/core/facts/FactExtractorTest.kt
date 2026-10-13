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
package io.shamash.asm.core.facts

import io.shamash.asm.core.facts.bytecode.BytecodeUnit
import io.shamash.asm.core.facts.model.DependencyKind
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import org.junit.Assume
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertTrue

class FactExtractorTest {
    @Test
    fun `extracts dependency edges from compiled class bytes`() {
        val compiler = ToolProvider.getSystemJavaCompiler()
        Assume.assumeNotNull(compiler)

        val project = Files.createTempDirectory("shamash-asm-facts")
        try {
            val outDir = project.resolve("build/classes/java/main")
            Files.createDirectories(outDir)

            val source =
                """
                package com.example;

                import java.util.*;

                public class Foo {
                    private List<String> xs = Collections.emptyList();
                    public List<String> get() { return xs; }
                }
                """.trimIndent()

            val classFile = compileJava(project, "com.example.Foo", source, outDir)
            val bytes = Files.readAllBytes(classFile)

            val location =
                SourceLocation(
                    originKind = OriginKind.DIR_CLASS,
                    originPath = classFile.toString(),
                )

            val unit =
                BytecodeUnit(
                    bytes = bytes,
                    location = location,
                    originId = location.originPath,
                )

            val result = FactExtractor.extractResult(unit)
            val facts = result.facts

            assertTrue(result.errors.isEmpty(), "expected no extraction errors; got: ${result.errors}")

            val edges = facts.edges
            assertTrue(edges.isNotEmpty(), "expected at least one dependency edge")

            assertTrue(
                edges.any { it.kind == DependencyKind.FIELD_TYPE && it.to.fqName == "java.util.List" },
                "expected FIELD_TYPE edge to java.util.List; got: $edges",
            )

            assertTrue(
                edges.any { it.kind == DependencyKind.METHOD_CALL && it.to.fqName == "java.util.Collections" },
                "expected METHOD_CALL edge to java.util.Collections; got: $edges",
            )

            val clazz = facts.classes.singleOrNull { it.fqName == "com.example.Foo" }
            assertTrue(clazz != null, "expected ClassFact for com.example.Foo")

            val loc = clazz.location
            assertTrue(loc.originKind == OriginKind.DIR_CLASS)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `extracts generic signature dependencies from classes fields and methods`() {
        val compiler = ToolProvider.getSystemJavaCompiler()
        Assume.assumeNotNull(compiler)

        val project = Files.createTempDirectory("shamash-asm-generics")
        try {
            val outputDir = project.resolve("classes")
            Files.createDirectories(outputDir)
            val source =
                """
                package com.example;

                import java.util.List;
                import java.util.Map;

                public abstract class Foo<T extends Bound & Tagged>
                        extends Parent<ParentArgument> implements Contract<ContractArgument> {
                    public List<FieldArgument> field;
                    public Map<String, List<? extends WildcardArgument[]>> nested;
                    public List<? super LowerArgument> lower;
                    public Outer<OuterArgument>.Inner<InnerArgument> inner;

                    public abstract List<ReturnArgument> transform(List<ParameterArgument> input);
                    public abstract <U extends MethodBound & MethodTagged> U bounded(U input);

                    public static class Outer<V> {
                        public class Inner<W> {}
                    }
                }

                class Bound {}
                interface Tagged {}
                class Parent<P> {}
                interface Contract<C> {}
                class ParentArgument {}
                class ContractArgument {}
                class FieldArgument {}
                class WildcardArgument {}
                class LowerArgument {}
                class OuterArgument {}
                class InnerArgument {}
                class ReturnArgument {}
                class ParameterArgument {}
                class MethodBound {}
                interface MethodTagged {}
                """.trimIndent()

            val classFile = compileJava(project, "com.example.Foo", source, outputDir)
            val location = SourceLocation(originKind = OriginKind.DIR_CLASS, originPath = classFile.toString())
            val result =
                FactExtractor.extractResult(
                    BytecodeUnit(bytes = Files.readAllBytes(classFile), location = location, originId = location.originPath),
                )

            assertTrue(result.errors.isEmpty(), result.errors.toString())

            val generics = result.facts.edges.filter { it.kind == DependencyKind.GENERIC_TYPE }
            val targets = generics.mapTo(mutableSetOf()) { it.to.fqName }
            val expected =
                setOf(
                    "com.example.Bound",
                    "com.example.Tagged",
                    "com.example.ParentArgument",
                    "com.example.ContractArgument",
                    "com.example.FieldArgument",
                    "com.example.WildcardArgument",
                    "com.example.LowerArgument",
                    "com.example.OuterArgument",
                    "com.example.InnerArgument",
                    "com.example.ReturnArgument",
                    "com.example.ParameterArgument",
                    "com.example.MethodTagged",
                )

            assertTrue(targets.containsAll(expected), "missing: ${expected - targets}; got: $generics")
            fun hasGeneric(detail: String, fqn: String): Boolean =
                generics.any { it.detail == detail && it.to.fqName == fqn }

            assertTrue(hasGeneric("field:field", "com.example.FieldArgument"))
            assertTrue(hasGeneric("field:nested", "com.example.WildcardArgument"))
            val methodDetail = "method:transform(Ljava/util/List;)Ljava/util/List;"
            assertTrue(hasGeneric(methodDetail, "com.example.ParameterArgument"))
            assertTrue(hasGeneric(methodDetail, "com.example.ReturnArgument"))
            assertTrue(!hasGeneric("field:field", "java.util.List"))
            assertTrue(result.facts.edges.any { it.kind == DependencyKind.FIELD_TYPE && it.to.fqName == "java.util.List" })
            assertTrue(generics.any { it.to.internalName == "com/example/Foo" + "$" + "Outer" })
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `malformed generic signature reports an error without losing class facts`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Broken", "<T:Lbad", "java/lang/Object", null)
        writer.visitEnd()

        val location = SourceLocation(originKind = OriginKind.DIR_CLASS, originPath = "Broken.class")
        val result =
            FactExtractor.extractResult(
                BytecodeUnit(bytes = writer.toByteArray(), location = location, originId = location.originPath),
            )

        assertTrue(result.errors.any { it.phase == "signature:class" }, result.errors.toString())
        assertTrue(result.facts.classes.any { it.fqName == "com.example.Broken" })
    }

    private fun compileJava(
        tmp: Path,
        fqcn: String,
        source: String,
        outputDir: Path,
    ): Path {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("JDK compiler not available")
        val parts = fqcn.split('.')
        val cls = parts.last()

        val srcDir = tmp.resolve("srcgen").resolve(parts.dropLast(1).joinToString("/"))
        Files.createDirectories(srcDir)

        val javaFile = srcDir.resolve("$cls.java")
        Files.writeString(javaFile, source)

        val rc = compiler.run(null, null, null, "-d", outputDir.toString(), javaFile.toString())
        if (rc != 0) error("javac failed with exit code $rc")

        val rel = parts.joinToString("/") + ".class"
        return outputDir.resolve(rel)
    }
}
