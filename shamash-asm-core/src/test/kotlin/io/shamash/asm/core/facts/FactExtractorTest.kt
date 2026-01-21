/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
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
package io.shamash.asm.core.facts

import io.shamash.asm.core.facts.bytecode.BytecodeUnit
import io.shamash.asm.core.facts.model.DependencyKind
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import org.junit.Assume
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
