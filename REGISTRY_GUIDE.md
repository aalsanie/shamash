# Registry Guide

Shamash ships with the built-in `default` ASM rule registry. Registry selection is optional; if no registry is selected, Shamash uses the built-in registry.

A registry defines which ASM rules are available and how they execute. It is separate from `shamash/configs/asm.yml`, which selects and configures rules for a project.

## Dependency

Registry providers compile against `shamash-asm-core`, published on Maven Central.

Gradle:

```kotlin
dependencies {
    compileOnly("io.github.aalsanie:shamash-asm-core:0.92.0")
}
```

Maven:

```xml
<dependency>
    <groupId>io.github.aalsanie</groupId>
    <artifactId>shamash-asm-core</artifactId>
    <version>0.92.0</version>
    <scope>provided</scope>
</dependency>
```

Use the Shamash version your provider targets. The Shamash CLI or IntelliJ plugin supplies `shamash-asm-core` at runtime; provider JARs should not bundle their own copy.

## CLI registry providers

The CLI discovers external registries with Java `ServiceLoader`.

Implement:

```kotlin
package com.acme.shamash

import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.asm.core.engine.rules.RuleRegistry
import io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider

class AcmeRuleRegistryProvider : AsmRuleRegistryProvider {
    override val id: String = "acme"
    override val displayName: String = "Acme Rules"

    override fun create(): RuleRegistry = DefaultRuleRegistry.create()
}
```

Register the provider in:

```text
src/main/resources/META-INF/services/io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
```

with the provider's fully qualified class name:

```text
com.acme.shamash.AcmeRuleRegistryProvider
```

The provider JAR must be on the Shamash CLI runtime classpath. With the packaged CLI, place the JAR in the distribution's `lib` directory before starting Shamash.

List available registries:

```bash
shamash registry list
```

Select one for a scan:

```bash
shamash scan --registry acme
```

An unknown registry id is a configuration error and Shamash prints the available ids.

## IntelliJ registry providers

The IntelliJ plugin exposes this extension point:

```text
io.shamash.asmRuleRegistryProvider
```

It uses the same provider interface as the CLI:

```text
io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
```

A companion IntelliJ plugin can implement that interface and register the implementation:

```xml
<idea-plugin>
    <depends>io.shamash</depends>

    <extensions defaultExtensionNs="io.shamash">
        <asmRuleRegistryProvider implementation="com.acme.shamash.AcmeRuleRegistryProvider"/>
    </extensions>
</idea-plugin>
```

Contributed registries appear in Shamash's registry selection UI. Duplicate or blank ids are ignored and reported by the plugin.

## Compatibility

Registry providers are binary-coupled to the Shamash rule-registry API they compile against.

Compile against an explicit `shamash-asm-core` version and run the provider with a compatible Shamash version. Rebuild and retest the provider when upgrading across versions that change the rule-registry API.

`ServiceLoader` and the IntelliJ extension point provide runtime discovery. Maven Central provides the compile-time API dependency.
