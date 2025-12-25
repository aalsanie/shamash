package io.shamash.psi.architecture

import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.*
import com.intellij.psi.search.searches.ReferencesSearch
import io.shamash.psi.util.EntryPointUtil

object DeadCodeRules {

    // Framework / test / lifecycle annotations that often mean "used indirectly".
    private val indirectUseMethodAnnotations = setOf(
        // JUnit 4/5
        "org.junit.Test",
        "org.junit.Before",
        "org.junit.After",
        "org.junit.BeforeClass",
        "org.junit.AfterClass",
        "org.junit.jupiter.api.Test",
        "org.junit.jupiter.api.BeforeEach",
        "org.junit.jupiter.api.AfterEach",
        "org.junit.jupiter.api.BeforeAll",
        "org.junit.jupiter.api.AfterAll",
        "org.junit.jupiter.api.Disabled",
        "org.junit.jupiter.params.ParameterizedTest",

        // Spring web endpoints (indirect invocation)
        "org.springframework.web.bind.annotation.RequestMapping",
        "org.springframework.web.bind.annotation.GetMapping",
        "org.springframework.web.bind.annotation.PostMapping",
        "org.springframework.web.bind.annotation.PutMapping",
        "org.springframework.web.bind.annotation.DeleteMapping",
        "org.springframework.web.bind.annotation.PatchMapping",

        // Common DI/lifecycle
        "javax.annotation.PostConstruct",
        "javax.annotation.PreDestroy",
        "jakarta.annotation.PostConstruct",
        "jakarta.annotation.PreDestroy",
    )

    private val indirectUseClassAnnotations = setOf(
        "org.springframework.stereotype.Controller",
        "org.springframework.web.bind.annotation.RestController",
        "org.springframework.stereotype.Service",
        "org.springframework.stereotype.Repository",
        "org.springframework.stereotype.Component",
        "org.springframework.boot.autoconfigure.SpringBootApplication",
        "org.springframework.context.annotation.Configuration"
    )

    // Fields are the easiest to delete incorrectly, so we are extra conservative.
    private val indirectUseFieldAnnotations = setOf(
        // Spring DI
        "org.springframework.beans.factory.annotation.Autowired",
        "org.springframework.beans.factory.annotation.Value",
        "org.springframework.beans.factory.annotation.Qualifier",

        // Jakarta / Javax DI
        "javax.inject.Inject",
        "javax.annotation.Resource",
        "jakarta.inject.Inject",
        "jakarta.annotation.Resource",

        // JPA / persistence
        "javax.persistence.Id",
        "javax.persistence.Column",
        "javax.persistence.OneToOne",
        "javax.persistence.OneToMany",
        "javax.persistence.ManyToOne",
        "javax.persistence.ManyToMany",
        "javax.persistence.Embedded",
        "javax.persistence.EmbeddedId",
        "javax.persistence.Transient",
        "jakarta.persistence.Id",
        "jakarta.persistence.Column",
        "jakarta.persistence.OneToOne",
        "jakarta.persistence.OneToMany",
        "jakarta.persistence.ManyToOne",
        "jakarta.persistence.ManyToMany",
        "jakarta.persistence.Embedded",
        "jakarta.persistence.EmbeddedId",
        "jakarta.persistence.Transient",

        // Jackson / serialization
        "com.fasterxml.jackson.annotation.JsonProperty",
        "com.fasterxml.jackson.annotation.JsonIgnore",
        "com.fasterxml.jackson.annotation.JsonCreator",
        "com.fasterxml.jackson.annotation.JsonAlias",
        "com.fasterxml.jackson.annotation.JsonUnwrapped",
        "com.fasterxml.jackson.annotation.JsonValue",

        // Lombok (fields may be used via generated accessors)
        "lombok.Getter",
        "lombok.Setter",
        "lombok.Data",
        "lombok.Value",
        "lombok.Builder"
    )

    fun isUnusedClass(psiClass: PsiClass): Boolean {
        if (!psiClass.isValid) return false
        if (psiClass is PsiAnonymousClass) return false
        if (psiClass is PsiTypeParameter) return false
        if (psiClass.containingClass != null && psiClass.hasModifierProperty(PsiModifier.PUBLIC)) {
            return false
        }

        val layer = LayerDetector.detect(psiClass)
        if (layer == Layer.CONTROLLER || layer == Layer.CLI) return false

        if (EntryPointUtil.isEntryPoint(psiClass)) return false
        if (hasAnyAnnotation(psiClass, indirectUseClassAnnotations)) return false
        if (hasMainMethod(psiClass)) return false

        val isPublic = psiClass.hasModifierProperty(PsiModifier.PUBLIC)

        // Default: do NOT report public top-level classes (could be API/reflection/library use),
        // except for UTIL classes that live in project source.
        if (isPublic && layer != Layer.UTIL) return false
        if (isPublic && layer == Layer.UTIL && !isInProjectSource(psiClass)) return false

        return ReferencesSearch.search(psiClass).findFirst() == null
    }

    private fun isInProjectSource(psiClass: PsiClass): Boolean {
        val vFile = psiClass.containingFile?.virtualFile ?: return false
        val index = ProjectRootManager.getInstance(psiClass.project).fileIndex

        // "Source content" includes main + test sources; fine for dead-code detection.
        // If you want to exclude tests, we can tighten this later.
        if (!index.isInSourceContent(vFile)) return false

        // Avoid flagging library source/classes.
        if (index.isInLibrary(vFile)) return false

        return true
    }

    fun isUnusedMethod(method: PsiMethod): Boolean {
        if (!method.isValid) return false
        if (method.isConstructor) return false
        if (method.containingClass == null) return false
        if (method.name == "main" && method.hasModifierProperty(PsiModifier.STATIC)) return false

        val containing = method.containingClass ?: return false
        val layer = LayerDetector.detect(containing)
        if (layer == Layer.CONTROLLER || layer == Layer.CLI) return false

        // Only private methods (safe default).
        if (!method.hasModifierProperty(PsiModifier.PRIVATE)) return false

        // Skip overrides / interface implementations.
        if (method.findSuperMethods().isNotEmpty()) return false

        // Skip annotated indirect uses.
        if (hasAnyAnnotation(method, indirectUseMethodAnnotations)) return false

        // IntelliJ entrypoint
        if (EntryPointUtil.isEntryPoint(method)) return false

        // Must have no references.
        return ReferencesSearch.search(method).findFirst() == null
    }

    /**
     * Very conservative:
     * - only private fields
     * - skips DI/persistence/serialization/lombok annotated fields
     * - skips controllers/cli layers
     * - skips constants / serialVersionUID
     */
    fun isUnusedField(field: PsiField): Boolean {
        if (!field.isValid) return false
        if (field is PsiEnumConstant) return false

        val containing = field.containingClass ?: return false
        val layer = LayerDetector.detect(containing)
        if (layer == Layer.CONTROLLER || layer == Layer.CLI) return false

        // Only private fields (safe default).
        if (!field.hasModifierProperty(PsiModifier.PRIVATE)) return false

        // Common "do not touch" field patterns.
        if (field.name == "serialVersionUID") return false
        if (field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL)) {
            // constants can be used externally/reflection; be conservative
            return false
        }

        // Skip annotated indirect uses (DI/JPA/Jackson/Lombok…).
        if (hasAnyAnnotation(field, indirectUseFieldAnnotations)) return false

        // IntelliJ entrypoint/implicit usage mechanisms.
        if (EntryPointUtil.isEntryPoint(field)) return false

        // Must have no references.
        return ReferencesSearch.search(field).findFirst() == null
    }

    private fun hasMainMethod(psiClass: PsiClass): Boolean =
        psiClass.methods.any { it.name == "main" && it.hasModifierProperty(PsiModifier.STATIC) }

    private fun hasAnyAnnotation(owner: PsiModifierListOwner, fqns: Set<String>): Boolean {
        val modifierList = owner.modifierList ?: return false
        val annotations = modifierList.annotations
        return annotations.any { ann ->
            val q = ann.qualifiedName
            q != null && q in fqns
        }
    }
}
