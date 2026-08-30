# Shamash 0.91.0 — Activation Release

0.91.0 intentionally freezes engine breadth and focuses on activation, distribution reliability and product coherence.

## Product changes

- Configless `shamash scan` using a safe embedded discovery profile.
- Discovery is report-only, does not materialize project files and does not fail on findings.
- Actionable Gradle/Maven guidance when compiled bytecode is missing.
- Findings are printed by default; engine counters move behind `--verbose`.
- Default `shamash init` creates a 26-line starter configuration.
- Spring policy is explicit through `--preset spring`.
- Full engine reference remains available through `--preset reference`.
- `shamash baseline create` baselines existing debt without YAML mode editing.
- Existing baselines are protected unless `--force` is explicit.
- First-party GitHub Action verifies release checksums before execution.
- One IntelliJ `Shamash` tool window replaces separate ASM/PSI top-level surfaces.
- First-level IDE terminology is Build Analysis / Source Analysis.

## Production/release changes

- Version set to 0.91.0.
- IntelliJ compatibility extended and verified through 2026.2 (`262.*`).
- Linux, Windows and macOS unit/package smoke gates.
- Plugin Verifier is a required CI/release job.
- Release pipeline creates assets only after tests/verifier/smoke gates pass.
- Plugin signing and JetBrains Marketplace publication are in the release workflow.
- Canonical Apache-2.0 `LICENSE` replaces the shortened `LICENSE.md`.
- Security policy points vulnerability reports to private disclosure.
- Documentation uses the actual `SHA256SUMS.txt` release contract.

## Feature freeze rule

No additional analysis/rule/report feature should enter 0.91.x unless it fixes a correctness, security, compatibility or activation problem. Engine breadth resumes only after the 0.91 activation funnel has evidence.
