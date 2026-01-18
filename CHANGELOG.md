# Changelog

## [unreleased]

### Update
- Refactored baseline and extracted BaselineFingerprint

### Added
- Add a complete IntelliJ Plugin E2E test suite

## [0.70.3]
### Update
- Updated IntelliJ Platform target from 2024.2 to 2024.2.1+ 
- Support Kotlin’s supportsKotlinPluginMode descriptor 
- Eliminate unresolved extension point warnings.

## [0.70.2]
### Fixed
- Simplified UI and added clear instructions 
- UI Dashboard panel overlapping action buttons
- UI Config panel overlapping action buttons
- Updated ASM Config tab summary messaging to reflect state accurately
- Adjusted validate config to redirect to dashboard only on successful validation.
- Fixed Open Reference YAML action enforcing UTF-8 on the virtual file

### Removed
- Removed UI redundant labels

## [0.70.1]
### Added
- Added test suite for `shamash-artifacts` module
- Added test suite for `shamash-export` module
- Added test suite for `shamash-asm-core` module
- Added test suite for `shamash-psi-core` module

### Fixed
- Fixed GlobMatcher compilation/normalization to correctly match * / ** patterns across platforms
- Support “match anywhere” behavior for relative globs.

### Changed
- Updated ASM config validation to support engine rule-id provider .
- Updated ASM config validation to validate unknown rules based on unknownRule policy.

## [0.70.0]
### Added

- Major refactor: modularized the codebase into five modules for clearer ownership and reuse:
  - **shamash-artifacts** — shared contracts, models, and finding/report primitives.
  - **shamash-export** — export pipeline (orchestration, report builders, exporters, output layout).
  - **shamash-psi-core** — PSI/source analysis core (config/validation + analysis engine integration).
  - **shamash-asm-core** — ASM/bytecode analysis core (scan → facts → engine → baseline/export).
  - **shamash-intellij-plugin** — IntelliJ UI/actions/tool windows wiring for PSI + ASM.
- ASM ToolWindow with **Dashboard / Findings / Config** tabs wired.
- ASM actions: **Run Scan**, **Validate Config**, **Create Config from Reference**, **Open Reference Config**, **Export Reports**.
- In-memory **ASM UI state service** with listeners + automatic tab navigation.
- Findings UI: table + details panel, selection persistence, state-driven rendering.
- Config UI: config discovery + open/create/validate flows with settings override support.
- Bytecode scan primitives: **BytecodeOrigin** + **BytecodeScanner** (roots resolution, glob filtering, scope bucketing, deterministic ordering, truncation limits, best-effort IO error capture).
- **ShamashAsmScanRunner** orchestration: config load/validate → bytecode scan → fact extraction → engine execution → baseline/export integration.
- Added dependency (`org.snakeyaml:snakeyaml-engine`).
- ASM engine enhancements:
  - `EngineRunSummary.RuleStats` counters for configured/executed/skipped **rule instances**.
  - Finding normalization + stable sorting + de-dupe keys.
  - Engine error stabilization (de-dupe + stable sorting).
  - Engine-owned role classification (priority matching; populates `FactIndex.roles` + `classToRole`).
  - Engine-owned exception suppression (compiled matchers: rule/type/name/role/class/package/path/glob).
  - Engine-owned baseline flow: NONE / GENERATE (atomic write fallback) / VERIFY (suppress by fingerprints).
  - Export pipeline wired via **shamash-export** with overwrite gating and normalized output directory resolution.

### Fixed

- ASM config locator parity with PSI: search `ProjectLayout.ASM_CONFIG_CANDIDATES` across resource roots + fallback to project root, with settings override.
- avoid invalid VFS child names; prevent duplicated `shamash/configs/...` segments.
- IntelliJ write actions: command-based writes return values safely (avoid `Unit?` mismatches).
- Swing naming collision: replaced ambiguous `component()` usage to avoid invoke/property conflicts.
- Rule registry executability check now uses the default registry implementation (removed invalid `RuleRegistry.allIds()` usage).
- `graph.maxDependencyDensity` parsing uses `Params.requireDouble("max", min, max)`.
- UnknownRulePolicy parsing tolerates lowercase values (`ERROR/WARN/IGNORE` and `error/warn/ignore`).
- Rule execution wiring calls `Rule.evaluate(facts, rule, config)` with explicit named arguments.
- Baseline fingerprinting handles blank `filePath` via a stable fallback path input.
- Export overwrite gating: `overwrite=false` skips export when any requested report already exists.

### Removed

- Removed unused rule-context mutation approach (`withRuleDef`) in favor of the direct `Rule.evaluate(...)` contract.


## [0.60.1]
### Fixed
- Existing bug related to role assignment for a specific rule caused any rule with a role not to take effect
  - patched ConfigSemanticValidator to execute rule key regardless of role being there or not
  - the engine expand authored roles into instance of name.type.role at runtime
- Populated classToRole facts with a fallback to UAST extractor
- Added test bed application and documentation

## [0.60.0]
### Fixed
- Stabilize PSI Config v1 contract (reference YAML + JSON schema + binder alignment)
- Tighten param typing + validation messages (maps vs lists, non-empty rules, regex lists, etc.)
- Refactor scan pipeline for IDE safety (smart-mode read actions) + progress indicator file names
- Update engine/baseline/export/fixes/UI wiring to new config pipeline
- Fixed `facts.classToRole` only includes roles for classes found in the current file.

### Added
- Configurable architecture engine
- Configurable code structure engine
- Configurable fixes engine
- Added Exception suppression complete production ready
- Complete Role coverage build class→role mapping across source globs cache it per project
- Glob: scope globs + exception fileGlob
- Complete exception matching (fileGlob + role + annotations + expiry enforcement)
- Complete exporters JSON + SARIF + xml, + html
- Baseline mode & complete Kotlin support
- Complete UI layer
- Make the plugin complete
- Show violations instantly as you type and can point to exact code + quick fixes → that’s valuable.
- Complete refactoring and auto fixing
- Team configurable rules via our json/yaml schema through rule DSL
- Indexing + accurate suppressions + exportable reports.
- CLI runner produces SARIF/JSON/HTML/XML.
- A minimal CI integration (GitHub Actions template) that uploads SARIF to code scanning.
- A PSI dashboard that consumes exported artifacts.
- Baseline complete support
- Export layer
- Scan layer runner

### Removed
- Removed opinionated inspections and fixes and descriptions
- Removed legacy architecture module
- Removed static inspections and fixes

## [0.41.1]
### Removed
- Remove deprecated TreeSearch and use installTreeSpeedSearch for search tab
### Updated
- update gradle to 8.13
- remove deprecated methods/classes

## [0.4.0]
### Fixed
- Support latest IDE
## [0.3.0]
### Added
- Shamash Dashboard
  - Findings and violations
  - Hierarchy tree
  - Search capabilities
  - Reports
- Exportable `xml` and `json` violations and fixes reports
- Exportable `html` os overall architecture score
- ASM / PSI hybrid scans
### Fixed
- Compatibility issues with 24.x.x.x intellij version

## [0.2.0]
### Added
- Actionable quick-fix infrastructure across PSI inspections.
- SafeMoveRefactoring powered by IntelliJ refactoring APIs:
  - Moves classes to the correct package
  - Updates package declarations
  - Fixes imports and references
  - Supports undo/redo
  - Create missing package directories when needed
- Root package auto-detection + resolution workflow
  - RootPackageResolver
  - TargetPackageResolver
  - “Set detected root as project root” flow (project-level alignment)
- Layer-aware root package fix
  - Detects class layer (Controller/Service/DAO/Util/Workflow/CLI)
  - Moves to correct target package when the structure exists
  - Falls back safely when the structure cannot be inferred
- Dead code delete fix
  - Safe deletion using IDE safe-delete mechanics
  - Integrated into DeadCodeInspection for unused methods/classes (conservative default)

### Changed
- Inspections refactored for clarity
- Dead code detection made conservative by design
  - Avoids noisy “unused” reporting in layers where reflection / frameworks often call code indirectly
  - Prevents risky safe-delete suggestions where uncertainty exists
- PackageRootInspection improved
  - Detects classes outside the configured project root
  - Also detects structurally misplaced classes (wrong layer package)
  - Fix now performs the full refactor instead of only recommending a target package

### Fixed
- Multiple quick-fix “no-op” cases where pressing Apply Fix did not change code.
- Incorrect or ambiguous PSI element validity checks (descriptor/parent resolution).
- Package move edge cases where packages did not exist (now created safely).
- Non-triggering package/layer detection scenarios by tightening dependency queries and layer inference rules.

### Known limitations
- Dead code detection remains intentionally conservative; Shamash prefers **not warning** over deleting something indirectly used.
- ASM layer is not included yet; PSI is complete and locked, ASM begins next.


### [0.1.0]

### Added
- Controller to DAO dependency inspection
- Service to Controller dependency inspection
- Layer-based method count enforcement
- One-public-method rule for controllers
- Forbidden private methods in architectural layers (Controller, Service, DAO)
- Utility class structural enforcement
- Naming convention inspection with banned suffixes (`Manager`, `Helper`, `Impl`)
- Abbreviation detection for class names
- Root package enforcement with configurable base package
- Unused class detection
- Unused method detection
- Safe deletion quick fixes with IntelliJ undo support
- Remove unused classes and methods
- Make utility classes `final`
- Make utility methods `static`
- Remove private modifiers from forbidden layers
- Remove Spring stereotype annotations from utility classes
- Rename classes to comply with naming rules
- Configure root package directly from inspection results