# Shamash PSI – Schema v1 Docs

This folder documents **Shamash PSI Config Schema v1**, including:

- Full top-level config structure
- Roles and matcher grammar
- Rules definition shape
- Exceptions and suppressions
- Examples can be found [here](./examples)

## What these docs are (and are not)

- Accurate to the JSON Schema
- Covers all matcher operators and leaves with examples.
- Covers all exception match fields with examples.
- Covers the complete rule definition shape (`type`, `name`, `enabled`, `severity`, `roles`, `scope`, `params`).

## Quick start

Start with an example configuration from [here](./examples). 

Then customize:
- `project.sourceGlobs`
- `roles`
- `rules` (set `type`/`name` and `params` according to your shipped RuleSpecs)
- `exceptions`

## Role
Is a **map** of: `roleId -> roleDef`, where you defined your targeted role e.g. controller

```yaml
roles:
  controller:
    priority: 100
    description: "optional"
    match: <matcher>
```

### 1. Priority
An Integer between 0..100 where higher priority wins when multiple roles match the same target.

### 2. matcher
A recursive expression that must provide exactly one of these operators `anyOf` | `allOf` | `not` and a leaf.

<b>anyOf</b>
```yaml
match:
  anyOf:
    - classNameEndsWith: "Controller"
    - annotation: "org.springframework.web.bind.annotation.RestController"
```

<b>allOf</b>
```yaml
match:
  allOf:
    - packageContainsSegment: "service"
    - classNameEndsWith: "Service"
```

<b>not</b>
```yaml
match:
  allOf:
    - packageContainsSegment: "controller"
    - not:
        packageContainsSegment: "generated"
```

<b>annotation</b>
```yaml
match:
  annotation: "org.springframework.stereotype.Service"
```

<b>annotation fqn prefix</b>
```yaml
match:
  annotationPrefix: "org.springframework."
```

<b>regex applied to a package name</b>
```yaml
match:
  packageRegex: ".*\\.controller(\\..*)?$"
```

<b>contains a package segment</b>
```yaml
match:
  packageContainsSegment: "controller"
```

<b>regex applied to class name</b>
```yaml
match:
  classNameRegex: ".*(Controller|Resource)$"
```

<b>suffix match</b>
```yaml
match:
  classNameEndsWith: "Controller"
```

<b>list of suffixes</b>
```yaml
match:
  classNameEndsWithAny:
    - "Dao"
    - "Repository"
```

<b>has a main method</b>
```yaml
match:
  hasMainMethod: true
```

<b>implements - interface fqn</b>
```yaml
match:
  implements: "java.lang.Runnable"
```

<b>extends - super class fqn</b>
```yaml
match:
  extends: "org.springframework.boot.autoconfigure.SpringBootApplication"
```

## Rule
A list of `RuleDefinition` object that contains: type, name, roles, enabled, params, scope and severity.
The combination of type and name makes up a unique `ruleKey`.
If a role list is not defined, then the rule will apply to all.

### 0. Currently implemented rules
- naming.bannedSuffixes
- arch.forbiddenRoleDependencies
- deadcode.unusedPrivateMembers
- metrics.maxMethodsByRole
- packages.rolePlacement
- packages.rootPackage

### 1. Scope
Narrows where a rule is applied.

```yaml
scope:
  includeRoles: ["controller"]
  excludeRoles: ["repository"]
  includePackages: ["^com\\.acme\\.app\\..*"]
  excludePackages: [".*\\.legacy(\\..*)?$"]
  includeGlobs: ["src/main/kotlin/**"]
  excludeGlobs: ["**/generated/**"]
```

### 2. Params
A set of allowed params for each role specification:


- **naming.bannedSuffixes**: Forbid class name suffixes (e.g. *Action)
  - banned: required::list<String>
  - applyToRoles: optional::list<RoleId>
  - caseSensitive: optional::boolean


- **arch.forbiddenRoleDependencies**: Prevent dependencies between architectural roles
  - kinds: required::list<String>
  - forbidden: required::list<from, to[], message?>


- **metrics.maxMethodsByRole**: Enforce method count limits per role
  - limits (required list of { role, max })
  - countKinds (optional)
  - ignoreMethodNameRegex (optional)


- **packages.rolePlacement**: Enforce where roles must live package-wise
  - expected: required::map<roleId: { packageRegex }>


- **packages.rootPackage**: Enforce project root package consistency
  - mode: required::AUTO | EXPLICIT 
  - value: required when EXPLICIT


- **deadcode.unusedPrivateMembers**: Detect unused private fields/methods/classes
  - check: required object: { fields, methods, classes })
  - ignoreIfAnnotatedWithExact
  - ignoreIfAnnotatedWithPrefix
  - ignoreIfContainingClassAnnotatedWithExact
  - ignoreIfContainingClassAnnotatedWithPrefix
  - ignoreRoles
  - ignoreNameRegex

## Exceptions
A list*of exception definitions to supress rules where each exception:
- has an `id` and `reason`
- optionally has `expiresOn` (YYYY-MM-DD)
- has a `match` object (must contain at least one field)
- has a `suppress` list (one or more rule ids)

```yaml
exceptions:
  - id: "EX-001"
    reason: "Legacy module; cleanup scheduled"
    expiresOn: "2026-06-01"
    match:
      fileGlob: "**/legacy/**"
    suppress:
      - "deadcode.unusedPrivateMembers"
      - "metrics.maxMethodsByRole"
```
### 1. Match
A match object can include one or more of:

<b>fileGlob</b>
```yaml
match:
  fileGlob: "**/generated/**"
```

<b>packageRegex</b>
```yaml
match:
  packageRegex: ".*\\.legacy(\\..*)?$"
```

<b>classNameRegex</b>
```yaml
match:
  classNameRegex: ".*(Dto|Entity)$"
```

<b>methodNameRegex</b>
```yaml
match:
  methodNameRegex: "^(get|set).*"
```

<b>fieldNameRegex</b>
```yaml
match:
  fieldNameRegex: "^m[A-Z].*"
```

<b>hasAnnotation</b>
```yaml
match:
  hasAnnotation: "org.junit.jupiter.api.Test"
```

<b>hasAnnotationPrefix</b>
```yaml
match:
  hasAnnotationPrefix: "org.springframework."
```

<b>role</b>
```yaml
match:
  role: "repository"
```

### 2. Supress
A list of `ruleId` of the targeted rules.  A rule id is made of `name.type`.


Below is a list of allowed rule keys below:
- `naming.bannedSuffixes`
- `arch.forbiddenRoleDependencies`
- `deadcode.unusedPrivateMembers`
- `metrics.maxMethodsByRole`
- `packages.rolePlacement`
- `packages.rootPackage`

## Project validation
Schema v1 requires you to define project validation.
```yaml
project:
  validation:
    unknownRule: WARN  # ERROR | WARN | IGNORE (case-sensitive)
```

- ERROR: fail validation if config includes rule ids not in registry.
- WARN: allow but warn.
- IGNORE: allow silently.