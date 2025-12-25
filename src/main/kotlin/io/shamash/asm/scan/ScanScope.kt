package io.shamash.asm.scan

/**
 * Controls how ASM scanning treats dependencies.
 */
enum class ScanScope {
    /**
     * Scan only module output directories.
     * External deps are NOT scanned; they are collapsed into buckets using reference names.
     */
    PROJECT_WITH_EXTERNAL_BUCKETS,

    /**
     * Scan only module output directories (no external bucketing).
     */
    PROJECT_ONLY,

    /**
     * Scan module outputs AND dependency jars (can explode in size; mostly for debugging).
     */
    ALL_SOURCES
}
