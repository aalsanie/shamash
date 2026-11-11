/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceTypeWitnessIndexTest {
    private val owner = SourceEntityId("module", "file:///src", "file:///src/Owner.java", SourceEntityKind.CLASS, "demo.Owner")
    private val target = SourceTarget.Internal(
        SourceEntityId("module", "file:///src", "file:///src/Target.java", SourceEntityKind.CLASS, "demo.Target"),
    )

    @Test
    fun `matches exact owner target and inclusive source range`() {
        val index = SourceTypeWitnessIndex(listOf(reference(20)))
        assertFalse(index.contains(owner, target, 0, 19))
        assertTrue(index.contains(owner, target, 20, 20))
        assertTrue(index.contains(owner, target, 0, 20))
        assertFalse(index.contains(owner, target, 21, 99))
        assertFalse(index.contains(owner.copy(moduleId = "other"), target, 0, 99))
        assertFalse(index.contains(owner, SourceTarget.External("demo.Target"), 0, 99))
    }

    @Test
    fun `ignores non type evidence and indexes subsequent type witnesses`() {
        val index = SourceTypeWitnessIndex(listOf(reference(10).copy(kind = SourceDependencyKind.CALL)))
        assertFalse(index.contains(owner, target, 0, 20))
        index.add(reference(13))
        assertTrue(index.contains(owner, target, 12, 13))
        assertFalse(index.contains(owner, target, 14, 20))
        index.add(reference(33))
        assertTrue(index.contains(owner, target, 30, 40))
        assertFalse(index.contains(owner, target, 20, 30))
    }

    @Test
    fun `dense witnesses remain independently searchable`() {
        val index = SourceTypeWitnessIndex((0 until 5_000).map { reference(it * 4) })
        assertTrue(index.contains(owner, target, 19_996, 19_996))
        assertFalse(index.contains(owner, target, 19_997, 19_999))
    }

    private fun reference(offset: Int) = SourceDependency(
        owner,
        target,
        SourceDependencyKind.TYPE,
        SourceReference(owner.fileUrl, offset, 3, 1),
    )
}
