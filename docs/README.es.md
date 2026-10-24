<p align="center">
  <a href="../README.md">English</a> •
  <a href="./README.es.md">Español</a> •
  <a href="./README.zh-CN.md">简体中文</a> •
  <a href="./README.pt-BR.md">Português (Brasil)</a> •
  <a href="./README.ru.md">Русский</a>
</p>

<p align="center">
  <img src="../assets/shamash_logo.png" alt="Shamash" width="180"/>
</p>

# Shamash

**Controla la arquitectura de Java y Kotlin sin escribir pruebas de arquitectura.**

Detecta ciclos de dependencias, aplica límites entre paquetes y roles e identifica puntos críticos de acoplamiento. Analiza el código compilado desde la CLI o CI y obtén hallazgos y correcciones sobre el código fuente en IntelliJ.

[![Release](https://img.shields.io/github/v/release/aalsanie/shamash?label=release)](https://github.com/aalsanie/shamash/releases)
![CI](https://github.com/aalsanie/shamash/actions/workflows/ci.yml/badge.svg)
[![Licencia](https://img.shields.io/badge/license-Apache%202.0-4EB1BA.svg)](../LICENSE)

## Primer análisis

Necesitas **Java 17 o posterior** y las clases compiladas. Descarga el [archivo de la CLI y las sumas de verificación](https://github.com/aalsanie/shamash/releases/latest), verifica el archivo y añade el directorio `bin` al `PATH` (`shamash` en Unix, `shamash.bat` en Windows).

```bash
./gradlew classes  # or ./mvnw package
shamash scan
```

Sin configuración, el análisis muestra los hallazgos sin modificar archivos del proyecto ni devolver un error por las infracciones arquitectónicas.

## Aplicar reglas

```bash
shamash init
shamash validate
shamash scan --fail-on ERROR
```

`init` crea `shamash/configs/asm.yml` con una regla para detectar ciclos de dependencias. Usa `shamash init --preset spring` para los valores predeterminados de Spring o `--preset reference` para el ejemplo completo.

En un proyecto existente, ejecuta `shamash baseline create` para aceptar los hallazgos actuales. Incluye en el repositorio la configuración actualizada y `.shamash/baseline/asm-baseline.json`; los siguientes análisis mostrarán las infracciones nuevas.

## GitHub Actions

```yaml
name: Architecture

on: [pull_request, push]

jobs:
  architecture:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
      - run: ./gradlew classes
      - uses: aalsanie/shamash@v0.92.0
        with:
          config: shamash/configs/asm.yml
          fail-on: ERROR
```

Para Maven, sustituye el paso de compilación por `./mvnw package`. La acción verifica la suma de comprobación de la CLI antes de analizar.

**Códigos de salida:** `0` análisis completado sin alcanzar el umbral; `2` error de configuración o entrada; `3` análisis fallido o incompleto; `4` hallazgos que alcanzan o superan el umbral.

## IntelliJ

[Instala el complemento desde JetBrains Marketplace](https://plugins.jetbrains.com/plugin/29504-shamash) y abre **Tools → Shamash**. **Build Analysis** examina el código compilado; **Source Analysis** ofrece hallazgos, exclusiones y correcciones sobre el código fuente.

## Biblioteca

Disponible en Maven Central para Java 17 o posterior.

**Gradle**
```kotlin
dependencies {
    implementation("io.github.aalsanie:shamash-asm-core:0.92.0")
}
```

**Maven**
```xml
<dependency>
    <groupId>io.github.aalsanie</groupId>
    <artifactId>shamash-asm-core</artifactId>
    <version>0.92.0</version>
</dependency>
```

```kotlin
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ShamashAsmScanRunner
import java.nio.file.Path

val result = ShamashAsmScanRunner().run(ScanOptions(projectBasePath = Path.of(".")))
check(result.isSuccess) { result.toString() }
val findings = requireNotNull(result.engine).findings
```

`isSuccess` confirma que el análisis terminó correctamente, no que esté libre de infracciones.

## Funciones avanzadas

Configura restricciones de API y anotaciones, reglas sobre el origen de archivos JAR, métricas de dependencias y comprobaciones de grafos. Exporta informes JSON, SARIF, HTML o XML y consulta hechos, puntos críticos y puntuaciones arquitectónicas. Amplía las reglas mediante registros personalizados.

[Opciones de la CLI y sumas de verificación](./QUICK_START.es.md) · [Ejemplos de configuración](../docs/asm/schema/) · [Registros personalizados](./REGISTRY_GUIDE.es.md) · [Pruebas de rendimiento](../benchmarks/)

[Historial de cambios](../CHANGELOG.md) · [Seguridad](../SECURITY.md) · [Contribuir](../CONTRIBUTING.md) · [License](../LICENSE)
