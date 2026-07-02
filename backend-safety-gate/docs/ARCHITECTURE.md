# Architecture

## Pipeline flow

```
PR opened/updated
       │
       ▼
┌─────────────────────┐
│ 1. Change Detection  │  git diff base...head → changed files
│    ChangeDetector    │  changed files → apis.json → scoped endpoint set
└──────────┬───────────┘
           │ scopedEndpoints (empty set = run everything)
           ▼
┌─────────────────────┐
│ 2. Contract Diff     │  base-openapi.json vs head-openapi.json
│  ContractDiffChecker │  → CRITICAL/HIGH findings on breaking changes
└──────────┬───────────┘
           │ (fails fast here if config says failOnContractBreak)
           ▼
┌─────────────────────┐
│ Auth: JwtFetcher      │  POST /token/refresh/ using CI-secret refresh token
└──────────┬───────────┘
           │ access token
           ▼
┌─────────────────────┐
│ 3. Functional Regr.  │  scoped test cases from functional-cases.json
│ FunctionalRegression  │  → status code + body-content assertions
│         Runner        │
└──────────┬───────────┘
           ▼
┌─────────────────────┐
│ 4. Response Time     │  3 samples/endpoint → median
│    ResponseTimeGate   │  hard limit check + baseline regression check
└──────────┬───────────┘   baseline ratchets forward only on pass
           ▼
┌─────────────────────┐
│ 5. Security Scan     │  SQLi/XSS injection, JWT tamper, IDOR probe,
│ SecurityPayloadRunner │  stray-CSRF-on-GET check
└──────────┬───────────┘
           ▼
┌─────────────────────┐
│ 6. Report            │  aggregate all StageResults → single HTML file
│   ReportGenerator     │  exit code 0/1 back to GitHub Actions
└──────────┬───────────┘
           ▼
   PR check passes/fails
```

## Design decisions

**Why scope to changed endpoints instead of testing everything every time?**
Full-surface testing on every PR is slow and, past a certain endpoint count,
teams start skipping or disabling the gate out of frustration. Scoping to
what changed keeps runs fast (seconds to low minutes) so it stays in the
critical path without friction. A nightly/weekly unscoped full run is a good
complement (pass an empty base ref set, or call the stages with an empty
`scopedEndpoints` set, to run everything).

**Why JSON-driven test cases instead of a Java test class per endpoint?**
The Excel/Confluence-based test case tradition on this team means
non-developers can review or add cases without touching code. It also keeps
the mapping between "what changed" and "what to run" simple — one file to
scan (`functional-cases.json`), matched purely by the `endpoint` key.

**Why refresh-token auth instead of logging in per run?**
Django was observed flagging repeated automated `/token/` calls and marking
the service account credentials unusable. Refreshing an existing token
avoids ever calling the login endpoint from CI.

**Why hard limit AND regression-percentage for response time?**
A hard limit alone lets slow-but-under-threshold endpoints creep
indefinitely (200ms → 750ms, still "passing" at an 800ms limit, but a 3.75x
regression). A regression-percentage alone would fail fast endpoints on
noise (10ms → 15ms is 50% but still trivially fast). Using both catches real
problems without false-failing on noise.

**Why is the report a single static HTML file?**
No external CSS/JS CDN dependency means it renders correctly as a GitHub
Actions artifact download or an email attachment, even offline.
