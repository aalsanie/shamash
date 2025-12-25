# Changelog

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

#### Architecture & Dependency Inspections
- Controller → DAO dependency inspection
- Service → Controller dependency inspection
- Layer-based method count enforcement
- One-public-method rule for controllers
- Forbidden private methods in architectural layers (Controller, Service, DAO)
- Utility class structural enforcement

#### Naming & Structure Enforcement
- Naming convention inspection with banned suffixes (`Manager`, `Helper`, `Impl`)
- Abbreviation detection for class names
- Root package enforcement with configurable base package

#### Dead Code Detection
- Unused class detection
- Unused method detection
- Safe deletion quick fixes with IntelliJ undo support

---

### Quick Fixes

All fixes are explicit, local, and reversible.

- Remove unused classes and methods
- Make utility classes `final`
- Make utility methods `static`
- Remove private modifiers from forbidden layers
- Remove Spring stereotype annotations from utility classes
- Rename classes to comply with naming rules
- Configure root package directly from inspection results

---

### Architectural Guarantees

- All inspections are PSI-only and side-effect free
- No bytecode analysis or runtime assumptions
- No framework coupling beyond explicit detection
- No premature optimization or speculative behavior
- All inspections are isolated, deterministic, and testable

---

### Internal Structure

- Clear separation between:
    - PSI utilities
    - Architecture rules
    - Inspections
    - Fix implementations
- One-liner inspection classes
- Centralized rule logic
- No duplicated PSI traversal

---

### Status

- PSI layer: **Complete**
- ASM layer: Planned
- Hybrid analysis: Planned
- Runtime logging cleanup: Planned

This release marks the first stable foundation of Shamash.
All future layers will build on this PSI core.

---

