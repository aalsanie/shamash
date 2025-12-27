# Changelog
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