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
package io.shamash.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Production-grade CLI tests WITHOUT SecurityManager tricks.
 *
 * Approach:
 * - Execute the CLI in a separate JVM process.
 * - Assert exit codes + stdout/stderr.
 *
 * This stays compatible with JDK 17+ where SecurityManager is deprecated (JEP 411).
 */
class MainTest {
    data class ProcResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    @Test
    fun `version prints version and exits 0`() {
        val r = runCli("version")
        assertEquals(0, r.exitCode, "stderr:\n${r.stderr}\nstdout:\n${r.stdout}")
        kotlin.test.assertTrue(r.stdout.trim().startsWith("shamash-cli"), "stdout:\n${r.stdout}")
    }

    @Test
    fun `init --stdout prints reference config and exits 0`() {
        val r = runCli("init", "--stdout")
        assertEquals(0, r.exitCode, "stderr:\n${r.stderr}\nstdout:\n${r.stdout}")
        // Keep assertions resilient to minor formatting changes
        kotlin.test.assertTrue(r.stdout.contains("version:", ignoreCase = true), "stdout:\n${r.stdout}")
        kotlin.test.assertTrue(r.stdout.contains("project:", ignoreCase = true), "stdout:\n${r.stdout}")
        assertTrue(r.stdout.contains("rules:", ignoreCase = true), "stdout:\n${r.stdout}")
    }

    @Test
    fun `init writes config file under project and validate succeeds`(
        @TempDir tmp: Path,
    ) {
        val init = runCli("init", "--project", tmp.toString())
        assertEquals(0, init.exitCode, "stderr:\n${init.stderr}\nstdout:\n${init.stdout}")

        val expected = tmp.resolve("shamash").resolve("configs").resolve("asm.yml")
        assertTrue(expected.exists(), "Expected config to be created at: $expected")
        val content = expected.readText(StandardCharsets.UTF_8)
        assertTrue(content.contains("version:"), "Config content seems wrong:\n$content")

        val validate = runCli("validate", "--project", tmp.toString())
        assertEquals(0, validate.exitCode, "stderr:\n${validate.stderr}\nstdout:\n${validate.stdout}")
        assertTrue(validate.stdout.contains("OK:"), "stdout:\n${validate.stdout}")
    }

    @Test
    fun `init without --force does not overwrite existing file`(
        @TempDir tmp: Path,
    ) {
        val init1 = runCli("init", "--project", tmp.toString())
        assertEquals(0, init1.exitCode, "stderr:\n${init1.stderr}\nstdout:\n${init1.stdout}")

        val cfg = tmp.resolve("shamash").resolve("configs").resolve("asm.yml")
        assertTrue(cfg.exists(), "Expected config to exist at: $cfg")

        // Mutate file to detect overwrite attempts
        Files.writeString(cfg, "version: 1\n# mutated\n", StandardCharsets.UTF_8)

        val init2 = runCli("init", "--project", tmp.toString())
        assertEquals(2, init2.exitCode, "stderr:\n${init2.stderr}\nstdout:\n${init2.stdout}")
        assertTrue(init2.stderr.contains("already exists", ignoreCase = true), "stderr:\n${init2.stderr}")

        val after = cfg.readText(StandardCharsets.UTF_8)
        assertTrue(after.contains("# mutated"), "File was overwritten unexpectedly:\n$after")

        val init3 = runCli("init", "--project", tmp.toString(), "--force")
        assertEquals(0, init3.exitCode, "stderr:\n${init3.stderr}\nstdout:\n${init3.stdout}")

        val overwritten = cfg.readText(StandardCharsets.UTF_8)
        assertFalse(overwritten.contains("# mutated"), "File was not overwritten with --force:\n$overwritten")
        assertTrue(overwritten.contains("rules:"), "Config content seems wrong after overwrite:\n$overwritten")
    }

    @Test
    fun `validate with no config exits 2`() {
        val r = runCli("validate", "--project", createTempDir().toString())
        assertEquals(2, r.exitCode, "stderr:\n${r.stderr}\nstdout:\n${r.stdout}")
        assertTrue(
            r.stderr.contains("ASM config not found", ignoreCase = true) ||
                r.stderr.contains("not found", ignoreCase = true),
            "stderr:\n${r.stderr}",
        )
    }

    @Test
    fun `scan with invalid fail-on exits 2 without needing a project`() {
        val r = runCli("scan", "--fail-on", "BAD")
        assertEquals(2, r.exitCode, "stderr:\n${r.stderr}\nstdout:\n${r.stdout}")
        assertTrue(r.stderr.contains("Unknown fail-on severity", ignoreCase = true), "stderr:\n${r.stderr}")
    }

    @Test
    fun `scan with invalid scope exits 2 without needing a project`() {
        val r = runCli("scan", "--scope", "BAD")
        assertEquals(2, r.exitCode, "stderr:\n${r.stderr}\nstdout:\n${r.stdout}")
        assertTrue(r.stderr.contains("Unknown --scope", ignoreCase = true), "stderr:\n${r.stderr}")
    }

    @Test
    fun `scan with non-positive max-classes exits 2 without needing a project`() {
        val r = runCli("scan", "--max-classes", "0")
        assertEquals(2, r.exitCode, "stderr:\n${r.stderr}\nstdout:\n${r.stdout}")
        assertTrue(r.stderr.contains("Invalid --max-classes", ignoreCase = true), "stderr:\n${r.stderr}")
    }

    @Test
    fun `scan prints applied overrides when provided`(
        @TempDir tmp: Path,
    ) {
        ensureBytecodeAndConfig(tmp)

        val r =
            runCli(
                "scan",
                "--project",
                tmp.toString(),
                "--scope",
                "ALL_SOURCES",
                "--follow-symlinks",
                "true",
                "--max-classes",
                "1",
            )

        assertEquals(0, r.exitCode, "stderr:\n${r.stderr}\nstdout:\n${r.stdout}")
        assertTrue(r.stdout.contains("Overrides", ignoreCase = false), "stdout:\n${r.stdout}")
        // Make sure at least one of our overrides is reflected back
        assertTrue(
            r.stdout.contains("scope=", ignoreCase = false) ||
                r.stdout.contains("followSymlinks=", ignoreCase = false) ||
                r.stdout.contains("maxClasses=", ignoreCase = false),
            "stdout:\n${r.stdout}",
        )
    }

    private fun runCli(
        vararg args: String,
        workingDir: Path? = null,
    ): ProcResult {
        val javaExe = resolveJavaExecutable()
        val cp = System.getProperty("java.class.path")
        require(!cp.isNullOrBlank()) { "java.class.path is empty; cannot spawn CLI process." }

        val cmd =
            ArrayList<String>(4 + args.size).apply {
                add(javaExe.toString())
                // Stabilize encoding in output assertions
                add("-Dfile.encoding=UTF-8")
                add("-cp")
                add(cp)
                add("io.shamash.cli.MainKt")
                addAll(args)
            }

        val pb = ProcessBuilder(cmd)
        if (workingDir != null) pb.directory(workingDir.toFile())
        pb.redirectErrorStream(false)

        val p = pb.start()

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val outThread =
            Thread {
                p.inputStream.use { it.copyTo(stdout) }
            }.apply {
                isDaemon = true
                start()
            }

        val errThread =
            Thread {
                p.errorStream.use { it.copyTo(stderr) }
            }.apply {
                isDaemon = true
                start()
            }

        val finished = p.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            throw AssertionError("CLI process timed out after 60s. Args=${args.joinToString(" ")}")
        }

        outThread.join(1_000)
        errThread.join(1_000)

        val charset: Charset = StandardCharsets.UTF_8
        return ProcResult(
            exitCode = p.exitValue(),
            stdout = stdout.toString(charset),
            stderr = stderr.toString(charset),
        )
    }

    private fun resolveJavaExecutable(): Path {
        val home = System.getProperty("java.home")
        val bin = Path.of(home, "bin")
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val exeName = if (isWindows) "java.exe" else "java"
        val exe = bin.resolve(exeName)
        if (Files.isRegularFile(exe)) return exe

        // Fallback for unusual layouts
        val alt = Path.of(home, exeName)
        if (Files.isRegularFile(alt)) return alt

        throw IllegalStateException("Could not locate java executable under java.home=$home")
    }

    private fun createTempDir(): Path = Files.createTempDirectory("shamash-cli-test-").toAbsolutePath().normalize()

    private fun ensureBytecodeAndConfig(project: Path) {
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        require(compiler != null) { "JDK compiler not available (ToolProvider.getSystemJavaCompiler returned null)" }

        val outDir = project.resolve("build/classes/java/main")
        Files.createDirectories(outDir)

        compileJava(
            tmp = project,
            fqcn = "com.example.App",
            source = "package com.example; public class App { public static void main(String[] a){} }",
            outputDir = outDir,
        )

        val cfgDir = project.resolve("shamash").resolve("configs")
        Files.createDirectories(cfgDir)
        val cfgPath = cfgDir.resolve("asm.yml")
        Files.writeString(cfgPath, minimalAsmConfigYaml(), StandardCharsets.UTF_8)
    }

    private fun minimalAsmConfigYaml(): String =
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
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler() ?: error("JDK compiler not available")

        val parts = fqcn.split('.')
        val cls = parts.last()
        val pkgPath = parts.dropLast(1).joinToString("/")

        val srcDir = tmp.resolve("srcgen").resolve(pkgPath)
        Files.createDirectories(srcDir)

        val javaFile = srcDir.resolve("$cls.java")
        Files.writeString(javaFile, source, StandardCharsets.UTF_8)

        val rc = compiler.run(null, null, null, "-d", outputDir.toString(), javaFile.toString())
        if (rc != 0) error("javac failed with exit code $rc")
    }
}
