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
import io.shamash.asm.core.scan.ScanResult

internal object AsmScanPresentation {
    fun message(result: ScanResult): String =
        when {
            result.hasConfigErrors -> "Config invalid. Fix errors in the Config tab."
            result.truncated -> "Scan incomplete: the class limit was reached."
            result.hasScanErrors || result.hasFactsErrors -> "Scan incomplete: errors occurred during analysis."
            result.engine?.hasErrors == true -> "Scan finished with engine errors."
            !result.hasEngineResult && result.classUnits == 0 -> "No compiled JVM classes found. Build the project and scan again."
            !result.hasEngineResult -> "Scan did not reach engine execution."
            result.hasConfigWarnings -> "Scan complete with configuration warnings."
            result.engine?.findings?.isEmpty() == true -> "Scan complete. No findings."
            else -> "Scan complete. Findings: ${result.engine?.findings?.size ?: 0}"
        }

    fun notificationType(result: ScanResult): NotificationType =
        when {
            result.hasConfigErrors || result.hasScanErrors || result.hasFactsErrors || result.engine?.hasErrors == true -> {
                NotificationType.ERROR
            }

            !result.isSuccess || result.hasConfigWarnings -> {
                NotificationType.WARNING
            }

            else -> {
                NotificationType.INFORMATION
            }
        }
}
