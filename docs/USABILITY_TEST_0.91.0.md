# 0.91.0 activation usability gate

This is a **human release gate**, not an automated unit test. It must not be represented as complete until real participants have run it.

## Participants

Recruit 10 JVM developers who have not used Shamash before. Do not coach them and do not explain ASM/PSI, baselines, facts, registries or scan scopes before the exercise.

## Tasks

1. Starting only from the main README, get Shamash to tell you something useful about a provided Java/Kotlin project.
2. Configure one architecture rule that you would keep in the project.
3. Add Shamash to CI without forcing existing architecture debt to be fixed immediately.

## Record

For every participant record:

- first-scan completion/failure
- time to first successful scan
- first place they got stuck
- terminology they misunderstood
- whether the first result was useful
- whether they successfully created a baseline when needed
- whether they successfully added CI enforcement
- whether they would keep Shamash in the project

Do not record source code or proprietary project details.

## Release threshold

At least **8 of 10** participants must complete the first successful scan using only the main README.

Failures must be triaged into documentation, CLI UX, packaging/install, project discovery, rule-model or product-value causes. Any repeated P0/P1 onboarding failure blocks the public promotion campaign even if the binaries are technically releasable.
