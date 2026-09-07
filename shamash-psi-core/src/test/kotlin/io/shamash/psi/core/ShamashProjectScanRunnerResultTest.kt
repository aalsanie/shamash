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
