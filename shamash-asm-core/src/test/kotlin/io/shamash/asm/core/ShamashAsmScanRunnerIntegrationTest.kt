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
package io.shamash.asm.core

import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import io.shamash.asm.core.scan.RunOverrides
import io.shamash.asm.core.scan.RunnerOverrides
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ShamashAsmScanRunner
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShamashAsmScanRunnerIntegrationTest {
    @Test
    fun `runner discovers config, scans bytecode and runs engine`() {
        val compiler = ToolProvider.getSystemJavaCompiler()
        Assume.assumeNotNull(compiler)

        val project = Files.createTempDirectory("shamash-asm-e2e")
        try {
            val outDir = project.resolve("build/classes/java/main")
            Files.createDirectories(outDir)
            compileJava(project, "com.example.App", "package com.example; public class App {}", outDir)

            val cfgDir = project.resolve("shamash/configs")
            Files.createDirectories(cfgDir)
            val cfgPath = cfgDir.resolve("asm.yml")
            Files.writeString(cfgPath, minimalConfigYaml())

            val validation = Files.newBufferedReader(cfgPath).use { reader -> ConfigValidation.loadAndValidateV1(reader) }
            assertTrue(validation.ok, "config should validate: ${validation.errors}")
            assertNotNull(validation.config)

            val runner = ShamashAsmScanRunner()
            val result = runner.run(ScanOptions(projectBasePath = project, projectName = "demo"))

            assertNotNull(result.configPath)
            assertNotNull(result.config)
            assertTrue(result.configErrors.isEmpty(), "config errors should be empty: ${result.configErrors}")
            assertTrue(result.scanErrors.isEmpty(), "scan errors should be empty: ${result.scanErrors}")
            assertTrue(result.classUnits >= 1, "scanner should find at least one class unit (got ${result.classUnits})")
            assertTrue(result.origins.isNotEmpty(), "scanner should include at least one origin")

            val engine = result.engine
            assertNotNull(engine)
            assertTrue(engine.isSuccess, "engine should succeed: ${engine.errors}")
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runner uses built in discovery for configless Maven bytecode`() {
        val compiler = ToolProvider.getSystemJavaCompiler()
        Assume.assumeNotNull(compiler)

        val project = Files.createTempDirectory("shamash-asm-discovery")
        try {
            val outDir = project.resolve("target/classes")
            Files.createDirectories(outDir)
            compileJava(project, "com.example.App", "package com.example; public class App {}", outDir)

            val result = ShamashAsmScanRunner().run(ScanOptions(projectBasePath = project, projectName = "maven-demo"))

            assertNull(result.configPath)
            assertNotNull(result.config)
            assertTrue(result.configErrors.isEmpty(), "config errors should be empty: ${result.configErrors}")
            assertTrue(result.scanErrors.isEmpty(), "scan errors should be empty: ${result.scanErrors}")
            assertTrue(result.classUnits >= 1, "scanner should find Maven bytecode (got ${result.classUnits})")
            assertTrue(result.origins.isNotEmpty(), "scanner should include at least one origin")
            val engine = result.engine
            assertNotNull(engine)
            assertTrue(engine.isSuccess, "engine should succeed: ${engine.errors}")
            assertFalse(Files.exists(project.resolve("shamash/configs/asm.yml")))
            assertFalse(Files.exists(project.resolve(".shamash")))
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runner does not bypass an invalid project config with discovery`() {
        val compiler = ToolProvider.getSystemJavaCompiler()
        Assume.assumeNotNull(compiler)

        val project = Files.createTempDirectory("shamash-asm-invalid-config")
        try {
            val outDir = project.resolve("target/classes")
            Files.createDirectories(outDir)
            compileJava(project, "com.example.App", "package com.example; public class App {}", outDir)

            val cfgDir = project.resolve("shamash/configs")
            Files.createDirectories(cfgDir)
            val cfgPath = cfgDir.resolve("asm.yml")
            Files.writeString(cfgPath, "version: 2\nproject: {}\n")

            val result = ShamashAsmScanRunner().run(ScanOptions(projectBasePath = project, projectName = "invalid-config"))

            assertEquals(cfgPath, result.configPath)
            assertTrue(result.configErrors.isNotEmpty())
            assertNull(result.engine)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runner does not write baseline when no bytecode exists`() {
        val project = Files.createTempDirectory("shamash-asm-empty")
        try {
            val cfgDir = project.resolve("shamash/configs")
            Files.createDirectories(cfgDir)
            Files.writeString(cfgDir.resolve("asm.yml"), minimalConfigYaml())

            val result =
                ShamashAsmScanRunner().run(
                    ScanOptions(projectBasePath = project, projectName = "empty"),
                    RunOverrides(runner = RunnerOverrides(baselineMode = BaselineMode.GENERATE, exportEnabled = false)),
                )

            assertEquals(0, result.classUnits)
            assertTrue(result.configErrors.isEmpty())
            assertTrue(result.scanErrors.isEmpty())
            assertNull(result.engine)
            assertFalse(Files.exists(project.resolve(".shamash/baseline.json")))
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    private fun minimalConfigYaml(): String =
        """
        version: 1

        project:
          bytecode:
            roots: ["build/classes/java/main"]

            outputsGlobs:
              include: ["**"]
              exclude: []

            jarGlobs:
              include: ["**/*.jar"]
              exclude: ["**/*"]

          scan:
            scope: PROJECT_ONLY
            followSymlinks: false
            maxClasses: null
            maxJarBytes: null
            maxClassBytes: null

          validation:
            unknownRule: IGNORE

        roles: {}

        analysis:
          graphs:
            enabled: false
            granularity: PACKAGE
            includeExternalBuckets: false

          hotspots:
            enabled: false
            topN: 10
            includeExternal: false

          scoring:
            enabled: false
            model: V1
            godClass:
              enabled: false
              weights: null
              thresholds: null
            overall:
              enabled: false
              weights: null
              thresholds: null

        rules: []
        exceptions: []

        baseline:
          mode: NONE
          path: .shamash/baseline.json

        export:
          enabled: false
          outputDir: .shamash/reports/asm
          formats: [JSON]
          overwrite: true
        """.trimIndent()

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
