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

import io.shamash.asm.core.config.schema.v1.model.ExportFactsFormat
import io.shamash.asm.core.export.facts.FactsExporter
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.model.DependencyKind
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.model.TypeRef
import io.shamash.asm.core.facts.query.FactIndex
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class FactsCliE2ETest {
    @Test
    fun `facts subcommand reads jsonl gz and prints summaries`() {
        val tmp = Files.createTempDirectory("shamash-facts-cli-").toAbsolutePath().normalize()
        val factsPath = tmp.resolve("facts.jsonl.gz")

        FactsExporter.export(
            facts = sampleFactsIndex(),
            outputPath = factsPath,
            format = ExportFactsFormat.JSONL_GZ,
            toolName = "shamash",
            toolVersion = "test",
            projectName = "e2e",
            generatedAtEpochMillis = System.currentTimeMillis(),
        )

        val res = runCli("facts", "--path", factsPath.toString())
        assert(res.exitCode == 0) {
            "Expected exitCode=0 but was ${res.exitCode}\nSTDOUT:\n${res.stdout}\nSTDERR:\n${res.stderr}"
        }

        // Totals
        assert(res.stdout.contains("Classes")) { res.stdout }
        assert(res.stdout.contains("Edges")) { res.stdout }
        assert(res.stdout.contains("Total")) { res.stdout }

        // Package + fan-in/out sections
        assert(res.stdout.contains("Top packages")) { res.stdout }
        assert(res.stdout.contains("Top fan-out")) { res.stdout }
        assert(res.stdout.contains("Top fan-in")) { res.stdout }
    }

    @Test
    fun `facts subcommand supports class filter`() {
        val tmp = Files.createTempDirectory("shamash-facts-cli-").toAbsolutePath().normalize()
        val factsPath = tmp.resolve("facts.jsonl.gz")

        FactsExporter.export(
            facts = sampleFactsIndex(),
            outputPath = factsPath,
            format = ExportFactsFormat.JSONL_GZ,
            toolName = "shamash",
            toolVersion = "test",
            projectName = "e2e",
            generatedAtEpochMillis = System.currentTimeMillis(),
        )

        val res = runCli("facts", "--path", factsPath.toString(), "--class", "com.example.A")
        assert(res.exitCode == 0) {
            "Expected exitCode=0 but was ${res.exitCode}\nSTDOUT:\n${res.stdout}\nSTDERR:\n${res.stderr}"
        }
        assert(res.stdout.contains("com.example.A")) { res.stdout }
        // Should show edges to/from the class
        assert(res.stdout.contains("->")) { res.stdout }
    }

    private data class CliRun(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun runCli(vararg args: String): CliRun {
        val classpath = System.getProperty("java.class.path")
        val java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"

        val cmd = mutableListOf(java, "-cp", classpath, "io.shamash.cli.Main")
        cmd.addAll(args)

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(false)

        val p = pb.start()

        val out = StringBuilder()
        val err = StringBuilder()

        val outThread = Thread { readAll(p.inputStream, out) }
        val errThread = Thread { readAll(p.errorStream, err) }
        outThread.start()
        errThread.start()

        val code = p.waitFor()
        outThread.join(10_000)
        errThread.join(10_000)

        return CliRun(exitCode = code, stdout = out.toString(), stderr = err.toString())
    }

    private fun readAll(
        stream: java.io.InputStream,
        into: StringBuilder,
    ) {
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines { lines ->
            lines.forEach {
                into.append(it).append('\n')
            }
        }
    }

    private fun sampleFactsIndex(): FactIndex {
        val a = TypeRef.fromInternalName("com/example/A")
        val b = TypeRef.fromInternalName("com/example/B")
        val loc = SourceLocation(originKind = OriginKind.DIR_CLASS, originPath = "/tmp/A.class")

        val classes =
            listOf(
                ClassFact(
                    type = a,
                    access = ACC_PUBLIC,
                    superType = TypeRef.fromInternalName("java/lang/Object"),
                    interfaces = emptySet(),
                    annotationsFqns = emptySet(),
                    hasMainMethod = false,
                    location = loc,
                ),
                ClassFact(
                    type = b,
                    access = ACC_PUBLIC,
                    superType = TypeRef.fromInternalName("java/lang/Object"),
                    interfaces = emptySet(),
                    annotationsFqns = emptySet(),
                    hasMainMethod = false,
                    location = loc,
                ),
            )

        val methods =
            listOf(
                io.shamash.asm.core.facts.model.MethodRef(
                    owner = a,
                    name = "m1",
                    desc = "()V",
                    signature = null,
                    access = ACC_PUBLIC,
                    isConstructor = false,
                    returnType = null,
                    parameterTypes = emptyList(),
                    throwsTypes = emptyList(),
                    annotationsFqns = emptySet(),
                    location = loc,
                ),
                io.shamash.asm.core.facts.model.MethodRef(
                    owner = b,
                    name = "m",
                    desc = "()V",
                    signature = null,
                    access = ACC_PUBLIC,
                    isConstructor = false,
                    returnType = null,
                    parameterTypes = emptyList(),
                    throwsTypes = emptyList(),
                    annotationsFqns = emptySet(),
                    location = loc,
                ),
            )

        val fields =
            listOf(
                io.shamash.asm.core.facts.model.FieldRef(
                    owner = a,
                    name = "f",
                    desc = "Ljava/lang/String;",
                    signature = null,
                    access = ACC_PUBLIC,
                    fieldType = TypeRef.fromInternalName("java/lang/String"),
                    annotationsFqns = emptySet(),
                    location = loc,
                ),
            )

        val edges =
            listOf(
                DependencyEdge(
                    from = a,
                    to = b,
                    kind = DependencyKind.TYPE_INSTRUCTION,
                    location = loc,
                    detail = "e2e",
                ),
            )

        return FactIndex(
            classes = classes,
            methods = methods,
            fields = fields,
            edges = edges,
            roles = emptyMap(),
            classToRole = emptyMap(),
        )
    }

    private companion object {
        private const val ACC_PUBLIC: Int = 0x0001
    }
}
