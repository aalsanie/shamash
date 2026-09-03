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
package io.shamash.intellij.plugin.asm.ui.actions

import com.intellij.notification.NotificationType
import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.config.ValidationSeverity
import io.shamash.asm.core.engine.EngineError
import io.shamash.asm.core.engine.EngineResult
import io.shamash.asm.core.engine.EngineRunSummary
import io.shamash.asm.core.facts.FactsError
import io.shamash.asm.core.scan.ScanError
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ScanResult
import junit.framework.TestCase
import java.nio.file.Path

class AsmScanPresentationTest : TestCase() {
    fun testCompleteScanWithWarningsRemainsSuccessful() {
        val result = complete().copy(configErrors = listOf(ValidationError("rules", "unknown", ValidationSeverity.WARNING)))
        assertTrue(result.isSuccess)
        assertEquals(NotificationType.WARNING, AsmScanPresentation.notificationType(result))
        assertTrue(AsmScanPresentation.message(result).contains("complete with configuration warnings"))
    }

    fun testTruncatedScanCannotBePresentedAsComplete() {
        val result = complete().copy(truncated = true)
        assertEquals(NotificationType.WARNING, AsmScanPresentation.notificationType(result))
        assertTrue(AsmScanPresentation.message(result).contains("incomplete"))
    }

    fun testScanAndFactFailuresArePresentedAsErrors() {
        val base = complete()
        val scan = base.copy(scanErrors = listOf(ScanError(ScanError.Phase.BYTECODE_SCAN, "unreadable")))
        val facts = base.copy(factsErrors = listOf(FactsError("App", "parse", "invalid")))
        for (result in listOf(scan, facts)) {
            assertEquals(NotificationType.ERROR, AsmScanPresentation.notificationType(result))
            assertTrue(AsmScanPresentation.message(result).contains("incomplete"))
        }
    }

    fun testEngineFailuresArePresentedAsErrors() {
        val base = complete()
        val result = base.copy(engine = requireNotNull(base.engine).copy(errors = listOf(EngineError.internal("failed"))))
        assertEquals(NotificationType.ERROR, AsmScanPresentation.notificationType(result))
        assertTrue(AsmScanPresentation.message(result).contains("engine errors"))
    }

    fun testCompleteScanUsesInformationNotification() {
        val result = complete()
        assertEquals(NotificationType.INFORMATION, AsmScanPresentation.notificationType(result))
        assertEquals("Scan complete. No findings.", AsmScanPresentation.message(result))
    }

    private fun complete(): ScanResult {
        val project = Path.of(".")
        val summary = EngineRunSummary("test", project, "Shamash", "test", 0L, 1L)
        return ScanResult(ScanOptions(project), classUnits = 1, engine = EngineResult.success(summary, emptyList()))
    }
}
