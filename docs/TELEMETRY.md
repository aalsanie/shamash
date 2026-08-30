# Shamash telemetry contract

Shamash works fully offline. Telemetry is optional, explicit opt-in, and is never required by the analysis engine.

## Activation

Telemetry only attempts delivery when both environment variables are present:

```text
SHAMASH_TELEMETRY=1
SHAMASH_TELEMETRY_ENDPOINT=https://...
```

No endpoint is hard-coded in the open-source release. This avoids inventing or silently depending on a collection service that is not operated as part of this repository.

## Allowed fields

The 0.91 client can send only high-level product events and bounded operational values such as:

- event name (`scan_succeeded`, `config_created`, `baseline_created`)
- Shamash version
- operating-system name
- whether the process is running in CI
- discovery/configured mode
- counts such as classes/findings
- a local salted project identifier for retention measurement

The project identifier is a SHA-256-derived opaque value based on a local random salt plus the normalized local project path. The path and salt are never sent.

## Prohibited data

Telemetry must never include:

- source or bytecode content
- class or package names
- file paths
- repository names or Git remotes
- organization/user names
- rule definitions
- finding messages or finding locations
- configuration contents
- environment-variable values

## Failure behavior

Telemetry uses a short HTTPS timeout and all failures are ignored. A telemetry failure must never change findings, reports, exit codes, scan results, baseline behavior or CLI startup.

## Product metrics

When a real collection endpoint is operated, the intended funnel is:

```text
first successful scan
→ configuration created
→ baseline created (brownfield only)
→ CI execution
→ 7-day retained project
→ 30-day retained project
```

The north-star metric is 30-day retained projects running Shamash in CI, not raw downloads or command executions.
