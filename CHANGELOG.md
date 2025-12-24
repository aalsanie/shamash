# Changelog

## [0.1.0] Shamash
The PSI layer is now production-ready and enforces structural, naming, and dependency rules with deterministic behavior and safe, reversible fixes.

---

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

