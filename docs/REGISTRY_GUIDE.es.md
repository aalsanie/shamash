[English](../REGISTRY_GUIDE.md) · [Español](./REGISTRY_GUIDE.es.md) · [简体中文](./REGISTRY_GUIDE.zh-CN.md) · [Português (Brasil)](./REGISTRY_GUIDE.pt-BR.md) · [Русский](./REGISTRY_GUIDE.ru.md)

# Guía de registros de reglas

Se incluye el registro de reglas ASM integrado `default`. Seleccionar un registro es opcional; si no se indica ninguno, se utiliza el integrado.

Un registro define qué reglas ASM están disponibles y cómo se ejecutan. Es independiente de `shamash/configs/asm.yml`, que selecciona y configura las reglas de cada proyecto.

## Dependencia

Los proveedores de registros se compilan contra `shamash-asm-core`, publicado en Maven Central.

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

Utiliza la versión correspondiente a tu proveedor. La CLI o el complemento IntelliJ proporciona `shamash-asm-core` durante la ejecución; no incluyas otra copia dentro del JAR del proveedor.

## Proveedores de registros para la CLI

La CLI descubre los registros externos mediante `ServiceLoader` de Java.

Implementa:

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

Registra el proveedor en:

```text
src/main/resources/META-INF/services/io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
```

con el nombre completo de la clase del proveedor:

```text
com.acme.shamash.AcmeRuleRegistryProvider
```

El JAR del proveedor debe estar en el classpath de ejecución de la CLI. Si utilizas la distribución empaquetada, colócalo en su directorio `lib` antes de ejecutar la herramienta.

Muestra los registros disponibles:

```bash
shamash registry list
```

Selecciona uno para un análisis:

```bash
shamash scan --registry acme
```

Un identificador de registro desconocido produce un error de configuración y muestra los identificadores disponibles.

## Proveedores de registros para IntelliJ

El complemento IntelliJ expone este punto de extensión:

```text
io.shamash.asmRuleRegistryProvider
```

Utiliza la misma interfaz de proveedor que la CLI:

```text
io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
```

Un complemento de IntelliJ adicional puede implementar esa interfaz y registrar su implementación:

```xml
<idea-plugin>
    <depends>io.shamash</depends>

    <extensions defaultExtensionNs="io.shamash">
        <asmRuleRegistryProvider implementation="com.acme.shamash.AcmeRuleRegistryProvider"/>
    </extensions>
</idea-plugin>
```

Los registros aportados aparecen en el selector de registros. El complemento ignora y notifica los identificadores duplicados o vacíos.

## Compatibilidad

Los proveedores de registros dependen de la compatibilidad binaria con la API de registros de reglas utilizada al compilarlos.

Compila contra una versión explícita de `shamash-asm-core` y ejecuta el proveedor con una versión compatible. Si una actualización modifica la API de registros, recompila el proveedor y vuelve a probarlo.

`ServiceLoader` y el punto de extensión de IntelliJ permiten descubrir proveedores en ejecución. Maven Central suministra la dependencia de API necesaria para compilarlos.
