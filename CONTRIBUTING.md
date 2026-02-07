# Contributing to Shamash

Thank you for your interest in contributing to **Shamash**.  
Contributions are welcome, whether they are bug reports, documentation improvements, inspections, dashboard enhancements, or architectural suggestions.

This document explains how to contribute in a way that keeps the project clean, stable, and maintainable.

---

## Ways to Contribute

You can contribute by:

- Reporting bugs or regressions
- Improving documentation or examples
- Adding or refining inspections
- Improving ASM/PSI analysis or dashboard visualization
- Optimizing performance or memory usage
- Suggesting architectural improvements

---

## Reporting Issues

When reporting a bug, please include:

- IntelliJ IDEA version
- Shamash plugin version
- Shamash CLI version
- Operating system
- Steps to reproduce
- Expected vs actual behavior
- Relevant logs or screenshots (if applicable)

Use GitHub Issues for all bug reports and feature requests.

---

## Development Setup

### Build
```shell
gradlew clean build
```

### Test
```shell
gradlew clean test
```

### CLI
```shell
gradlew :shamash-cli:run
```

### Running the plugin locally

```bash
gradlew clean runIde
```

### Format
```shell
gradlew.bat spotlessApply
```
