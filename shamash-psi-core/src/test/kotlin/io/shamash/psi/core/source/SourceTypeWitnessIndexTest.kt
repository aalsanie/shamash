/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceTypeWitnessIndexTest {
    private val owner = SourceEntityId("module", "file:///src", "file:///src/Owner.java", SourceEntityKind.CLASS, "demo.Owner")
    private val target =
        SourceTarget.Internal(
            SourceEntityId("module", "file:///src", "file:///src/Target.java", SourceEntityKind.CLASS, "demo.Target"),
        )

    @Test
    fun `matches exact owner target and half-open source range`() {
        val index = SourceTypeWitnessIndex(listOf(reference(20)))
        assertFalse(index.contains(owner, target, 0, 19))
        assertFalse(index.contains(owner, target, 20, 20))
        assertFalse(index.contains(owner, target, 0, 20))
        assertTrue(index.contains(owner, target, 20, 21))
        assertTrue(index.contains(owner, target, 0, 21))
        assertFalse(index.contains(owner, target, 21, 99))
        assertFalse(index.contains(owner.copy(moduleId = "other"), target, 0, 99))
        assertFalse(index.contains(owner, SourceTarget.External("demo.Target"), 0, 99))
    }

    @Test
    fun `ignores non type evidence and indexes subsequent type witnesses`() {
        val index = SourceTypeWitnessIndex(listOf(reference(10).copy(kind = SourceDependencyKind.CALL)))
        assertFalse(index.contains(owner, target, 0, 20))
        index.add(reference(13))
        assertFalse(index.contains(owner, target, 12, 13))
        assertTrue(index.contains(owner, target, 12, 14))
        assertFalse(index.contains(owner, target, 14, 20))
        index.add(reference(33))
        assertTrue(index.contains(owner, target, 30, 40))
        assertFalse(index.contains(owner, target, 20, 30))
    }

    @Test
    fun `dense witnesses remain independently searchable`() {
        val index = SourceTypeWitnessIndex((0 until 5_000).map { reference(it * 4) })
        assertFalse(index.contains(owner, target, 19_996, 19_996))
        assertTrue(index.contains(owner, target, 19_996, 19_997))
        assertFalse(index.contains(owner, target, 19_997, 19_999))
    }

    @Test
    fun `generic fallback locates only an exact unambiguous type token`() {
        val text = "Map<TargetExtra, demo.Target>"
        assertEquals(text.lastIndexOf("Target"), findUniqueTypeTokenOffset(text, "Target"))
        assertEquals(0, findUniqueTypeTokenOffset("Target", "Target"))
        assertEquals(5, findUniqueTypeTokenOffset("List<Target>", "Target"))
    }

    @Test
    fun `generic fallback refuses repeated or missing token evidence`() {
        assertNull(findUniqueTypeTokenOffset("Map<Target, Target>", "Target"))
        assertNull(findUniqueTypeTokenOffset("Map<a.Target, b.Target>", "Target"))
        assertNull(findUniqueTypeTokenOffset("List<TargetExtra>", "Target"))
        assertNull(findUniqueTypeTokenOffset("List<Other>", "Target"))
        assertNull(findUniqueTypeTokenOffset("Target", ""))
    }

    @Test
    fun `generic fallback respects identifier boundaries`() {
        val text = "List<πTarget, _Target, Target>"
        assertEquals(text.lastIndexOf("Target"), findUniqueTypeTokenOffset(text, "Target"))
        assertNull(findUniqueTypeTokenOffset("List<\$Target>", "Target"))
        assertNull(findUniqueTypeTokenOffset("List<Target2>", "Target"))
    }

    @Test
    fun `missing generic witness is added with an exact source location`() {
        val existing = mutableListOf<SourceDependency>()
        val index = SourceTypeWitnessIndex(existing)
        val sourceText = "Map<String, demo.Target>"
        val relativeOffset = sourceText.indexOf("Target")
        assertTrue(index.addMissing(existing, owner, target, sourceText, 40, "Target", 37))
        val witness = existing.single()
        assertEquals(owner, witness.from)
        assertEquals(target, witness.target)
        assertEquals(SourceDependencyKind.TYPE, witness.kind)
        assertEquals(40 + relativeOffset, witness.evidence.offset)
        assertEquals("Target".length, witness.evidence.length)
        assertEquals(37, witness.evidence.documentStamp)
        assertTrue(index.contains(owner, target, 40, 40 + sourceText.length))
        assertFalse(index.contains(owner, target, 0, 40))
    }

    @Test
    fun `ambiguous generic witness preserves existing evidence and rejects fabricated offsets`() {
        val existing = mutableListOf(reference(11))
        val index = SourceTypeWitnessIndex(existing)
        assertFalse(index.addMissing(existing, owner, target, "Map<Target, Target>", 40, "Target", 37))
        assertFalse(index.addMissing(existing, owner, target, "List<TargetExtra>", 40, "Target", 37))
        assertEquals(1, existing.size)
        assertTrue(index.contains(owner, target, 11, 12))
        assertFalse(index.contains(owner, target, 40, 60))
    }

    private fun reference(offset: Int) =
        SourceDependency(
            owner,
            target,
            SourceDependencyKind.TYPE,
            SourceReference(owner.fileUrl, offset, 3, 1),
        )
}
