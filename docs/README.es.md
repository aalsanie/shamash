<p align="center">
  <img src="assets/shamash_logo.png" alt="Shamash" width="180"/>
</p>

# Shamash

**Detén la deriva arquitectónica de la JVM antes de que llegue a main.**

Shamash analiza aplicaciones Java/Kotlin compiladas, detecta ciclos de dependencias e infracciones arquitectónicas y puede impedir que nuevas infracciones lleguen al código en CI, sin necesidad de escribir pruebas de arquitectura.

- **CLI primero:** herramienta independiente para Java 17+ destinada al uso local y a CI.
- **Primer análisis sin configuración:** detecta riesgos arquitectónicos útiles antes de aprender el modelo de configuración.
- **Adecuado para proyectos existentes:** crea una línea base de la deuda actual una sola vez y, a partir de ahí, falla únicamente ante nuevas infracciones.
- **IntelliJ:** un único espacio de trabajo con Build Analysis y Source Analysis.
- **Capacidades avanzadas cuando hagan falta:** siguen disponibles los roles/reglas personalizados, facts, grafos, hotspots, registries y múltiples formatos de informe.

[![Release](https://img.shields.io/github/v/release/aalsanie/shamash?label=release)](https://github.com/aalsanie/shamash/releases)
![CI](https://github.com/aalsanie/shamash/actions/workflows/ci.yml/badge.svg)
[![License](https://img.shields.io/badge/license-Apache%202.0-4EB1BA.svg)](./LICENSE)

## Uso

Shamash analiza bytecode compilado. Primero, compila el proyecto:

```bash
./gradlew classes
# or: ./mvnw package
```

Después, ejecuta:

```bash
shamash scan
```

Este primer análisis no requiere configuración. El modo de descubrimiento es solo informativo: no crea configuración, informes ni líneas base en el proyecto, y nunca falla debido a los hallazgos.

Ejemplo de la salida:

```text
Shamash - discovery scan
Report-only mode. No project files were changed.

Shamash found 3 architecture issues

ERROR   graph.noCycles
        Dependency cycle detected ...

WARN    metrics.maxFanOut
        ...

642 classes scanned
1 errors, 2 warnings, 0 info

Ready to enforce architecture? Run: shamash init
```

Si Shamash no encuentra clases compiladas, detecta proyectos Gradle/Maven habituales y muestra el comando de compilación exacto que debe ejecutarse primero.

## Instalar la CLI

Requiere Java 17 o una versión posterior.

Descarga `shamash-cli-<version>.zip` y `SHA256SUMS.txt` desde GitHub Releases, verifica la suma de comprobación, extrae el archivo y utiliza:

```text
bin/shamash      # Linux/macOS
bin/shamash.bat  # Windows
```

El nombre del launcher forma parte del contrato del producto empaquetado y se somete a pruebas de humo en Linux, Windows y macOS antes de cada versión.

## Aplicar reglas de arquitectura en un proyecto

Crea la configuración predeterminada y reducida:

```bash
shamash init
```

Esto escribe:

```text
shamash/configs/asm.yml
```

La configuración starter predeterminada es intencionadamente pequeña y comienza con una regla para detectar ciclos de dependencias. Las políticas específicas de frameworks son opcionales:

```bash
shamash init --preset spring
```

La referencia avanzada completa sigue estando disponible:

```bash
shamash init --preset reference
```

Valida la configuración:

```bash
shamash validate
```

Después, ejecuta el análisis normalmente:

```bash
shamash scan
```

Los hallazgos se muestran de forma predeterminada. Usa `--all-findings` para ver la lista completa y `--verbose` para obtener diagnósticos del motor.

## Proyectos existentes: acepta la deuda actual una sola vez

Después de `shamash init`, ejecuta:

```bash
shamash baseline create
```

Este comando analiza el proyecto actual, escribe la línea base configurada y garantiza que `baseline.mode` sea `VERIFY`. Las líneas base existentes están protegidas; para reemplazarlas es necesario usar `--force`.

Confirma ambos archivos en el repositorio:

```text
shamash/configs/asm.yml
.shamash/baseline/asm-baseline.json
```

Con el modo de línea base `VERIFY`, los análisis posteriores suprimen las huellas ya aceptadas y muestran las nuevas infracciones.

## GitHub Actions

Compila la aplicación y después utiliza la Action oficial:

```yaml
name: Architecture

on:
  pull_request:
  push:
    branches: [main]

jobs:
  shamash:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "17"
      - run: ./gradlew classes
      - uses: aalsanie/shamash@v0.91.0
```

Para aplicar una configuración definida:

```yaml
      - uses: aalsanie/shamash@v0.91.0
        with:
          config: shamash/configs/asm.yml
          fail-on: ERROR
```

La Action verifica la suma de comprobación de la versión antes de ejecutarse.

## IntelliJ

Instala **Shamash** desde JetBrains Marketplace y abre:

```text
Tools → Shamash
```

Shamash utiliza una única ventana de herramientas. Sus áreas de primer nivel son:

- **Build Analysis** — comprobaciones arquitectónicas sobre bytecode compilado, hallazgos, roles, grafos e informes.
- **Source Analysis** — comprobaciones basadas en código fuente, supresiones y correcciones.

ASM y PSI siguen existiendo internamente porque resuelven problemas técnicos diferentes, pero los usuarios no necesitan conocer esos nombres de motores para empezar.

## Comportamiento en CI y códigos de salida

Los análisis configurados utilizan estos códigos de salida estables:

- `0` análisis correcto y hallazgos por debajo del umbral
- `2` problema de configuración/entrada (incluida la ausencia de bytecode compilado)
- `3` fallo en tiempo de ejecución/del motor
- `4` los hallazgos alcanzaron el umbral seleccionado mediante `--fail-on`

El modo de descubrimiento es solo informativo y devuelve `0` después de un análisis correcto, incluso si detecta riesgos arquitectónicos.

## Capacidades avanzadas

Los equipos avanzados pueden seguir utilizando:

- dependencias entre roles arquitectónicos y reglas de paquetes
- reglas de grafos de dependencias y límites de ciclos
- métricas de acoplamiento/tamaño de clases
- restricciones de API/anotaciones
- restricciones según el origen de los JAR
- exportación de facts y `shamash facts`
- análisis de grafos/hotspots/puntuación y `shamash analysis`
- formatos de informe JSON, SARIF, HTML y XML
- registries de reglas personalizados
- excepciones y líneas base

Consulta `docs/asm/` y `REGISTRY_GUIDE.md` para ver la referencia avanzada del motor y la configuración.

## Seguridad

No divulgues vulnerabilidades en un issue público. Sigue [`SECURITY.md`](./SECURITY.md).

## Licencia

Apache License 2.0. Consulta [`LICENSE`](./LICENSE).
