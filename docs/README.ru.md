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

**Проверка архитектуры Java и Kotlin без написания архитектурных тестов.**

Находите циклические зависимости, контролируйте границы пакетов и архитектурных ролей и выявляйте области высокой связанности. Проверяйте скомпилированный код через CLI или CI, а в IntelliJ получайте диагностику и исправления на уровне исходного кода.

[![Release](https://img.shields.io/github/v/release/aalsanie/shamash?label=release)](https://github.com/aalsanie/shamash/releases)
![CI](https://github.com/aalsanie/shamash/actions/workflows/ci.yml/badge.svg)
[![Лицензия](https://img.shields.io/badge/license-Apache%202.0-4EB1BA.svg)](../LICENSE)

## Первое сканирование

Требуются **Java 17 или новее** и скомпилированные классы. Скачайте [архив CLI и файл контрольных сумм](https://github.com/aalsanie/shamash/releases/latest), проверьте архив и добавьте каталог `bin` в `PATH` (`shamash` в Unix, `shamash.bat` в Windows).

```bash
./gradlew classes  # or ./mvnw package
shamash scan
```

Без конфигурации сканирование выводит найденные проблемы, но не изменяет файлы проекта и не завершается с ошибкой только из-за нарушений архитектуры.

## Применение правил

```bash
shamash init
shamash validate
shamash scan --fail-on ERROR
```

Команда `init` создаёт `shamash/configs/asm.yml` с правилом обнаружения циклических зависимостей. Используйте `shamash init --preset spring` для настроек Spring или `--preset reference` для полного примера конфигурации.

Для существующего проекта запустите `shamash baseline create`, чтобы зафиксировать текущие нарушения. Добавьте обновлённую конфигурацию и `.shamash/baseline/asm-baseline.json` в систему контроля версий — последующие проверки будут выявлять новые нарушения.

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

Для Maven замените шаг сборки на `./mvnw package`. Перед сканированием Action проверяет контрольную сумму загруженного CLI.

**Коды завершения:** `0` — сканирование успешно завершено, порог не достигнут; `2` — ошибка конфигурации или входных данных; `3` — сбой или неполное сканирование; `4` — количество или уровень нарушений достигли заданного порога.

## IntelliJ

[Установите плагин из JetBrains Marketplace](https://plugins.jetbrains.com/plugin/29504-shamash) и откройте **Tools → Shamash**. **Build Analysis** проверяет скомпилированный код, а **Source Analysis** предоставляет диагностику, подавление предупреждений и исправления на уровне исходников.

## Библиотека

Доступна в Maven Central для Java 17 и новее.

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

`isSuccess` подтверждает успешное завершение сканирования, но не отсутствие нарушений.

## Дополнительные возможности

Настраивайте ограничения API и аннотаций, правила происхождения JAR-файлов, метрики зависимостей и проверки графов. Экспортируйте отчёты JSON, SARIF, HTML или XML, просматривайте факты, проблемные области и оценки архитектуры. При необходимости подключайте собственные реестры правил.

[Параметры CLI и проверка контрольных сумм](./QUICK_START.ru.md) · [Примеры конфигурации](../docs/asm/schema/) · [Пользовательские реестры правил](./REGISTRY_GUIDE.ru.md) · [Тесты производительности](../benchmarks/)

[История изменений](../CHANGELOG.md) · [Безопасность](../SECURITY.md) · [Участие в разработке](../CONTRIBUTING.md) · [License](../LICENSE)
