# Backend Safety Gate

A PR gate for `collegehai-outbound-int` that blocks a merge unless the pushed
backend changes are proven to be **backward-compatible**, **functionally
correct**, **fast enough**, and **secure** — scoped only to the endpoints the
PR actually touches.

This complements `api-response-time-tester` rather than replacing it: the
response-time logic here follows the same baseline/threshold model, and the
JWT-handling follows the same refresh-token pattern (never re-logging in on
every CI run).

---

## Why this exists

A backend PR can pass its own unit tests and still break every consumer of
the API — a renamed field, a new required parameter, a slower query, a
newly-exposed IDOR. This gate catches those categories specifically, using
the **currently deployed API contract** (the base branch) as the source of
truth for "what must keep working."

---

## Toggling stages

Each stage runs independently and is individually toggleable via
`failOnContractBreak`, `failOnFunctionalRegression`,
`failOnPerformanceRegression`, `failOnSecurityFinding` in
`config/gate-config.json` — so you can run a stage in "warn only" mode while
you're still building out its test coverage. (See "The stages" table further
down for what each one does.)

---

## Project layout

```
backend-safety-gate/
├── pom.xml                          # zero external runtime deps — pure JDK
├── config/
│   ├── gate-config.json             # thresholds, paths, fail-on switches
│   ├── apis.json                    # endpoint ⇄ source-file map, query params, owner param
│   └── functional-cases.json        # declarative regression test cases
├── specs/
│   ├── base-openapi.json            # exported from base branch in CI
│   └── head-openapi.json            # exported from PR branch in CI
├── baseline/
│   └── response-time-baseline.json  # persisted median response times, ratcheted forward on pass
├── src/main/java/com/makunai/safetygate/
│   ├── Main.java                    # orchestrator — runs all 6 stages in order
│   ├── util/
│   │   ├── Json.java                # dependency-free JSON parser/writer
│   │   ├── HttpUtil.java            # shared HTTP client (Postman UA, timing capture)
│   │   └── JwtFetcher.java          # refresh-token based auth (no repeated logins)
│   ├── config/GateConfig.java
│   ├── contract/
│   │   ├── ChangeDetector.java
│   │   └── ContractDiffChecker.java
│   ├── swagger/SwaggerImporter.java     # fetches live OpenAPI specs, syncs apis.json
│   ├── functional/FunctionalRegressionRunner.java
│   ├── performance/ResponseTimeGate.java
│   ├── security/
│   │   ├── PayloadLibrary.java
│   │   └── SecurityPayloadRunner.java
│   └── report/
│       ├── StageResult.java
│       └── ReportGenerator.java
├── src/test/java/.../JsonTest.java
└── .github/workflows/backend-safety-gate.yml
```

---

## Why no Jackson/Gson/OkHttp?

CI runners in restricted network environments can't always reach Maven
Central for every dependency. `util/Json.java` is a small hand-rolled
parser/writer, and HTTP calls use `java.net.http.HttpClient` from the JDK.
The only dependency in `pom.xml` is JUnit, and it's test-scope only — it
isn't needed to build or run the actual gate jar.

---

## The stages (now seven)

| # | Stage | What it checks | File |
|---|-------|-----------------|------|
| 0 | Swagger Fetch *(optional)* | Pulls live OpenAPI specs from `swaggerUrlBase` (your deployed/production env — the source of truth for "what currently works") and `swaggerUrlHead` (the PR's freshly-started test server); auto-adds newly-discovered endpoints to `apis.json` without touching manual edits | `swagger/SwaggerImporter.java` |
| 1 | Change Detection | Diffs the PR against the base branch, maps changed source files → affected API endpoints via `config/apis.json` | `contract/ChangeDetector.java` |
| 2 | Contract Diff | Compares OpenAPI specs (base vs. head) for removed endpoints/fields, type changes, newly-required fields, changed status codes | `contract/ContractDiffChecker.java` |
| 3 | Functional Regression | Runs declarative JSON test cases against scoped endpoints | `functional/FunctionalRegressionRunner.java` |
| 4 | Response Time Gate | Hard limit + % regression vs. a persisted baseline per endpoint | `performance/ResponseTimeGate.java` |
| 5 | Security Surface Scan | SQLi/XSS injection, JWT tampering, IDOR probe, stray-CSRF-on-GET check | `security/SecurityPayloadRunner.java` |
| 6 | Report | Single self-contained HTML report + process exit code | `report/ReportGenerator.java` |

---

## How the Swagger fetch works

Instead of hand-maintaining `apis.json`, the gate can pull it straight from
your DRF Swagger/OpenAPI schema:

- **`swaggerUrlBase`** — point this at your **currently deployed** backend's
  schema endpoint (e.g. `https://collegehai-outbound-int.makunaiglobal.ai/api/schema/?format=json`).
  This is the "ground truth" for what's currently working — Stage 2 diffs
  against it.
- **`swaggerUrlHead`** — point this at the **PR's own test server**, started
  fresh in CI from the PR's code (e.g. `http://localhost:8000/api/schema/?format=json`).

On every run, Stage 0:
1. Fetches both specs and saves them to `specs/base-openapi.json` / `specs/head-openapi.json`.
2. Reads the head spec's endpoint list and **adds any endpoint missing from
   `config/apis.json`** — inferring `requiresAuth` (from the spec's security
   requirements), `queryParams` (from spec parameters), and a best-guess
   `ownerParam` (any query param containing `_by`, `_user`, `user_id`, or
   `owner`).
3. **Never touches existing entries** — if you've manually added
   `sourceFiles` (for git-diff scoping) or corrected an `ownerParam`, those
   stay exactly as you wrote them.

New endpoints land in `apis.json` with `"sourceFiles": []`. Fill these in
when you have time so Stage 1's git-diff scoping picks them up precisely;
until then they're still fully covered whenever you run the gate without a
git-diff scope restriction (e.g. a nightly full run).

If you don't set `swaggerUrlHead`/`swaggerUrlBase`, the gate falls back to
whatever static files already exist at `openApiSpecPathBase` /
`openApiSpecPathHead` and whatever's already in `apis.json` — nothing
breaks, Stage 0 just gets skipped.

You can also run the importer standalone, outside the full gate:

```bash
java -cp target/classes com.makunai.safetygate.swagger.SwaggerImporter \
  "https://collegehai-outbound-int.makunaiglobal.ai/api/schema/?format=json" \
  specs/base-openapi.json \
  config/apis.json
```

---

## Full step-by-step: running this locally

**Prerequisites:** JDK 17+, Maven 3.8+, `git`, network access to your Django backend.

```bash
# 1. Clone your repo (or the gate's own repo if kept separate — see "Adding
#    this to GitHub" below)
git clone https://github.com/<your-org>/backend-safety-gate.git
cd backend-safety-gate

# 2. Confirm Java + Maven are available
java -version     # should print 17 or higher
mvn -version

# 3. Build the jar (compiles everything, skips tests for speed)
mvn clean package -DskipTests

# This produces: target/backend-safety-gate.jar

# 4. Set the one required secret as an environment variable
export DJANGO_REFRESH_TOKEN="<your long-lived refresh token>"

# 5. (First time only) generate a refresh token manually if you don't have
#    one yet — hit your login endpoint once by hand (Postman/curl), grab the
#    "refresh" value from the response, and store it somewhere safe. Never
#    commit it — it only ever goes in DJANGO_REFRESH_TOKEN.

# 6. Edit config/gate-config.json:
#    - set "baseUrl" to the environment you're testing against
#    - set "swaggerUrlBase" to your deployed backend's schema URL
#    - set "swaggerUrlHead" to the server you're about to start in step 7
#      (usually http://localhost:8000/api/schema/?format=json)

# 7. Start the backend you want to test (in a separate terminal, from your
#    Django project directory)
cd /path/to/collegehai-outbound-int
python manage.py migrate --noinput
python manage.py runserver 0.0.0.0:8000

# 8. Back in the gate's directory, run it
cd /path/to/backend-safety-gate
java -jar target/backend-safety-gate.jar config/gate-config.json origin/main HEAD

# 9. Check the result
echo $?                              # 0 = passed, 1 = failed, 2 = crashed
open reports/gate-report.html        # macOS
xdg-open reports/gate-report.html    # Linux
```

If you're running it against a branch that isn't checked out with full git
history (shallow clone), Stage 1's `git diff` will fail — either `git fetch
--unshallow` first, or pass `origin/main` / your actual comparison refs
that exist locally.

### Running just one stage while you're building out config

You don't have to run the whole thing to test your `apis.json` or
`functional-cases.json` edits — the Swagger importer alone is a fast way to
sanity-check what the gate would scope:

```bash
java -cp target/classes com.makunai.safetygate.swagger.SwaggerImporter \
  "http://localhost:8000/api/schema/?format=json" \
  specs/head-openapi.json \
  config/apis.json
cat config/apis.json   # inspect what got added
```

---

## Adding this to your GitHub repo

You have two reasonable options — pick based on how tightly you want this
coupled to `collegehai-outbound-int`.

### Option A — separate repo (recommended)

Keeps the gate versioned independently and reusable across other backend
repos later, matching how `api-response-time-tester` is already set up.

```bash
cd backend-safety-gate
git init
git add .
git commit -m "Initial commit: backend safety gate (contract, functional, performance, security)"

# Create the repo on GitHub first (via github.com or `gh repo create`), then:
git remote add origin https://github.com/<your-org>/backend-safety-gate.git
git branch -M main
git push -u origin main
```

Then in `collegehai-outbound-int`, the workflow file already checks this repo
out as a step (`Checkout backend-safety-gate tooling` in
`.github/workflows/backend-safety-gate.yml`) — just update the
`repository:` field to your actual GitHub org/repo name, and copy that
workflow file into `collegehai-outbound-int/.github/workflows/`.

### Option B — subfolder inside `collegehai-outbound-int`

Simpler if you don't need to reuse the gate elsewhere yet.

```bash
# From inside your collegehai-outbound-int repo
mkdir -p tools/backend-safety-gate
cp -r /path/to/backend-safety-gate/* tools/backend-safety-gate/
cp -r /path/to/backend-safety-gate/.github .   # merge workflow into repo root if you don't already have one

git add tools/backend-safety-gate .github/workflows/backend-safety-gate.yml
git commit -m "Add backend safety gate: contract diff, functional, perf, security PR checks"
git push origin <your-branch>
```

If you go with Option B, simplify the workflow's checkout step — you no
longer need the separate `Checkout backend-safety-gate tooling` step or the
`working-directory: .safety-gate` prefixes; everything lives at
`tools/backend-safety-gate` in the same checkout.

### GitHub repo settings you'll need either way

1. **Secret:** Settings → Secrets and variables → Actions → New repository
   secret → `DJANGO_REFRESH_TOKEN`
2. **Variable (optional):** Settings → Secrets and variables → Actions →
   Variables tab → New repository variable → `PROD_SWAGGER_URL` = your
   deployed backend's schema URL (used by the workflow's
   `SWAGGER_URL_BASE` env var)
3. **Branch protection:** Settings → Branches → Add rule for `main`
   (or `develop`) → enable "Require status checks to pass before merging" →
   select the `safety-gate` job once it's run at least once (GitHub only
   lists checks that have executed previously)

---

## Setup (manual config, if not using Swagger auto-sync)

### 1. Configure `config/apis.json`

List every endpoint you want covered, with the source files that affect it.
This is what lets Stage 1 scope the run instead of testing all 200 endpoints
on every PR:

```json
{
  "method": "GET",
  "path": "/choutbound-service/v1/manual-push-count",
  "requiresAuth": true,
  "sourceFiles": ["views.py", "serializers/push.py"],
  "queryParams": ["subscriber_name", "manual_push_by"],
  "ownerParam": "manual_push_by"
}
```

- `queryParams` — which params get SQLi/XSS injection probes (Stage 5)
- `ownerParam` — if set, Stage 5 tries swapping it to a different user's id
  and confirms the backend returns 403/404 instead of 200 (IDOR check)

### 2. Configure `config/functional-cases.json`

Add one entry per scenario you want regression-tested. `endpoint` must match
the `METHOD /path` key format used in `apis.json` so the scoping in Stage 1
lines up.

### 3. Configure `config/gate-config.json`

```json
{
  "baseUrl": "https://collegehai-outbound-int.makunaiglobal.ai",
  "responseTimeHardLimitMs": 800,
  "responseTimeRegressionTolerancePct": 15.0,
  "failOnContractBreak": true,
  "failOnFunctionalRegression": true,
  "failOnPerformanceRegression": true,
  "failOnSecurityFinding": true
}
```

### 4. Set the CI secret

`DJANGO_REFRESH_TOKEN` — a long-lived refresh token generated once manually
and stored as a GitHub Actions secret. The gate calls `/token/refresh/` on
every run instead of `/token/`, avoiding Django's automated-login flagging.

If the refresh token itself expires, `JwtFetcher` throws a clear error
telling you to regenerate it — it never silently falls back to a fresh login.

---

## Running locally

```bash
mvn clean package -DskipTests
java -jar target/backend-safety-gate.jar config/gate-config.json origin/main HEAD
```

Exit codes:

| Code | Meaning |
|------|---------|
| `0` | All enabled stages passed — safe to merge |
| `1` | At least one enabled stage failed |
| `2` | The gate itself crashed (bad config, no git repo, etc.) — treat as a broken pipeline, not a code-quality signal |

The HTML report is written to `reports/gate-report.html` (configurable via
`reportOutputPath`).

---

## How each stage decides pass/fail

**Contract Diff** — any `CRITICAL` or `HIGH` finding fails the stage:
- `CRITICAL`: endpoint/method removed, response field removed, new required
  request field
- `HIGH`: field type changed (request or response)
- `MEDIUM`: success status code changed (warn, doesn't fail by default)
- `INFO`: new endpoint, new optional field, new response field (all
  non-breaking, logged for visibility only)

**Response Time** — fails if median response time (of 3 samples) exceeds the
hard limit, *or* if it's more than `responseTimeRegressionTolerancePct`
slower than the stored baseline — even if still under the hard limit. This
catches creeping regressions (200ms → 350ms) that a hard limit alone would
miss. The baseline only ratchets forward on a passing run, so a regression
never gets silently baked in as the new normal.

**Functional Regression** — any case where the actual status code doesn't
match `expectedStatus` is `HIGH`; missing expected body content is `MEDIUM`.

**Security Scan** — `CRITICAL` on: 500 error from an injection payload, SQL
error leaked in response body, tampered/missing JWT accepted, cross-account
IDOR access allowed. `HIGH` on: payload reflected unescaped (possible XSS).
`LOW` on: CSRF token present on a GET endpoint (informational — usually
leftover middleware, not directly exploitable, but worth a look).

---

## Extending the gate

- **New endpoint** → add an entry to `apis.json`, add functional cases to
  `functional-cases.json`. Contract diff and security scan pick it up
  automatically once `queryParams`/`ownerParam` are set.
- **New security payload class** → add to `PayloadLibrary.java`, wire it into
  `SecurityPayloadRunner.run()`.
- **Stricter performance budget for one endpoint** → currently the hard
  limit is global; if you need per-endpoint limits, extend `apis.json` with
  an optional `responseTimeLimitMs` field and read it in `ResponseTimeGate`.
- **Slack instead of email** → replace the "Email report on failure" step in
  the workflow with a Slack webhook `curl` call; the HTML report path stays
  the same.

---

## Known limitations (be upfront about these)

- The contract diff assumes the OpenAPI spec accurately reflects the code
  (via `drf-spectacular` or similar) — it does not read Django code directly.
- The IDOR probe is a single heuristic (swap the owner id to `999999999`) —
  it won't catch every access-control bug, just the common "trust the
  request param" mistake.
- Security payloads are standard OWASP-style QA probes for regression
  testing your own API, not a full penetration test. Pair this with periodic
  manual security review for anything handling sensitive data.
- Response time samples (3 per endpoint) reduce noise but don't eliminate
  it — for endpoints with highly variable latency, consider raising
  `samplesPerEndpoint` in `ResponseTimeGate`.
