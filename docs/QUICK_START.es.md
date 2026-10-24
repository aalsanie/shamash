[English](../QUICK_START.md) · [Español](./QUICK_START.es.md) · [简体中文](./QUICK_START.zh-CN.md) · [Português (Brasil)](./QUICK_START.pt-BR.md) · [Русский](./QUICK_START.ru.md)

# Guía rápida de Shamash

## 1. Compila el proyecto JVM

```bash
./gradlew classes
# or
./mvnw package
```

## 2. Analiza sin configurar

```bash
shamash scan
```

El primer análisis utiliza un perfil integrado de detección que solo genera resultados. No modifica archivos del proyecto y los hallazgos no provocan un código de salida de error.

## 3. Activa la aplicación de reglas

```bash
shamash init
shamash validate
```

Para los valores predeterminados de roles y límites de Spring:

```bash
shamash init --preset spring
```

Para una configuración completa de referencia:

```bash
shamash init --preset reference
```

## 4. Proyectos existentes: acepta la deuda actual una sola vez

```bash
shamash baseline create
```

Incluye en el repositorio la configuración y la línea base generadas. Después:

```bash
shamash scan
```

mostrará las infracciones nuevas según la política de umbrales y línea base configurada.

## 5. Integración continua

```yaml
- run: ./gradlew classes
- uses: aalsanie/shamash@v0.92.0
  with:
    config: shamash/configs/asm.yml
    fail-on: ERROR
```

La acción descarga `shamash-cli-0.92.0.zip`, lo verifica con `SHA256SUMS.txt` y propaga el código de salida del análisis.

## Opciones útiles de la CLI

```bash
shamash scan --all-findings
shamash scan --verbose
shamash scan --print-analysis-summary
shamash scan --fail-on WARNING
shamash scan --fail-on NONE
```

`--print-findings` sigue admitiéndose por compatibilidad con 0.x, pero ya no es necesario: los hallazgos se muestran de forma predeterminada.

## IntelliJ

Instala el complemento y abre:

```text
Tools → Shamash
```

Usa **Build Analysis** para comprobar el código compilado y **Source Analysis** para recibir resultados y correcciones sobre el código fuente en el IDE.

## Verificación de integridad

Cada versión incluye un archivo de sumas de verificación:

```text
SHA256SUMS.txt
```

Linux:

```bash
grep 'shamash-cli-0.92.0.zip' SHA256SUMS.txt | sha256sum -c -
```

macOS:

```bash
EXPECTED=$(awk '/shamash-cli-0.92.0.zip/ {print $1}' SHA256SUMS.txt)
ACTUAL=$(shasum -a 256 shamash-cli-0.92.0.zip | awk '{print $1}')
test "$EXPECTED" = "$ACTUAL"
```

Windows PowerShell:

```powershell
$line = Get-Content .\SHA256SUMS.txt |
  Where-Object { $_ -match '\s+shamash-cli-0\.92\.0\.zip$' } |
  Select-Object -First 1
if (-not $line) { throw "Checksum entry not found" }
$expected = ($line -split '\s+')[0].ToLowerInvariant()
$actual = (Get-FileHash .\shamash-cli-0.92.0.zip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($expected -ne $actual) { throw "Checksum mismatch" }
"Checksum OK"
```
