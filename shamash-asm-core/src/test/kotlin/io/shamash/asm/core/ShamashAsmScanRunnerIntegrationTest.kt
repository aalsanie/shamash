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
package io.shamash.asm.core

import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import io.shamash.asm.core.scan.RunOverrides
import io.shamash.asm.core.scan.RunnerOverrides
import io.shamash.asm.core.scan.ScanError
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ScanOverrides
import io.shamash.asm.core.scan.ShamashAsmScanRunner
import org.junit.Assume
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test
    fun `runner refuses to create or replace a baseline after truncation`() {
        for (existing in listOf(false, true)) {
            withBytecodeProject { project ->
                writeClass(project, "Other")
                val baseline = project.resolve(".shamash/baseline.json")
                if (existing) seedBaseline(project)

                val result =
                    ShamashAsmScanRunner().run(
                        ScanOptions(project),
                        RunOverrides(
                            scan = ScanOverrides(maxClasses = 1),
                            runner = RunnerOverrides(baselineMode = BaselineMode.GENERATE),
                        ),
                    )

                assertTrue(result.configErrors.isEmpty(), result.configErrors.toString())
                assertEquals(1, result.classUnits)
                assertTrue(result.truncated)
                assertFalse(result.isSuccess)
                assertNull(result.engine)
                assertTrue(result.scanErrors.any { it.message.contains("Baseline generation skipped") })
                if (existing) {
                    assertContentEquals(baselineBytes, Files.readAllBytes(baseline))
                } else {
                    assertFalse(Files.exists(baseline))
                }
            }
        }
    }

    @Test
    fun `runner preserves baseline after rejecting an oversized class`() {
        withBytecodeProject(minimalConfigYaml().replace("mode: NONE", "mode: GENERATE")) { project ->
            val baseline = seedBaseline(project)
            val output = project.resolve("build/classes/java/main")
            val classSize = Files.size(output.resolve("App.class")).toInt()
            Files.write(output.resolve("Large.class"), ByteArray(classSize + 1))

            val result =
                ShamashAsmScanRunner().run(
                    ScanOptions(project),
                    RunOverrides(scan = ScanOverrides(maxClassBytes = classSize)),
                )

            assertTrue(result.configErrors.isEmpty(), result.configErrors.toString())
            assertEquals(1, result.classUnits)
            assertTrue(result.scanErrors.any { it.phase == ScanError.Phase.BYTECODE_SCAN })
            assertFalse(result.isSuccess)
            assertNull(result.engine)
            assertContentEquals(baselineBytes, Files.readAllBytes(baseline))
        }
    }

    @Test
    fun `runner preserves baseline after fact extraction errors`() {
        withBytecodeProject { project ->
            val baseline = seedBaseline(project)
            Files.write(project.resolve("build/classes/java/main/Broken.class"), byteArrayOf(1, 2, 3))

            val runner = ShamashAsmScanRunner()
            val result =
                runner.run(
                    ScanOptions(project),
                    RunOverrides(runner = RunnerOverrides(baselineMode = BaselineMode.GENERATE)),
                )

            assertTrue(result.configErrors.isEmpty(), result.configErrors.toString())
            assertEquals(2, result.classUnits)
            assertTrue(result.factsErrors.isNotEmpty())
            assertFalse(result.isSuccess)
            assertNull(result.engine)
            assertContentEquals(baselineBytes, Files.readAllBytes(baseline))

            val partial = runner.run(ScanOptions(project))
            assertNotNull(partial.engine)
            assertTrue(partial.factsErrors.isNotEmpty())
            assertFalse(partial.isSuccess)
            assertContentEquals(baselineBytes, Files.readAllBytes(baseline))
        }
    }

    @Test
    fun `runner generates baseline after a complete scan at the exact class limit`() {
        withBytecodeProject { project ->
            val baseline = seedBaseline(project)
            val result =
                ShamashAsmScanRunner().run(
                    ScanOptions(project),
                    RunOverrides(
                        scan = ScanOverrides(maxClasses = 1),
                        runner = RunnerOverrides(baselineMode = BaselineMode.GENERATE),
                    ),
                )

            assertTrue(result.isSuccess, result.toString())
            assertEquals(1, result.classUnits)
            assertFalse(result.truncated)
            assertTrue(Files.readString(baseline).contains("\"fingerprints\": []"))
        }
    }

    @Test
    fun `invalid scan overrides fail before replacing a baseline`() {
        withBytecodeProject { project ->
            val baseline = seedBaseline(project)
            val result =
                ShamashAsmScanRunner().run(
                    ScanOptions(project),
                    RunOverrides(
                        scan = ScanOverrides(maxClasses = 0),
                        runner = RunnerOverrides(baselineMode = BaselineMode.GENERATE),
                    ),
                )

            assertFalse(result.isSuccess)
            assertNull(result.engine)
            assertTrue(
                result.scanErrors
                    .single()
                    .message
                    .contains("maxClasses must be > 0"),
            )
            assertContentEquals(baselineBytes, Files.readAllBytes(baseline))
        }
    }

    private val baselineBytes = "{\"version\":1,\"fingerprints\":[\"existing\"]}\n".toByteArray()

    private fun seedBaseline(project: Path): Path {
        val path = project.resolve(".shamash/baseline.json")
        Files.createDirectories(path.parent)
        return Files.write(path, baselineBytes)
    }

    private fun withBytecodeProject(
        yaml: String = minimalConfigYaml(),
        action: (Path) -> Unit,
    ) {
        val validation = ConfigValidation.loadAndValidateV1(yaml.reader())
        assertTrue(validation.ok, validation.errors.toString())
        val project = Files.createTempDirectory("shamash-baseline-runner")
        try {
            writeClass(project, "App")
            val configPath = project.resolve("shamash/configs/asm.yml")
            Files.createDirectories(configPath.parent)
            Files.writeString(configPath, yaml)
            action(project)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    private fun writeClass(
        project: Path,
        name: String,
    ) {
        val path = project.resolve("build/classes/java/main/$name.class")
        Files.createDirectories(path.parent)
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitEnd()
        Files.write(path, writer.toByteArray())
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
