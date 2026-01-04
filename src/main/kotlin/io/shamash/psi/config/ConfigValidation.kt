package io.shamash.psi.config

import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import java.io.Reader

object ConfigValidation {
    data class Result(
        val config: ShamashPsiConfigV1?,
        val errors: List<ValidationError>,
    ) {
        val ok: Boolean get() = errors.none { it.severity == ValidationSeverity.ERROR }
    }

    fun loadAndValidateV1(
        reader: Reader,
        schemaValidator: SchemaValidator = SchemaValidatorNetworkNt,
    ): Result {
        val raw =
            try {
                ConfigLoader.loadRaw(reader)
            } catch (e: Exception) {
                return Result(null, listOf(ValidationError("", "Failed to parse YAML: ${e.message}")))
            }

        val structural = schemaValidator.validate(raw)
        if (structural.isNotEmpty()) {
            return Result(null, structural)
        }

        val typed =
            try {
                ConfigLoader.bindV1(raw)
            } catch (e: Exception) {
                return Result(null, listOf(ValidationError("", "Failed to bind schema: ${e.message}")))
            }

        // also validate that enabled rules are executable by the engine
        val executableRuleIds: Set<String>? =
            try {
                // Adjust this import to your actual engine registry.
                // Example: io.shamash.psi.engine.registry.RuleRegistry.allIds()
                io.shamash.psi.engine.registry.RuleRegistry.allIds()
            } catch (_: Throwable) {
                // If engine registry isn't available in some contexts, degrade gracefully.
                null
            }

        val semantic = ConfigValidator.validateSemantic(typed, executableRuleIds)
        return Result(typed, semantic)
    }
}
