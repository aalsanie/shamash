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

/** Never turn wrapped platform cancellation or interruption into an ordinary resolution error. */
internal fun rethrowSourceCancellation(failure: Throwable) {
    val seen = HashSet<Throwable>()
    var cause: Throwable? = failure
    while (cause != null && seen.add(cause)) {
        when (cause) {
            is ProcessCanceledException -> {
                throw cause
            }

            is CancellationException -> {
                throw ProcessCanceledException()
            }

            is InterruptedException -> {
                Thread.currentThread().interrupt()
                throw ProcessCanceledException()
            }
        }
        cause = if (cause is InvocationTargetException) cause.targetException ?: cause.cause else cause.cause
    }
}
