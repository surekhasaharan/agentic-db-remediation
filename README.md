# Agentic database remediation, D0 prototype

A self-contained demonstration of an agentic PostgreSQL privilege remediation system: agents propose, deterministic code decides, a human approves, faults are injected and recovered from, verification and drift supervision run for real. One Java 21 process, Javalin, Jackson and a vanilla web page. No account, key, database or network access is needed.

**Prototype: recorded agent responses, a simulated PostgreSQL target and demo personas. Tools, policy, guards, workflow and fault handling run live.** The banner on the page, the tags on every card, the source labels on every piece of evidence and the fields in the evidence export all say so.

## Prerequisites

- A JDK 21 or newer, or Docker. Nothing else.
- Network access on the first build only, so the Maven wrapper can download Maven and the dependencies. The running application makes no network calls.
- A browser. Port 8080 by default; set `PORT` to change it.

## Run it

With a JDK 21 or newer:

```bash
./mvnw -q package && java @jvm.options -jar target/adr-demo.jar
```

With only Docker installed:

```bash
docker build -t adr-demo . && docker run --rm -p 8080:8080 adr-demo
```

Then open http://localhost:8080 and press Next nine times. `PORT` is honoured if set. There are no other environment variables.

Start-up loads and validates the seed files, the policy and the five recordings, then runs the guided flow headless four times (no faults, duplicate delivery plus dropped response, abort before commit, drift) before binding the port. A failure exits non-zero. `GET /api/health` reports the policy version, the recordings hash and the self-check result; `/healthz` serves the same document locally, but Cloud Run's front end reserves that path, so use `/api/health` on the hosted URL.

## What the guided flow shows

| Step | What happens | What to look for |
| --- | --- | --- |
| 1 | The analysis agent reads the role graph, grants and telemetry through allowlisted tools | Recorded cards, tool strips, a seven-privilege proposal |
| 2 | Deterministic retrieval finds a rolled-back past fix; the agent revises the plan | Plan v2 adds two grants and the month-end scenario; every guard passes |
| 3 | Sam requests approval of plan v2 | The approval binds to the plan hash |
| 4 | Sam tries to approve his own request | Refused by the separation-of-duties gate, not by an agent |
| 5 | Dana approves; duplicate delivery and a lost response are injected | One delivery wins the lease, one is refused; the outcome becomes unknown |
| 6 | The agent may only ask for status | Status reads under the same lock, finds the ledger row; applied once |
| 7 | Eleven checks are computed before the verifier sees them | The verdict guard has the last word; the fingerprint is sealed |
| 8 | An on-call DBA re-grants the owner role outside the system | The poller detects drift; the supervisor recommends; a guard validates; a linked child opens |
| 9 | 5,000 findings through a 256-slot queue and 8 workers | Live gauges stay under the named bounds |

After step 9 the page hands over to an explore panel: inject faults, block writes with the kill switch, run the burst again, or run the lifecycle on the reopened finding. Controls enable only in the states and for the persona that admit them, and say why otherwise. The guided steps take about three minutes; a first-time reviewer should be able to say afterwards what the agents decided, what deterministic code enforced, what the human approved, and how the system recovered from the lost response.

## Evidence export

**Export evidence** in the explore panel downloads `evidence.json` for the session: every finding with its plans, approvals, operations and verification results, every evidence item with its source label, the whole timeline, and the labels (`agent_mode: recorded`, `target_mode: simulated`, `identity_mode: demo_persona`, `persistence: in_memory_session`, `captured: authored`). The same document is served at `GET /api/evidence.json`.

## What is real and what is not

| Real, executes on every run | Simulated or seeded, labelled |
| --- | --- |
| Tool allowlists and per-caller capabilities enforced by the target | Agent responses: hand-authored recordings selected turn by turn from live tool results |
| Strict argument validation: missing, null and blank refused by name | The PostgreSQL target: an in-memory model of roles, memberships, grants, a transaction, a lock and a ledger |
| Policy with deny by default: every tool needs an explicit entry; plan lint, plan hash, approval binding, separation of duties, expiry | Sam and Dana: demo personas chosen in the page, never authenticated |
| Completeness, justification, scenario coverage, citation, verdict and reopen guards | Telemetry, job history, audit feed, knowledge corpus and fleet grid: JSON resources per session |
| Compare-and-set lease, atomic commit with its ledger marker, outcome classification, reconciliation | |
| Verification assertions, negative probes and application scenarios against the privilege engine | |
| Drift detection by fingerprint poll, atomic reopen, bounded burst | |

Everything lives in memory. Reset, expiry, restart or scale to zero discards it by design.

## Measurements on this machine

| Measure | Value |
| --- | --- |
| Start-up to healthy, JAR, including validator and golden flow | 0.6 s |
| Resident memory, JAR, idle | 83 MB |
| Resident memory, JAR, after a full flow and a 5,000-finding burst | 70 MB |
| Container memory, Docker, idle and after a full flow and burst | 62 MiB idle, 65 MiB after |
| Container healthy after start | under 1 s |
| Cloud Run cold start, first byte after 18 minutes idle, us-west1, CPU boost | 2.0 to 2.4 s; warm requests about 0.12 s |
| Image size | 302 MB (Alpine JRE 21) |
| Test suite | 274 tests, `duplicateDeliveryAppliesOnce` repeated 100 times |

The host JDK here is 25; the build compiles for Java 21 (`--release 21`) and the Docker image builds and runs on Java 21.

## Layout

```text
src/main/java/adr/
  app       Main, StartupChecks, Api, Commands, Session, SessionRegistry, Sse, Snapshot, EvidenceExport
  domain    Finding, Plan, PlanExec, Approval, Operation, Decision, TimelineEvent, Require, Labels
  workflow  Workflow, ApprovalService, ExecutionGuard, Verification, DriftPoller, BurstPipeline, GoldenFlow
  agents    AgentRunner, ModelClient, RecordedModelClient, RecordingValidator
  broker    ToolBroker, Tools, Args, Policy, Guards, PlanHash
  target    TargetDatabase, SimulatedPgTarget, PgState, Privileges, Fingerprint, AppSimulator, OutOfBandClient
  stores    Stores, InMemoryStores, Timeline, Findings, Lifecycle, Corpus, Seed
  chaos     Chaos, ChaosSwitches
src/main/resources/
  policy.json  seed/*.json  recordings/*.json  public/index.html  public/static/{styles.css,app.js}
```

The three extension points are `ModelClient`, `TargetDatabase` and `Stores`. `AgentRunner` reaches only `ModelClient` and `ToolBroker`. `Findings.casState` is package-private and `stores.Lifecycle` is its only caller.

## Documentation

The documents under `docs/` are read in this order. The PRD describes the full product idea; the demo was scoped down from it, so the design, not the PRD, describes what is built here.

| Document | Purpose |
| --- | --- |
| `docs/PRD.md` | The original product idea and competitive analysis: the full agentic remediation product with live agents and a real database. It was scoped down for this demo and does not describe the current design. |
| `docs/DESIGN.md` | The authoritative specification of the D0 prototype: invariants, the simulated target, recorded agents, the lifecycle, fault handling, the page, tests and the Definition of Done. Where it conflicts with the PRD, the design wins. |
| `docs/IMPLEMENTATION_RULES.md` | Precedence between the documents, scope boundaries, workflow, git and completion rules. |
| `docs/IMPLEMENTATION_PLAN.md` | The phased build plan agreed before any code: packages, phases, tests per phase, the guided journey, decisions and the Definition of Done. |
| `docs/IMPLEMENTATION_NOTES.md` | What was built, every deviation from the plan and the design with its reason, the measurements, the acceptance record and the limitations that remain. |

## Tests

```bash
./mvnw package
```

runs the whole suite: 274 tests, no mocks, no browser. The names in the design map to classes under `src/test/java/adr`: timeline delivery, session reset and cap, command and tool input matrices, the simulated target, the broker, policy and guards, recordings and the validator, analysis, execution with every fault seam, verification, drift and reopen, the burst, and the label scan. The Docker build runs the same suite in its build stage, so a red suite never produces an image.

## Limitations

- The agents are recordings. If a situation has no recorded turn, the run stops and says "Demo flow unavailable" rather than guessing.
- The PostgreSQL target is a model of roles, memberships, grants, a transaction, a lock and a ledger. It parses no SQL and does not model grantor rules on revoke, MVCC, connections or a real lock manager.
- Personas are chosen in the page; nothing is authenticated. Separation of duties compares persona ids.
- Rollback, live model mode, a real PostgreSQL target, authentication and persistence are deferred by design.
- Drift is detected by a two-second poll that runs only while a browser holds the event stream open.
- There are no automated browser tests; the page is checked by hand.

More detail, including every deviation from the design and the measurements, is in `docs/IMPLEMENTATION_NOTES.md`.

## Cloud Run

Deployment to Cloud Run is deferred. It is a separate step that runs only after explicit approval, with the project and region supplied at that time, and it changes nothing in the code: scale to zero, maximum one instance, no other cloud resource. See `docs/IMPLEMENTATION_PLAN.md`, phase 10.
