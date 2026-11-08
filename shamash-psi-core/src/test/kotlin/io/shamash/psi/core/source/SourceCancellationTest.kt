/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import com.intellij.openapi.progress.ProcessCanceledException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SourceCancellationTest {
    @Test
    fun `reflection wrappers preserve platform cancellation`() {
        val cancelled = ProcessCanceledException()
        assertFailsWith<ProcessCanceledException> {
            rethrowSourceCancellation(InvocationTargetException(cancelled))
        }
    }

    @Test
    fun `nested cancellation exceptions propagate as platform cancellation`() {
        assertFailsWith<ProcessCanceledException> {
            rethrowSourceCancellation(IllegalStateException("wrapped", CancellationException()))
        }
    }

    @Test
    fun `ordinary extractor exceptions are not treated as cancellation`() {
        rethrowSourceCancellation(IllegalStateException("broken resolution"))
    }
}
