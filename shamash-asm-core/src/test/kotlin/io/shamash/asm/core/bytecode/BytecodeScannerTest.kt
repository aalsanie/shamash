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
package io.shamash.asm.core.scan.bytecode

import io.shamash.asm.core.config.schema.v1.model.BytecodeConfig
import io.shamash.asm.core.config.schema.v1.model.GlobSet
import io.shamash.asm.core.config.schema.v1.model.ScanConfig
import io.shamash.asm.core.config.schema.v1.model.ScanScope
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BytecodeScannerTest {
    @Test
    fun `scans matching output directories and applies maxClasses limit`() {
        val compiler = ToolProvider.getSystemJavaCompiler()
        Assume.assumeNotNull(compiler)

        val project = Files.createTempDirectory("shamash-asm-scan")
        try {
            val outDir = project.resolve("build/classes/java/main")
            Files.createDirectories(outDir)

            compileJava(project, "com.example.A", "package com.example; public class A {}", outDir)
            compileJava(project, "com.example.B", "package com.example; public class B {}", outDir)

            val bytecode =
                BytecodeConfig(
                    roots = listOf("."),
                    // IMPORTANT:
                    // Use "build/classes/**" (not "**/build/classes/**") because stable paths are relative
                    // ("build/classes/...") and "**/build/..." requires a leading slash somewhere before "build".
                    // GlobMatcher already provides "match anywhere" fallback for relative globs.
                    outputsGlobs = GlobSet(include = listOf("build/classes/**"), exclude = emptyList()),
                    jarGlobs = GlobSet(include = emptyList(), exclude = emptyList()),
                )

            val scan =
                ScanConfig(
                    scope = ScanScope.PROJECT_ONLY,
                    followSymlinks = false,
                    maxClasses = 1,
                    maxJarBytes = null,
                    maxClassBytes = null,
                )

            val result =
                BytecodeScanner().scan(
                    projectBasePath = project,
                    bytecode = bytecode,
                    scan = scan,
                )

            assertEquals(1, result.units.size, "maxClasses=1 should cap scanned classes")
            assertTrue(result.truncated, "result should be marked truncated when more than maxClasses exist")
            assertTrue(result.errors.isEmpty(), "scan should not report errors: ${result.errors}")
            assertTrue(result.origins.isNotEmpty(), "scan should contain origins")
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    private fun compileJava(
        tmp: Path,
        fqcn: String,
        source: String,
        outputDir: Path,
    ) {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("JDK compiler not available")

        val parts = fqcn.split('.')
        val cls = parts.last()
        val pkgPath = parts.dropLast(1).joinToString("/")

        val srcDir = tmp.resolve("srcgen").resolve(pkgPath)
        Files.createDirectories(srcDir)

        val javaFile = srcDir.resolve("$cls.java")
        Files.writeString(javaFile, source)

        val rc = compiler.run(null, null, null, "-d", outputDir.toString(), javaFile.toString())
        if (rc != 0) error("javac failed with exit code $rc")
    }
}
