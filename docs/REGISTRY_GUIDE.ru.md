[English](../REGISTRY_GUIDE.md) · [Español](./REGISTRY_GUIDE.es.md) · [简体中文](./REGISTRY_GUIDE.zh-CN.md) · [Português (Brasil)](./REGISTRY_GUIDE.pt-BR.md) · [Русский](./REGISTRY_GUIDE.ru.md)

# Руководство по реестрам правил

Встроенный реестр правил ASM имеет идентификатор `default`. Указывать реестр необязательно: без явного выбора используется встроенный.

Реестр определяет доступные правила ASM и способы их выполнения. Он не зависит от `shamash/configs/asm.yml`, где выбираются и настраиваются правила конкретного проекта.

## Зависимость

Для компиляции провайдеров реестров используется `shamash-asm-core`, опубликованный в Maven Central.

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

Используйте версию, на которую рассчитан ваш провайдер. Во время выполнения `shamash-asm-core` предоставляется CLI или плагином IntelliJ; не включайте отдельную копию библиотеки в JAR провайдера.

## Провайдеры реестров для CLI

CLI обнаруживает внешние реестры с помощью Java `ServiceLoader`.

Реализуйте интерфейс:

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

Зарегистрируйте провайдер в файле:

```text
src/main/resources/META-INF/services/io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
```

указав полное имя класса провайдера:

```text
com.acme.shamash.AcmeRuleRegistryProvider
```

JAR провайдера должен находиться в classpath CLI во время выполнения. Для упакованной версии CLI поместите JAR в каталог `lib` дистрибутива до запуска.

Просмотрите доступные реестры:

```bash
shamash registry list
```

Выберите реестр для сканирования:

```bash
shamash scan --registry acme
```

Неизвестный идентификатор реестра приводит к ошибке конфигурации. При этом выводятся доступные идентификаторы.

## Провайдеры реестров для IntelliJ

Плагин IntelliJ предоставляет следующую точку расширения:

```text
io.shamash.asmRuleRegistryProvider
```

Используется тот же интерфейс провайдера, что и в CLI:

```text
io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
```

Дополнительный плагин IntelliJ может реализовать этот интерфейс и зарегистрировать реализацию:

```xml
<idea-plugin>
    <depends>io.shamash</depends>

    <extensions defaultExtensionNs="io.shamash">
        <asmRuleRegistryProvider implementation="com.acme.shamash.AcmeRuleRegistryProvider"/>
    </extensions>
</idea-plugin>
```

Подключённые реестры отображаются в интерфейсе выбора. Пустые и повторяющиеся идентификаторы игнорируются, а сведения об этом выводятся плагином.

## Совместимость

Провайдеры реестров бинарно связаны с версией API реестра правил, против которой они были скомпилированы.

Указывайте конкретную версию `shamash-asm-core` при компиляции и запускайте провайдер с совместимой версией. Если обновление изменяет API реестра правил, пересоберите провайдер и повторите тестирование.

`ServiceLoader` и точка расширения IntelliJ обеспечивают обнаружение провайдеров во время выполнения. Maven Central предоставляет API-зависимость для компиляции.
