package io.shamash.asm.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import io.shamash.asm.model.AsmIndex
import io.shamash.asm.scan.AsmScanner
import io.shamash.asm.scan.ScanScope

/**
 * Project-level holder for the latest ASM index.
 */
@Service(Service.Level.PROJECT)
class AsmIndexService(private val project: Project) {

    @Volatile
    private var latest: AsmIndex? = null

    fun getLatest(): AsmIndex? = latest

    /**
     * Builds a fresh ASM index.
     *
     * Default:
     *  - scans only module output directories
     *  - collapses external references into buckets (JDK / Spring / etc.)
     */
    fun rescan(
        indicator: ProgressIndicator? = null,
        scope: ScanScope = ScanScope.PROJECT_WITH_EXTERNAL_BUCKETS
    ): AsmIndex {
        indicator?.text = "Scanning project bytecode"
        val index = AsmScanner.scan(project, scope)

        latest = index
        project.messageBus.syncPublisher(AsmIndexListener.TOPIC).indexUpdated(index)
        logStats(index, scope)
        return index
    }

    private fun logStats(index: AsmIndex, scope: ScanScope) {
        val classCount = index.classes.size

        val totalReferences = index.classes.values
            .asSequence()
            .map { it.referencedInternalNames.size }
            .sum()

        val uniqueReferences = index.classes.values
            .asSequence()
            .flatMap { it.referencedInternalNames.asSequence() }
            .toSet()
            .size

        val externalBucketCount = index.externalBuckets.size

        LOG.info(
            buildString {
                append("Shamash scan complete. ")
                append("scope=$scope, ")
                append("projectClasses=$classCount, ")
                append("bytecodeRefs(total)=$totalReferences, ")
                append("bytecodeRefs(unique)=$uniqueReferences")
                if (scope == ScanScope.PROJECT_WITH_EXTERNAL_BUCKETS) {
                    append(", externalBuckets=$externalBucketCount")
                }
            }
        )
    }


    companion object {
        private val LOG = Logger.getInstance(AsmIndexService::class.java)
        fun getInstance(project: Project): AsmIndexService = project.service()
    }
}
