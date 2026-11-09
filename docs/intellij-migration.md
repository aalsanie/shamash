# Upgrading the IntelliJ plugin from 0.92

Shamash now opens a single source-first workspace with **Findings** and **Explore**.
Select **Analyze project** to investigate Java/Kotlin production-source dependencies and
package cycles. The analysis runs on demand, uses current in-memory editor documents,
and does not create configuration files, export reports, or run application builds.

## Existing project data

The upgrade **does not delete or rewrite** existing Shamash YAML configurations,
baselines, exported reports, or source files. They remain available to the standalone
Shamash CLI and libraries. The plugin no longer evaluates project-policy YAML, custom
rules, roles, suppression lists, bytecode scan settings, or custom registry providers.
Source findings are not persisted across IDE restarts.

The plugin records a versioned acknowledgement in its own IntelliJ workspace state
when the new window is opened; this does not migrate or modify project policy files.
A new installation defaults to a bottom tool window. IntelliJ's saved per-user
docking position is not changed by the migration.

## Old actions and keyboard shortcuts

These existing action IDs are retained as compatibility aliases for the 1.0 series:

| Previous action | New behavior |
| --- | --- |
| Source Dashboard | Opens **Findings** |
| Build Dashboard | Opens **Findings** |
| Run Source Scan | Opens **Findings** and runs source analysis |
| Run Build Scan | Opens **Findings** and runs **source** analysis, **not** a compiled-bytecode scan |

The old Build Scan shortcut does not validate ASM policies. Current analysis scope
and limitations are shown in the tool window.

Actions for creating/opening/validating YAML, exporting reports, cancelling through
the old action ID, and configuring plugin registries have been retired. Shortcuts
previously bound to these actions no longer perform those operations; you may remove
those bindings in **Settings → Keymap**. Use the toolbar's **Cancel** button for
an active source analysis.

The plugin-specific `io.shamash.asmRuleRegistryProvider` extension point was removed.
Third-party IntelliJ plugins using it must stop registering that extension.
The standalone ASM/PSI library rule APIs and CLI custom-registry mechanisms remain
available and are not modified by this upgrade.

For policies, reports, baselines, custom registries and CI enforcement, see the
[Shamash repository](https://github.com/aalsanie/shamash).
