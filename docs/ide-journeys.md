# IntelliJ IDE journey verification

PR4 adds a distinct, out-of-process **Starter/Driver + JUnit 5** suite alongside
the existing JUnit 4 IntelliJ fixture tests. It does not replace the deterministic
coordinator, lifecycle, navigation, stale-result or cancellation regressions.

## Executable journeys

The tracked fixtures contain explicitly marked production source roots and no
project build scripts. The real IDE suite verifies:

- **No source:** open Shamash, deliberately analyze, display the unavailable
  state, and preserve the YAML configuration, baseline and previous report.
- **Java:** run source analysis on a two-package cycle, show its actual cyclic
  group and the directed source-evidence list, without exporting reports.
- **Mixed Java/Kotlin:** use the retained old Build Scan action ID, find a
  mixed-language cycle, enter Explore, and preserve the existing YAML/report.
- **Partial resolution:** deliberately reference a missing Java type and assert
  that the source analysis reports incompleteness, never a clean no-cycle result.

The full in-process fixture suite separately covers partial/unresolved source,
cancellation and late publication, source edits and stale state, wrong-location
navigation, narrow layout, filtering, and migration behavior. The Starter tests
check the **project actually opened by the IDE**, which may be a copied fixture,
not just the tracked source template.

## Commands

From the repository root, with a graphical session available:

```powershell
.\gradlew.bat :shamash-intellij-plugin:test
python -B scripts/verify-plugin-tests.py
.\gradlew.bat :shamash-intellij-plugin:integrationTest -PshamashIdeJourneyVersion=2024.2.1
python -B scripts/verify-plugin-tests.py --integration
```

The approved CI matrix runs the separate task on Linux under Xvfb for
**2024.2.1** (minimum Community build) and **2026.2** (newest unified
IDEA build, using its IU product identifier), failing on
missing/skipped JUnit results and on IDE-process exceptions forwarded through
Starter's `CIServer` reporting hook. CI retains the XML/test reports even on
failure. No test is marked successful simply because Gradle reports NO-SOURCE.

Starter/Driver APIs are experimental; the IntelliJ Gradle plugin remains pinned
to the repository's existing release. A change to plugin/IDE/test framework
versions needs actual compilation and compatibility verification, not an
unreviewed dependency sweep.

## Evidence and scope

Record the passing run ID, the exact IDE build and distribution, JBR, OS, test
count, any IDE exceptions, and any limitations before marking the suite as
verified. The CI job is an automated **journey** gate, not proof that human
participants found the interface understandable or accessible. PR5 qualification
still needs the documented keyboard/screen-reader, theme/scaling, manual
usability, project-size performance, heap and supported-edition checks.

References: [JetBrains integration tests](https://plugins.jetbrains.com/docs/intellij/integration-tests-intro.html)
and [UI testing](https://plugins.jetbrains.com/docs/intellij/integration-tests-ui.html).
