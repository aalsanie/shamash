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
The plugin offers fixes and apply them, not just an inspection tool!

---

## What it does

Modern Java systems often decay not because of bad intentions, but because of **hidden behavior**, **blurred responsibilities**, and **implicit structure**.

Shamash enforces clarity by making architecture explicit and violations fixes.

- No guessing.  
- No heuristics pretending to be truth.  
- No runtime assumptions.
- scan and fix with a clean architecture decisions

---

## What Shamash Enforces

- Architectural Boundaries, for example:
  - Controllers must not depend on DAOs
  - Services must not depend on Controllers
  - Clear separation between layers (Controller, Service, DAO, Util, Workflow, CLI)

- Structural Integrity for example:
  - One public endpoint per controller
  - Layer-based method count limits
  - No private methods in forbidden layers
  - Utility classes must be stateless and explicit

- Naming for example:
  - Bans ambiguous suffixes (`Manager`, `Helper`, `Impl`)
  - Disallows abbreviated class names

- Dead Code removal for example
  - Detects unused classes and methods
  - Provides safe deletion fixes


Every Shamash violation has a fix that can be applied. Not just useless warnings!

All fixes are:
  - Local
  - Deterministic
  - Undo-safe

---

## Internals & How it works
I rely on three main technologies to refactor code:
- PSI inspection
- ASM (bytecode hierarchy analysis)

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
