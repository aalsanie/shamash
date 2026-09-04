# Jenkins 2.479.3 core

Shamash 0.92.0 scanned 2,496 classes from Jenkins core in a median 3.289 seconds. This is one local Windows result, not a comparison with another tool and not a claim about every monolith.

## Summary

| Metric | Result |
| --- | ---: |
| Measured runs | 5 |
| Median | 3.289 s |
| Mean | 3.289 s |
| Minimum | 3.232 s |
| Maximum | 3.395 s |
| Range | 0.163 s |
| Population standard deviation | 0.060 s |
| Coefficient of variation | 1.81% |
| Median throughput | 759 classes/s |
| Findings per run | 41 |

The finding count was stable across the priming and measured runs. Findings require human review; the count is not a defect count or an accuracy score.

## Runs

| Run | Included in summary | Time | Throughput | Findings |
| --- | :---: | ---: | ---: | ---: |
| Priming | No | 3.354 s | 744.2 classes/s | 41 |
| 1 | Yes | 3.297 s | 757.1 classes/s | 41 |
| 2 | Yes | 3.232 s | 772.3 classes/s | 41 |
| 3 | Yes | 3.232 s | 772.3 classes/s | 41 |
| 4 | Yes | 3.395 s | 735.2 classes/s | 41 |
| 5 | Yes | 3.289 s | 758.9 classes/s | 41 |

The recorded values are in [`runs.csv`](./runs.csv).

## Scope

| Item | Treatment |
| --- | --- |
| Target | `jenkins-core-2.479.3.jar` embedded in the Jenkins 2.479.3 WAR |
| Input integrity | WAR SHA-256 `304c8592860d5b03dec27c96b5e89ec58fc744f78161c53f7a344a0bf7ce9203` |
| Included | Jenkins core `.class` files |
| Excluded | Plugins and bundled dependency JARs |
| Rules | Built-in discovery configuration |
| Process model | A fresh JVM for every run; one priming run followed by five measured runs |
| JVM limits | `-Xms256m -Xmx2g` |
| Timed | JVM startup, class scan, analysis, and findings written to the log |
| Not timed | Download, checksum verification, extraction, CLI build, and log parsing |

## Reproduce

Build the CLI first:

```powershell
.\gradlew.bat --no-configuration-cache :shamash-cli:distZip
$CliZips = @(Get-ChildItem .\shamash-cli\build\distributions\*-0.92.0.zip)
if ($CliZips.Count -ne 1) { throw "Expected one 0.92.0 CLI ZIP" }
python .\benchmarks\jenkins-2.479.3\benchmark.py --cli-zip $CliZips[0].FullName --out .\benchmark-results
if ($LASTEXITCODE -ne 0) { throw "Benchmark failed; inspect failure.json and the saved logs" }
```

The script verifies the pinned WAR checksum, extracts only Jenkins core classes, checks that every scan is complete, and rejects changing findings across identical runs. It writes the environment, input hashes, class inventory, findings, full-precision run data, logs, and summary to a new result directory.

## Limitations

- OS caches were not reset.
- Memory and peak resident set size were not measured.
- The committed record contains the three-decimal console values. The generated environment and full-precision result files were not retained, so this result should not be used as a regression threshold or a cross-machine comparison.
- This measures Jenkins core without its plugins or bundled dependencies.
- No comparison with ArchUnit or another architecture tool was performed.
