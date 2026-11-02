/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core

import io.shamash.psi.core.engine.EngineError
import io.shamash.psi.core.scan.ShamashProjectScanRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShamashProjectScanRunnerResultTest {
    @Test
    fun `engine errors make scan incomplete`() {
        val result =
            ShamashProjectScanRunner.ScanResult(
                findings = emptyList(),
                exportedReport = null,
                outputDir = null,
                baselineWritten = false,
                engineErrors =
                    listOf(
                        EngineError(
                            fileId = "src/main/java/com/example/Broken.java",
                            phase = "rule:crash",
                            message = "boom",
                            throwableClass = IllegalStateException::class.java.name,
                            ruleId = "arch.example",
                        ),
                    ),
            )

        assertFalse(result.isComplete)
    }

    @Test
    fun `scan without config or engine errors is complete`() {
        val result =
            ShamashProjectScanRunner.ScanResult(
                findings = emptyList(),
                exportedReport = null,
                outputDir = null,
                baselineWritten = false,
            )

        assertTrue(result.isComplete)
    }
}
