<p align="center">
  <img src="assets/shamash_logo.png" alt="Shamash Logo" width="180"/>
</p>


# Shamash

Shamash is an architectural refactoring engine that enforces clean architecture,
explicit design, and structural integrity in Java codebases.

It reveals hidden structure, responsibility, and architectural truth by detecting violations early.

Shamash operates at multiple levels:
- PSI (Program Structure Interface)
- ASM bytecode analysis
- Logging-based cleanup (hybrid runtime signal)

All inspections are deterministic, reversible, and framework-aware where necessary.

---

## Philosophy

Modern Java systems often decay not because of bad intentions, but because of **hidden behavior**, **blurred responsibilities**, and **implicit structure**.

Shamash enforces clarity by making architecture explicit and violations impossible to ignore.

It favors:
- Explicit behavior over hidden logic
- Clear architectural boundaries
- Intent-revealing naming
- Fixing violations, not just reporting them

No guessing.  
No heuristics pretending to be truth.  
No runtime assumptions.

---

## What Shamash Enforces

### Architectural Boundaries
- Controllers must not depend on DAOs
- Services must not depend on Controllers
- Clear separation between layers (Controller, Service, DAO, Util, Workflow, CLI)

### Structural Integrity
- One public endpoint per controller
- Layer-based method count limits
- No private methods in forbidden layers
- Utility classes must be stateless and explicit

### Naming & Intent
- Bans ambiguous suffixes (`Manager`, `Helper`, `Impl`)
- Disallows abbreviated class names
- Enforces intent-revealing naming

### Dead Code Elimination
- Detects unused classes and methods
- Provides safe deletion fixes
- Fully reversible via IDE undo

---

## Fixes, Not Just Warnings

Every Shamash inspection is designed with **fixability** in mind.

Available quick fixes include:
- Remove unused elements
- Make classes `final`
- Make methods `static`
- Remove forbidden modifiers
- Rename classes to compliant names
- Configure architectural root packages

All fixes are:
- Local
- Deterministic
- Undo-safe

---

## How It Works

Shamash uses a layered analysis approach:

### PSI Layer (Completed)
- Inspects source structure directly
- No bytecode or runtime dependencies
- Deterministic and fast

### ASM Layer (Planned)
- Bytecode-level dependency and hierarchy analysis
- Detects indirect and hidden coupling
- Complements PSI inspections

### Logging-Based Cleanup (Planned)
- Optional runtime instrumentation
- Identifies code paths that never execute
- Enables confidence-based cleanup

These layers can operate independently or together in a hybrid mode.

---

## Who This Plugin Is For

Shamash is built for engineers who:
- Care about architecture, not just syntax
- Prefer explicit rules over conventions
- Want violations fixed, not documented
- Maintain systems meant to last

It is especially useful for:
- Long-lived codebases
- Large teams
- High-change environments
- Architecture-driven development

---

## Status

- PSI layer: **Complete**
- ASM analysis: Planned
- Hybrid inspections: Planned
- Runtime logging cleanup: Planned

Current release: **0.1.0**
[Change-log](./CHANGELOG.md)

---

## License

Shamash is licensed under the Apache License, Version 2.0.

See [LICENSE.md](LICENSE.md) for details.
