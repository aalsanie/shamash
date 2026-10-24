[English](../QUICK_START.md) · [Español](./QUICK_START.es.md) · [简体中文](./QUICK_START.zh-CN.md) · [Português (Brasil)](./QUICK_START.pt-BR.md) · [Русский](./QUICK_START.ru.md)

# Краткое руководство по Shamash

## 1. Соберите JVM-проект

```bash
./gradlew classes
# or
./mvnw package
```

## 2. Запустите проверку без конфигурации

```bash
shamash scan
```

Первое сканирование использует встроенный режим обнаружения без применения порогов отказа. Оно не изменяет файлы проекта и не завершает процесс с ошибкой из-за найденных нарушений.

## 3. Включите контроль соблюдения правил

```bash
shamash init
shamash validate
```

Для стандартных правил ролей и границ архитектуры Spring:

```bash
shamash init --preset spring
```

Для полной справочной конфигурации:

```bash
shamash init --preset reference
```

## 4. Зафиксируйте существующие нарушения в базовой линии

```bash
shamash baseline create
```

Добавьте созданные конфигурацию и базовую линию в систему контроля версий. Затем:

```bash
shamash scan
```

выявит новые нарушения в соответствии с настроенными порогами и правилами базовой линии.

## 5. Непрерывная интеграция

```yaml
- run: ./gradlew classes
- uses: aalsanie/shamash@v0.92.0
  with:
    config: shamash/configs/asm.yml
    fail-on: ERROR
```

Action загружает `shamash-cli-0.92.0.zip`, проверяет архив по `SHA256SUMS.txt` и передаёт код завершения сканирования.

## Полезные параметры CLI

```bash
shamash scan --all-findings
shamash scan --verbose
shamash scan --print-analysis-summary
shamash scan --fail-on WARNING
shamash scan --fail-on NONE
```

Параметр `--print-findings` сохранён для совместимости с версией 0.x, но больше не обязателен: найденные проблемы отображаются по умолчанию.

## IntelliJ

Установите плагин и откройте:

```text
Tools → Shamash
```

Используйте **Build Analysis** для проверки скомпилированного кода и **Source Analysis** для диагностики и исправлений непосредственно в IDE.

## Проверка контрольной суммы

Каждый выпуск включает единый файл контрольных сумм:

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
