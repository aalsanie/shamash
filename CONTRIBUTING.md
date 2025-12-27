# Contributing to Shamash

Thank you for your interest in contributing to **Shamash**.  
Contributions are welcome, whether they are bug reports, documentation improvements, inspections, dashboard enhancements, or architectural suggestions.

This document explains how to contribute in a way that keeps the project clean, stable, and maintainable.

---

## Guiding Principles

Shamash is built around a few core principles:

- **Determinism over magic**  
  Inspections and analysis must be predictable and explainable.

- **Architecture first**  
  Changes should strengthen or clarify architectural boundaries.

- **Low noise**  
  Avoid rules or features that produce excessive false positives.

- **Maintainability**  
  Readable, well-structured code matters more than cleverness.

---

## Ways to Contribute

You can contribute by:

- Reporting bugs or regressions
- Improving documentation or examples
- Adding or refining inspections
- Improving ASM analysis or dashboard visualization
- Optimizing performance or memory usage
- Suggesting architectural improvements

---

## Reporting Issues

When reporting a bug, please include:

- IntelliJ IDEA version
- Shamash plugin version
- Operating system
- Steps to reproduce
- Expected vs actual behavior
- Relevant logs or screenshots (if applicable)

Use GitHub Issues for all bug reports and feature requests.

---

## Development Setup

### Requirements

- IntelliJ IDEA (Community or Ultimate)
- JDK compatible with the IntelliJ Platform you are targeting
- Gradle (wrapper included)

### Running the plugin locally

```bash
gradlew.bat spotlessApply --stacktrace
gradlew runIde
```
