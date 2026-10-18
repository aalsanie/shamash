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
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.query.FactIndex
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FactExtractorAggregationTest {
    @Test
    fun `many units retain all facts and deterministic ordering`() {
        val units = (512 downTo 1).map { classUnit("org/example/C$it") }
        val result = FactExtractor.extractAll(units.asSequence())
        val reversed = FactExtractor.extractAll(units.asReversed().asSequence())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(result, reversed)
        assertEquals(512, result.facts.classes.size)
        assertEquals(512, result.facts.fields.size)
        assertEquals(512, result.facts.methods.size)
        assertEquals(2048, result.facts.edges.size)
        assertEquals(
            (1..512).map { "org.example.C$it" }.sorted(),
            result.facts.classes.map { it.fqName },
        )
        assertTrue(result.facts.roles.isEmpty())
        assertTrue(result.facts.classToRole.isEmpty())
    }

    @Test
    fun `duplicates retain the first extracted fact for each stable key`() {
        val first =
            classUnit(
                internalName = "org/example/Duplicate",
                classAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                fieldAccess = Opcodes.ACC_PUBLIC,
                methodAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            )
        val second =
            classUnit(
                internalName = "org/example/Duplicate",
                classAccess = Opcodes.ACC_ABSTRACT,
                fieldAccess = Opcodes.ACC_PROTECTED,
                methodAccess = Opcodes.ACC_PROTECTED or Opcodes.ACC_ABSTRACT,
            )

        val firstFacts = FactExtractor.extractResult(first).facts
        val secondFacts = FactExtractor.extractResult(second).facts

        assertEquals(firstFacts, FactExtractor.extractAll(sequenceOf(first, second)).facts)
        assertEquals(secondFacts, FactExtractor.extractAll(sequenceOf(second, first)).facts)
    }

    @Test
    fun `malformed unit records error without dropping subsequent units`() {
        val brokenPath = "classes/org/example/Broken.class"
        val broken =
            BytecodeUnit(
                bytes = byteArrayOf(0, 1, 2, 3),
                location =
                    SourceLocation(
                        originKind = OriginKind.DIR_CLASS,
                        originPath = brokenPath,
                    ),
                originId = brokenPath,
            )
        val result =
            FactExtractor.extractAll(
                sequence {
                    yield(classUnit("org/example/Before"))
                    yield(broken)
                    yield(classUnit("org/example/After"))
                },
            )

        assertEquals(listOf("org.example.After", "org.example.Before"), result.facts.classes.map { it.fqName })
        assertEquals(2, result.facts.methods.size)
        assertEquals(2, result.facts.fields.size)
        assertEquals(8, result.facts.edges.size)
        assertEquals(1, result.errors.size)
        assertEquals(brokenPath, result.errors.single().originId)
        assertEquals("ClassReader", result.errors.single().phase)
    }

    @Test
    fun `empty stream returns an empty fact index`() {
        val result = FactExtractor.extractAll(emptySequence())

        assertEquals(FactIndex.empty(), result.facts)
        assertTrue(result.errors.isEmpty())
    }

    private fun classUnit(
        internalName: String,
        classAccess: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
        fieldAccess: Int = Opcodes.ACC_PRIVATE,
        methodAccess: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
    ): BytecodeUnit {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, classAccess, internalName, null, "java/lang/Object", null)
        writer.visitField(fieldAccess, "items", "Ljava/util/List;", null, null).visitEnd()
        writer.visitMethod(methodAccess, "process", "(Ljava/lang/String;)Ljava/lang/Object;", null, null).visitEnd()
        writer.visitEnd()

        val originPath = "classes/$internalName.class"
        return BytecodeUnit(
            bytes = writer.toByteArray(),
            location =
                SourceLocation(
                    originKind = OriginKind.DIR_CLASS,
                    originPath = originPath,
                ),
            originId = originPath,
        )
    }
}
