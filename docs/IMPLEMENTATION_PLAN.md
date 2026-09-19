# D0 implementation plan

Companion to [DESIGN.md](DESIGN.md), which stays the authoritative specification. Where this plan makes a choice the design leaves open, the choice is listed in section 8 so it can be overruled before code is written. No code exists yet: the repository holds the design document, this plan and a `.gitignore`.

Four resolutions agreed before implementation, applied throughout this plan:

1. The supervisor agent only recommends. It reads the fingerprint, grantor and audit evidence, submits a `reopen` decision, the reopen-legality guard validates it, and only then does the workflow perform the atomic reopen. There is no `reopen_finding` agent tool.
2. `Lifecycle` lives in `adr.stores` beside `Findings`, so it can call the package-private `casState` legally. `Workflow` calls the public `Lifecycle` service.
3. Everything is built and accepted on `localhost:8080` first. Cloud Run is a separate final phase that starts only after local acceptance and explicit approval.
4. The project is a local git repository with a baseline documentation commit and one logical commit per phase. No remote is created and nothing is pushed without explicit approval.

Stack, fixed by decision D2: Java 21, Javalin 6, Jackson, slf4j-simple, JUnit 5. Vanilla HTML, CSS and JavaScript. No other runtime dependency.

## 1. Repository and package layout

```text
agentic-db-remediation/
├── pom.xml                         one module, shade plugin, surefire, Main-Class adr.app.Main
├── mvnw  mvnw.cmd  .mvn/wrapper/   committed so "./mvnw package" works with only a JDK
├── jvm.options                     the flags from the design, read with "java @jvm.options"
├── Dockerfile  .dockerignore       two stages, see section 5
├── README.md                       one-command local start, one-command deploy, what is simulated
├── docs/DESIGN.md  docs/IMPLEMENTATION_PLAN.md
├── src/main/java/adr/
│   ├── app/       Main, StartupChecks, Api, Commands, Session, SessionRegistry, Sse
│   ├── domain/    Finding, FindingState, Plan, PlanExec, GrantSpec, Approval, Actor,
│   │              Operation, OpState, OperationState, Delivery, Decision (sealed) and its four
│   │              records, Evidence, TimelineEvent, ActorType, Require, InvalidInput
│   ├── workflow/  Workflow, ApprovalService, ExecutionGuard, OutcomeClassifier,
│   │              Retrieval, Verification, DriftPoller, BurstPipeline, GoldenFlow
│   ├── agents/    AgentRunner, ModelClient, RecordedModelClient, Recording, Turn, Trigger,
│   │              Aliases, RecordingValidator
│   ├── broker/    ToolBroker, Tools, ToolSpec, Args (one record per tool), Inputs, Policy,
│   │              Guards, PlanHash, ToolError, ErrorCode
│   ├── target/    TargetDatabase, SimulatedPgTarget, PgState, Caller, Privileges,
│   │              Fingerprint, AppSimulator, OutOfBandClient, CommitHook, TargetException
│   ├── stores/    Stores, InMemoryStores, Timeline, Subscription, Findings, Lifecycle,
│   │              SessionHandle, Corpus, EvidenceLog, Seed, EvidenceExport
│   └── chaos/     Chaos, ChaosSwitches
├── src/main/resources/
│   ├── policy.json
│   ├── seed/       pg-state.json finding.json asset.json telemetry.json job-runs.json
│   │               audit.json corpus.json fleet.json scenarios.json
│   ├── recordings/ analysis-a.json analysis-b.json remediation.json verification.json
│   │               supervision.json
│   └── public/     index.html styles.css app.js
└── src/test/java/adr/
    ├── TestSession.java            builds a Session from the seed, direct executor, tick() access
    ├── stores/TimelineTest  stores/LabelsTest
    ├── app/SessionTest
    ├── target/SimulatedPgTargetTest
    ├── broker/BrokerTest  broker/GuardsTest
    ├── agents/RecordingsTest
    ├── stores/LifecycleTest
    └── workflow/ExecutionTest  DriftTest  BurstTest
```

The class list is longer than the design's "about fifteen" because the later design sections add `Lifecycle`, `Timeline`, `Findings`, `Require`, `RecordingValidator`, `GoldenFlow` and the epoch handling. Each is small. Line count target stays about 1,500 Java and 400 JavaScript, plus roughly 1,000 lines of JSON content.

Two structural rules the layout enforces. `agents` imports only `broker` and `domain`, never `target` or `stores`, so `AgentRunner` can reach only `ModelClient` and `ToolBroker`. `Findings.casState` is package-private in `stores`, and `Lifecycle` is the public service in the same package, so `casState` has exactly one legal caller and no accessor is needed. `Lifecycle` must not depend on `app`, so it takes a `stores.SessionHandle`, a small interface with `lock()` and `isCurrent()` that `app.Session` implements. `isCurrent()` is how the transition learns the session was reset or evicted. The source-scan test checks the import rule and the single `casState` call site.

## 2. Phases

Each phase ends with something visible in the timeline on `localhost:8080`, a green test subset, and one git commit. Phases run in order except the burst, which needs only phases 1 and 2. Nothing touches the cloud before phase 10.

| # | Phase | Depends on | Exit criterion |
| --- | --- | --- | --- |
| 0 | Content: seed files, policy, recordings, captions | Design only | Files exist and parse; validated for real in phase 4 |
| 1 | Skeleton: Maven, Javalin, `Session`, `Timeline`, SSE, cookie, bare page, Docker | 0 | `localhost:8080` streams a heartbeat into a bare timeline, from the JAR and from `docker run` |
| 2 | Domain, validation, stores, seed loading, `Lifecycle`, commands, reset, cap, `/api/state` | 1 | Snapshot returns the seeded finding; illegal commands are refused with 409 |
| 3 | `SimulatedPgTarget`, privilege rule, `apply`, `operationStatus`, `AppSimulator`, `PlanHash` | 2 | Plan v1 fails `month_end_close`, Plan v2 passes, in a unit test |
| 4 | Broker, tools, policy, guards, recorded agents, validator, retrieval, analysis A and B | 3 | `start_analysis` produces Plan v1 then Plan v2 as events and reaches `PLAN_READY` |
| 5 | Approval, execution guard, chaos, remediation, verification, evidence export | 4 | Timeline shows lease won, duplicate refused, `OUTCOME_UNKNOWN`, reconciled, `CLOSED`, mutations 1 |
| 6 | Drift poller, out-of-band client, supervisor, atomic reopen, golden-flow self-check at start-up | 5 | `trigger_drift` reopens a linked child within one poll |
| 7 | Full UI and guided script | 6 | A first-time viewer completes the nine steps without narration |
| 8 | Burst pipeline and gauges | 2 | 5,000 in, queue never above 256, 8 in flight, gauges live |
| 9 | Local acceptance: packaging hardening, README, full local Definition of Done | 7, 8 | Suite green, JAR runs, Docker image runs, nine steps verified in the browser on `localhost:8080` |
| 10 | Cloud Run deployment, after explicit approval | 9 and approval | Hosted guided flow completes in under five minutes |

The design's build plan puts the UI before drift and burst. This plan builds the UI one phase later, after drift, so the front end is written once against the complete event catalogue. The bare timeline from phase 1 keeps risk R1 in check in the meantime.

The design also says "deploy at step 1". By resolution 3 that is replaced with a local Docker run at step 1, which catches the same packaging surprises without a cloud account.

### Phase 0: content

Author every JSON file in section 4 before writing code, as the design advises. The recordings are the critical path: they encode the whole guided flow, and the validator in phase 4 will reject any gap.

Tests: none. Exit: every file parses with strict Jackson settings in a throwaway check.

### Phase 1: skeleton

Delivers `pom.xml`, wrapper, `Main`, `Api` with `GET /`, `GET /healthz`, `GET /api/events`, `Session` with `epoch`, `SessionRegistry` with the cookie, cap of 20, sweep and eviction, `Timeline` with `append`, `appendAll`, `read`, `subscribe`, `closeWith`, the 1,024-slot live queue per subscriber and the separate backlog copy, `Sse` with `keepAlive()`, replay then live loop, heartbeat comment every 15 seconds, `epoch:seq` event IDs and the `session.stale` reply. A bare `index.html` that opens the stream and appends one line per event. `Dockerfile`, `jvm.options`. Run the JAR and the image locally and record resident memory from `docker stats` and start-up time from the log.

Tests: `subscribeDuringAppendsSeesEverySeqOnce`, `slowSubscriberDoesNotBlockAppend`, `largeReplayDoesNotOverflowLiveQueue`, `replayThenLiveIsGapless`, `resetHandsSubscribersToNewTimeline` at the timeline level.

### Phase 2: domain, validation, lifecycle, commands

Delivers the domain records with `Require` in every compact constructor, `Inputs` (strict mapper plus the unwrapping of `ValueInstantiationException`), `Stores` and `InMemoryStores`, `Seed` loading with per-session copies, `Findings` with the package-private compare-and-set, `stores.Lifecycle.transition` with the legal-transition `EnumMap` and the `SessionHandle` check, `Commands` with per-session command lock, per-command argument records, persona check, `Idempotency-Key`, state-table check, `busy()`, reset with epoch handover, the 5,000-event cap at admission and the 100-refusal rule, `Counters`, `GET /api/state` with `session_epoch`, `findings[]`, `active_finding_id`, `can_reset`, labels and `last_seq`. Nothing moves a finding yet except `start_analysis` to `ANALYSING`, which stops there until phase 4.

Tests: `illegalCommandsAreRefused`, `lifecycleCommandsRequireFindingId`, `missingNullBlankInputsAreRefused` for the command half, `resetRefusedWhileBusy`, `timelineCapRefusesFurtherCommands`, `sessionsAreIsolated`, `onlyLifecycleMovesFindings`, `noDurableFieldAndPersistenceIsLabelled` for the event half.

### Phase 3: simulated target

Delivers `PgState` as an immutable record in an `AtomicReference`, `Privileges.attempt` with the five rules, `Fingerprint` and `snapshot` over the role's attributes, memberships and direct grants, `Caller` capability check on every method, `apply` with the lock, ledger, plan-hash set, validation list, precondition, the two `CommitHook` seams and the single reference swap, `operationStatus` under the same lock, `grantMembership` for the out-of-band client only, `AppSimulator` running the scenario action lists from `scenarios.json`, `PlanHash.of` with sorted grants and scenarios and `ORDER_MAP_ENTRIES_BY_KEYS`.

Tests: `planV1FailsMonthEndAndV2Passes`, `ledgerRejectsSecondApply`, `abortBeforeCommitLeavesNoTrace` at the target level, `statusWaitsForInFlightWrite`, plus a caller-capability test that every foreign caller is refused by the target itself.

### Phase 4: broker, guards, recorded agents, analysis

Delivers `Tools` with the fifteen specs, the design's sixteen minus `reopen_finding` by resolution 1, each declaring agents, caller, kind, argument record, outcomes and error codes with their recoverable or stopping mark. `ToolBroker.invoke` with the eleven steps. `Policy` loaded from `policy.json` with its SHA-256 as the policy version. `Guards`: plan lint, completeness, justification, scenario coverage, citation, decision legality, verdict, reopen legality. `Recording`, `Turn`, `Trigger`, `Aliases` with `$ev:`, `$op`, `$finding` substitution and the reserved `retrieval:<doc_id>` aliases. `RecordedModelClient`, `AgentRunner` with the `Semaphore(2)`, step budgets, `activeRuns`, the safe stop and `NEEDS_ATTENTION`. `RecordingValidator` with the seven checks, wired into `StartupChecks`. `Retrieval` over the corpus with keyword and metadata scoring and the mandatory set. `Workflow` for analysis: phase A, retrieval, phase B, guards, lint, policy admission, `plan.versioned` with the diff, `PLAN_READY`.

Tests: `foreignToolAndExtraArgsRefused`, `missingNullBlankInputsAreRefused` for the tool and decision halves, `completenessAndJustificationGuards`, `aliasesBindDistinctEvidence`, `recordingsCoverEveryRequiredTrigger`.

### Phase 5: approval, execution, verification, export

Delivers `ApprovalService` with persona, separation of duties, hash binding, 15-minute expiry and voiding. `ExecutionGuard` with `putIfAbsent` per finding and plan hash, `OpState` compare-and-set, `raceLease` with the latch, `deliveries`, the two gate events in fixed order and the counter. `OutcomeClassifier` with one row per outcome. `Chaos` and `ChaosSwitches` as one-shot switches wired through `CommitHook`, with `chaos.armed` and `chaos.fired` events. The remediation recording through the runner, `RECONCILE_REQUIRED` as a broker precondition, deterministic reconciliation on safe stop. `Verification`: the six assertions, two probes and three scenarios computed and stored before the verifier run, with the verifier's tools returning the stored results. The verdict guard. `CLOSED` with the sealed fingerprint, `evidence.sealed`, corpus overlay write-back. `EvidenceExport` streamed by Jackson with every label field. Kill switch.

Tests: `duplicateDeliveryAppliesOnce` as a `@RepeatedTest(100)` with the extended assertions, `lostResponseReconcilesWithoutSecondMutation`, `abortBeforeCommitLeavesNoTrace` end to end, `approvalRules`, `approvalsCarryDemoPersonaLabel`, `verdictGuardOverridesPass`, `stalePlanReturnsToAnalysis`, `recordingMissStopsSafely`, `everythingSimulatedIsLabelled`.

### Phase 6: drift and reopen

Delivers `DriftPoller` on one scheduled thread with `tick()` public for tests, the subscriber-count condition, the reseal path, `OutOfBandClient.regrant` as `dba_oncall` with the audit row, the supervisor recording. The supervisor reads the fingerprint, the grantor and the audit feed, then submits a `reopen` decision citing them. `AgentRunner` hands the decision to `Guards.reopenLegality`, which compares it with the stored fingerprint difference. Only a passed decision reaches `Workflow.reopen`, which performs the single atomic `Lifecycle.transition` with the child finding and the active-pointer move. A refused decision returns `guard_rejected:reopen_legality` to the runner and, with no corrective turn, ends in `NEEDS_ATTENTION` with the finding still `DRIFT_DETECTED`. The `annotate` decision follows the same path to the reseal transition. `GoldenFlow` running the four configurations headless, wired into `StartupChecks` after the validator. `/healthz` reports the recording hash and the self-check result.

Tests: `driftDetectedAndReopened`, `reopenIsAtomic`, `driftUsesCommonTransition`, `busyConsidersEveryFinding`, `findingHappyPathVisitsEveryState`, `goldenFlowHasNoRecordingMiss`, and `reopenRequiresGuardApproval`: a supervisor decision of `reopen` with no recorded fingerprint difference is refused, no child is created, and the parent stays `DRIFT_DETECTED`.

### Phase 7: user interface

Delivers `index.html`, `styles.css`, `app.js` as specified in section 3, the guided script, the about panel, the persona switch, the explore panel, reconnect and epoch handling, "Waking the demo" and "Start again" states.

Tests: no automated browser tests, by design. The phase is checked by driving `localhost:8080` through the built-in browser: complete the nine steps, reload mid-run and confirm the replay, reset from a stable state, watch the reset propagate to a second tab, confirm every card carries its label and that hostile corpus text renders as text.

### Phase 8: burst

Delivers `BurstPipeline` with the generator thread, `ArrayBlockingQueue(256)`, eight workers, `ConcurrentHashMap.merge` deduplication, atomic counters, the 4 Hz sampler emitting `gauges` without a `seq`, pacing at about 400 findings per second for the HTTP command, the top-20 worklist, and the gauge strip in the UI with the three named constants. `burstRunning` feeds `busy()`.

Tests: `burstIsBounded`, unpaced, three consecutive runs with a `System.gc()` between and a 10% heap tolerance.

### Phase 9: local acceptance

Delivers the final `Dockerfile` with the non-root user, the `.dockerignore`, the README with the measured memory and start-up note, and a full run of the local Definition of Done in section 9. Concretely, in order:

1. `./mvnw package` with the complete suite green, `duplicateDeliveryAppliesOnce` repeated 100 times.
2. `java @jvm.options -jar target/adr-demo.jar` serves `localhost:8080`; `/healthz` reports the recording hash and a passed self-check.
3. `docker build -t adr-demo .` then `docker run --rm -p 8080:8080 adr-demo` serves the same page.
4. The nine-step guided flow is completed in the built-in browser against the Docker container, including reload mid-run, reset from a stable state, reset refused while busy, and the evidence export.
5. Phase commit. Then a deployment approval request that states the local results.

### Phase 10: Cloud Run, after approval

Starts only after phase 9 passes and the user gives explicit approval together with the Google Cloud project and region. Delivers the `gcloud run deploy` in section 5, a check that `/healthz` on the hosted revision reports the same recording hash, the nine steps run once on the hosted URL, and the measured cold start added to the README. Nothing in the code changes in this phase; if something must change, it goes back through phase 9.

## 3. User interface and the guided journey

### Design intent

One page, three columns, one thing in focus at a time. The reviewer should be able to say, after nine presses of Next, what the agent decided, what deterministic code enforced, what the human approved, and how the system recovered. Every element either helps that or is hidden behind a disclosure.

Visual language, structural rather than decorative. `actor_type` picks the form, so it cannot drift from the truth:

| Actor | Form | Cues |
| --- | --- | --- |
| Agent | Card. Header: agent name, permission chip listing its tools, "recorded" tag. Body: decision sentence, why, confidence. Disclosures: evidence list, "could not confirm". | Rounded card, soft tinted background, serif-free prose |
| Gate and tool | One-line monospace strip: shield glyph, rule or tool name, `PASS` or `REFUSED`, and the reason on refusal. Consecutive passes fold into "n checks passed" with an expander. Refusals never fold. | Monospace, no avatar, no prose |
| Human | Signature block: persona name, role, "demo persona, not authenticated" tag, time, plan hash prefix, comment if any | Ruled box, hand-written feel through spacing only |
| Injected fault | Plain notice with a wrench glyph: "Injected: duplicate delivery" | Amber left rule, never red, so it is not read as a failure |
| System | Small grey line, used for `NEEDS_ATTENTION` and session events | Muted |

Colour: neutral greys for chrome, one accent per form. Green for pass and `CLOSED`, red only for refusals and failed checks, amber for injected faults and `NEEDS_ATTENTION`. Tokens on `:root`, dark scheme under `prefers-color-scheme`. Nothing is colour-only: every state also has a word or a glyph. System font stack, one monospace stack. No icons beyond three inline SVG glyphs, shield, signature and wrench. No animation except a 150 ms fade when a focus item appears.

### Layout

```text
┌ Banner: "Prototype: recorded agent responses, a simulated PostgreSQL target and demo personas. ─┐
│  Tools, policy, guards, workflow and fault handling run live."   About this demo   Reset          │
├ Counters: Mutations 1 · Duplicates refused 1 · Refusals 2      Acting as: Sam, security eng. ▾   ├
├──────────────┬──────────────────────────────────────────────┬──────────────────────────────────┤
│ Context      │ Lifecycle rail                               │ Session timeline, in memory      │
│              │ Analyse ─ Approve ─ Remediate ─ Verify ─ Sup. │ [all] [agent] [gates] [human]    │
│ Finding      │   ●         ○           ○          ○      ○  │ 12 gate  tool.read_grants   PASS │
│ Asset        │                                              │ 13 agent Analysis: keep seven … │
│ Plan v1→v2   │ Focus                                        │ 14 gate  guard.justification PASS│
│              │ ┌──────────────────────────────────────────┐ │ …                                │
│ (collapsed:  │ │  the one card, strip group or signature  │ │                                  │
│  telemetry,  │ │  that the current step is about          │ │                                  │
│  job runs,   │ └──────────────────────────────────────────┘ │                                  │
│  audit)      │ Earlier in this stage ▸ (folded list)        │                                  │
├──────────────┴──────────────────────────────────────────────┴──────────────────────────────────┤
│ Step 4 of 9 · Sam tries to approve the plan he requested.   What to look for: …      [ Next ]   │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

At widths under 1,100 px the timeline becomes a drawer opened from the guide bar, and the context column stacks above the rail. Phone width works but is not the target.

Regions and what each shows:

- **Banner and counters.** Always visible. "About this demo" opens a side sheet with: what is recorded, simulated, seeded and live, the `captured: authored` provenance, the demo-persona explanation, the in-memory note, and the list of test names that prove each invariant.
- **Context column.** Finding card open by default: subject role, asset, why it matters in one sentence, current state chip. Asset card folded. Plan card appears at `plan.versioned` and shows v2 as the primary view with a "compare with v1" toggle that renders the two added grants and the added scenario as a diff, plus the hash prefix and `expected_before`. Telemetry, job runs and audit are folded lists with their source label, opened by the reviewer, never by the script.
- **Lifecycle rail.** Five stages. The active stage is filled, done stages are ticked, `NEEDS_ATTENTION` turns the stage amber with the reason underneath. Remediate carries the operation state as a chip, `EXECUTING`, `OUTCOME_UNKNOWN`, `APPLIED`. Supervise shows the child finding as a linked chip after reopen.
- **Focus panel.** Shows the items the current guided step is about, in their visual form, and nothing else. Items arrive through a client-side reveal queue with a 900 ms stagger, skippable by clicking the panel, so a burst of events that arrived in the same millisecond is still read one at a time. The timeline receives every event immediately; only the focus panel paces. When a step completes, its items fold into "Earlier in this stage".
- **Timeline.** One line per event: `seq`, actor glyph, summary. Click expands the payload and labels. Filter chips by actor type. Auto-scrolls while the reviewer is at the bottom, otherwise shows a "new events" pill. The header reads "Session timeline, in memory".
- **Guide bar.** Step counter, one-sentence caption, one "what to look for" sentence, Next. Next is disabled until the step's awaited event has arrived and the reveal queue is drained. After step 9 the bar becomes the explore panel: chaos switches, kill switch, burst count, evidence export link, "Run again on the reopened finding".

Front-end structure: `app.js` holds `state`, `reduce(state, event)`, `render*` per region, `connect()` for the event stream with `Last-Event-ID` reconnect, `send(type, args)` posting with a fresh `Idempotency-Key`, and `GUIDE`, the step array. Static structure uses template strings. Every agent, tool, corpus and seed string reaches the DOM through `textContent`. `aria-live="polite"` on the focus panel, keyboard focus moves to Next when it enables.

### Guided script

The design asks for nine steps mirroring the PRD's beats. The PRD is not in the repository, so the beats below are reconstructed from the design's sections and the test names. Steps with no command are observation steps: Next enables as soon as the awaited event exists, which lets one automatic run be read as several beats without server-side timers.

| # | Persona | Command | Await | Caption and what to look for |
| --- | --- | --- | --- | --- |
| 1 | Sam | `start_analysis` | `agent.decision` for analysis phase A | The analysis agent reads the role graph, grants and telemetry through allowlisted tools and proposes seven privileges. Every read is a tool strip; the proposal is a recorded card. |
| 2 | Sam | none | `finding.state_changed` to `PLAN_READY` | Deterministic retrieval found a rolled-back fix, OUT-0212. The agent checked the objects exist here and revised the plan. Look at the diff: two grants and a month-end scenario were added, and the guards accepted it. |
| 3 | Sam | `request_approval` | `approval.requested` | The plan is bound to a hash. Nothing can execute until someone else approves that exact hash. |
| 4 | Sam | `approve` | `gate.checked` refusal `approval.separation_of_duties` | Sam tries to approve his own request and is refused by code, not by the agent. Refusals counter rises. |
| 5 | Dana | `arm_chaos duplicate_delivery`, `arm_chaos drop_response`, `approve` | `operation.state_changed` to `OUTCOME_UNKNOWN` | Dana approves. Two faults are injected: the tool call is delivered twice and the database's reply is lost. Watch the lease: one delivery wins, one is refused, and the operation becomes unknown rather than retried blindly. |
| 6 | none | none | `operation.state_changed` to `APPLIED` | The agent may only ask for status. Status reads under the same lock, finds the ledger row, and the operation is applied once. Mutations stays at 1. |
| 7 | none | none | `finding.state_changed` to `CLOSED` | Verification computed eleven checks before the verifier saw them. The verdict guard, not the agent, has the last word. The fingerprint is sealed. |
| 8 | Dana | `trigger_drift` | `finding.created` | An on-call DBA re-grants the owner role outside the system. The poller sees the fingerprint change, the supervisor classifies it as drift, and a linked child finding opens. |
| 9 | none | `start_burst 5000` | `burst.completed` | 5,000 findings through a 256-slot queue and 8 workers. Queue, in-flight and heap stay under their named bounds. |

After step 9 the explore panel appears with a closing line: "Everything the agents said was recorded. Everything that stopped them was live." The evidence export link sits beside it.

## 4. Seed data and recordings

All under `src/main/resources`. Every record validates with `Require` at start-up.

### Seed files

| File | Contents to author |
| --- | --- |
| `pg-state.json` | Roles `orders_owner`, `orders_app` (login), `dba_legacy`, `dba_oncall`, `agent_analysis`, `agent_remediator`, `agent_verifier`, `agent_supervisor`. Memberships: `orders_owner` → `orders_app`, grantor `dba_legacy`, no admin option (the finding); `orders_owner` → `dba_oncall`, grantor `orders_owner`, admin option true (what lets the drift happen). Objects: schema `orders`, tables `customers`, `orders`, `order_items`, `ledger_archive`, function `close_period(date)` with `publicExecute: false` and `requires: [INSERT orders.ledger_archive, SELECT orders.orders]`, all owned by `orders_owner`. Direct grants to `orders_app`: none. |
| `finding.json` | Fixed UUID, type `pg.excessive_privilege.owner_membership`, subject role `orders_app`, asset `orders-db-prod`, one-sentence description, criticality high, detected date. |
| `asset.json` | Asset name, owning team, application "Orders service", PII label true, environment "production (demo)". |
| `telemetry.json` | 14 days of rows for `orders_app`: `SELECT customers`, `SELECT`, `INSERT`, `UPDATE orders`, `SELECT`, `INSERT order_items`, `last_seen_days_ago` 0 to 2. Deliberately no row for `ledger_archive` or `close_period`. A few rows for `dba_oncall` as noise. |
| `job-runs.json` | `month_end_close` job, role `orders_app`, calls `close_period`, last run 19 days ago. Two other jobs as noise. |
| `audit.json` | The original grant by `dba_legacy`, 400 days ago. The drift switch appends the `dba_oncall` re-grant with a ticket reference at run time. |
| `corpus.json` | Eight documents with `id`, `kind`, `title`, `finding_type`, `asset_family`, `disposition`, `failed_scenario`, `required_privileges`, `tags`, `body`. RB-07 runbook for right-sizing owner membership. OUT-0212, billing-db, rolled back, `failed_scenario: month_end_close`, `required_privileges: [EXECUTE orders.close_period(date), INSERT orders.ledger_archive]`. OUT-0198, inventory-db, success. OUT-0231, poisoned: body contains an instruction to grant `SUPERUSER` and skip approval, `required_privileges: [SUPERUSER]`. Four distractors: a vacuum runbook, a password-rotation runbook, a network note, and an outcome of a different finding type. |
| `fleet.json` | 60 assets and 25 controls with weights, for burst scoring. |
| `scenarios.json` | `place_order`: `USAGE orders`, `SELECT customers`, `INSERT orders`, `INSERT order_items`, `UPDATE orders`. `read_order`: `USAGE orders`, `SELECT orders`, `SELECT order_items`, `SELECT customers`. `month_end_close`: `USAGE orders`, `EXECUTE close_period(date)`. |
| `policy.json` | Exactly the document in the design. |

Plan v1 is the seven privileges from telemetry. Plan v2 adds `INSERT orders.ledger_archive` and `EXECUTE orders.close_period(date)` and the `month_end_close` scenario. `close_period`'s `requires` list is what makes v1 genuinely fail the scenario in the simulator.

### Recordings

One file per agent and phase, `"captured": "authored"`, every call with an `as` alias. Triggers each file must cover, derived from the tools it calls and their declared outcomes:

| File | Turns |
| --- | --- |
| `analysis-a.json` | `start` calls `read_role_graph`, `read_grants`, `read_query_telemetry`. `ok:read_query_telemetry` submits the `fix` decision with seven retained grants, scenarios `place_order` and `read_order`, evidence aliases. |
| `analysis-b.json` | As in the design: `start` reads both catalog objects, `ok:read_catalog_object` reads job runs, `ok:read_job_runs` submits the revised decision with OUT-0212 incorporated and the unknown stated. |
| `remediation.json` | `start` calls `preflight_check`. `ok:preflight_check` calls `apply_right_size_role` with `$op`. `ok:apply_right_size_role` submits `applied`. `error:apply_right_size_role:TIMEOUT` and `:RECONCILE_REQUIRED` call `get_operation_status`. `:ABORTED_BEFORE_COMMIT` and `:LOCK_TIMEOUT` retry `apply` under the same alias. `ok:get_operation_status:applied` submits `applied`. `:not_applied` retries `apply`. `:in_flight` retries status. |
| `verification.json` | `start` calls `assert_privileges`, `run_negative_probe`, `run_health_scenarios`. `ok:run_health_scenarios` submits `pass` citing the three results. |
| `supervision.json` | `start` calls `read_control_fingerprint`, `read_membership_grantor`, `read_role_change_log`. `ok:read_role_change_log` submits the `reopen` decision, citing the three evidence aliases, with the reason that the grantor is `dba_oncall`, the audit row names a ticket, and no operation of this finding explains the change. No tool call changes state; the workflow reopens after the guard passes. |

Stopping codes need no turns. The stretch variant `analysis-b-overlooks.json` is out of scope by decision D1 and is not authored.

### Event catalogue

The UI, the guided script and the tests all key on `kind`, so it is fixed here.

Session level, `finding_id` null: `session.reset`, `session.expired`, `session.stale`, `chaos.armed`, `chaos.fired`, `kill_switch.changed`, `burst.started`, `burst.completed`, `system.notice`, and `gauges` without a `seq`.

Finding level: `finding.state_changed`, `finding.created`, `session.active_finding_changed`, `agent.turn`, `agent.decision`, `tool.invoked` with `ok` or the refusal code, `gate.checked` with rule name and `PASS` or `REFUSED`, `retrieval.completed`, `plan.versioned`, `approval.requested`, `approval.decided`, `approval.voided`, `operation.created`, `operation.state_changed`, `lease.won`, `delivery.duplicate_refused`, `verification.completed`, `evidence.sealed`, `drift.detected`, `drift.classified`.

## 5. Build, Docker and Cloud Run

**Maven.** One module, `adr:adr-demo`. Dependencies: `io.javalin:javalin`, `com.fasterxml.jackson.core:jackson-databind`, `org.slf4j:slf4j-simple`, test scope `org.junit.jupiter:junit-jupiter`. `maven-shade-plugin` produces `target/adr-demo.jar` with `Main-Class: adr.app.Main`. Surefire runs the suite in `package`. Wrapper committed.

**Local.**

```bash
./mvnw -q package && java @jvm.options -jar target/adr-demo.jar
```

`PORT` is honoured, default 8080. No other environment variable.

**jvm.options.** `-XX:+UseSerialGC -Xms32m -Xmx128m -Xss512k -XX:MaxMetaspaceSize=96m -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError`.

**Dockerfile.** Stage one `maven:3.9-eclipse-temurin-21` runs `mvn -q package`, tests included, so a red suite never produces an image. Stage two `eclipse-temurin:21-jre-alpine`, non-root user, copies the JAR and `jvm.options`, `ENTRYPOINT ["java","@jvm.options","-jar","adr-demo.jar"]`, `EXPOSE 8080`.

**Local Docker.**

```bash
docker build -t adr-demo . && docker run --rm -p 8080:8080 adr-demo
```

**Start-up order.** Load and validate policy and seeds. Run `RecordingValidator`. Run `GoldenFlow` in a throwaway session. Bind the port. Any failure logs the file, turn and reason and exits non-zero.

**Cloud Run, phase 10 only.** Run after local acceptance and explicit approval, with the project and region supplied at that time.

```bash
gcloud run deploy adr-demo --source . --region REGION --allow-unauthenticated --min-instances 0 --max-instances 1 --concurrency 80 --cpu 1 --memory 512Mi --cpu-boost --timeout 3600
```

Removal after the review is one `gcloud run services delete` plus the Artifact Registry image.

## 5a. Git workflow

The project root is a local git repository with no remote. Rules:

- The baseline commit holds the design, this plan and `.gitignore`.
- Each phase ends with one commit whose subject is `Phase N: <what it delivers>`. A phase that needs a fix after its commit gets a follow-up commit, never an amend.
- `.gitignore` excludes `target/`, IDE folders, OS files, logs and the local scratch output. Nothing under `src/main/resources` is ignored, since seeds and recordings are content the build needs.
- No GitHub remote is created and nothing is pushed without explicit approval.

## 6. Invariants and checklist mapped to code and tests

| Invariant | Where it lives | Proved by | Phase |
| --- | --- | --- | --- |
| I1 allowlisted tools only | `ToolBroker` step 1, `SimulatedPgTarget` caller check | `foreignToolAndExtraArgsRefused`, caller test | 3, 4 |
| I2 no model value becomes a write parameter | `Args.ApplyRightSizeRole(operationRef)` only; handler loads `PlanExec` by hash | `foreignToolAndExtraArgsRefused` | 4 |
| I3 policy allow and bound approval | `Policy.evaluate`, `ApprovalService`, `Lifecycle` T1 | `approvalRules` | 5 |
| I4 requester cannot approve | `ApprovalService.separationOfDuties` | `approvalRules`, guided step 4 | 5 |
| I5 one mutation under duplicate delivery | `ExecutionGuard.raceLease`, ledger under lock | `duplicateDeliveryAppliesOnce` x100 | 5 |
| I6 change and marker together | `SimulatedPgTarget.apply` single `state.set(draft)` | `abortBeforeCommitLeavesNoTrace` | 3, 5 |
| I7 pre-commit error is retryable | `OutcomeClassifier` | `abortBeforeCommitLeavesNoTrace`, classifier row tests | 5 |
| I8 lost response is unknown, write refused | `OutcomeClassifier`, broker precondition `RECONCILE_REQUIRED` | `lostResponseReconcilesWithoutSecondMutation` | 5 |
| I9 status never reads in-flight write | `operationStatus` takes the apply lock | `statusWaitsForInFlightWrite` | 3 |
| I10 verifier cannot pass failed checks | `Guards.verdict` over `Verification` store | `verdictGuardOverridesPass` | 5 |
| I11 deterministic drift, linked reopen | `DriftPoller.tick`, `Guards.reopenLegality`, `Workflow.reopen`, `Lifecycle` reopen effects | `driftDetectedAndReopened`, `reopenRequiresGuardApproval` | 6 |
| I12 bounded concurrency | `BurstPipeline` constants, `AgentRunner` semaphore | `burstIsBounded` | 8 |
| I13 nothing simulated unlabelled | Envelope fields, `EvidenceExport`, banner, tags | `everythingSimulatedIsLabelled`, `approvalsCarryDemoPersonaLabel` | 5, 7 |
| I14, I21 one lifecycle door | `stores.Lifecycle.transition`, package-private `Findings.casState` in the same package | `onlyLifecycleMovesFindings`, `illegalCommandsAreRefused` | 2 |
| I15 recording coverage and safe stop | `RecordingValidator`, `GoldenFlow`, `AgentRunner.safeStop` | `recordingsCoverEveryRequiredTrigger`, `goldenFlowHasNoRecordingMiss`, `recordingMissStopsSafely` | 4, 5, 6 |
| I16 agent gets the winner's result | `raceLease` returns winner, loser only in `deliveries` | `duplicateDeliveryAppliesOnce` extended | 5 |
| I17 reset only when stable | `Commands.reset` under session lock, `busy()` | `resetRefusedWhileBusy`, `busyConsidersEveryFinding` | 2, 6 |
| I18 explicit input validation first | `Require`, `Inputs`, broker step 3 | `missingNullBlankInputsAreRefused` | 2, 4 |
| I19 no missed event, no I/O under lock | `Timeline.subscribe`, `appendAll`, `Sse` sender thread | four timeline tests | 1 |
| I20 reopen atomic | one `Lifecycle.transition` with effects | `reopenIsAtomic` | 6 |
| I22 reset hands browsers over | epoch, `closeWith(session.reset)`, `session.stale` | `resetHandsSubscribersToNewTimeline` | 1, 2 |

The review checklist maps to phases as follows. Phase 2 owns the validation, lifecycle, `finding_id`, snapshot fields, reset gating, cap and `durable` items. Phase 1 owns the timeline, socket-write, epoch and event-ID items. Phase 3 owns the `apply` swap, lock sharing and chaos-seam placement. Phase 4 owns the `AgentRunner` import rule, `operation_ref` only, aliases, and the start-up validator. Phase 5 owns compare-and-set at every call site, `RECONCILE_REQUIRED`, not inspecting the target on `ResponseLost`, stored verification results, and persona labels. Phase 6 owns the poller making no agent call before a difference, the guard-before-reopen order, the single reopen call and the `casState` caller rule. Phase 7 owns `textContent` only, the four labels on screen, and the unknown-session page. Phase 8 owns the named constants on the gauges. Phase 9 owns the full-suite tick. Phase 10 owns maximum one instance.

Test harness rule: `TestSession` injects a direct executor so agent runs complete on the calling thread, which makes every test deterministic except the two threads inside `raceLease`, which are the point.

## 7. Risks

| # | Risk | Mitigation |
| --- | --- | --- |
| R1 to R5 | As in the design | As in the design, with the UI moved one phase later and the bare timeline covering the gap |
| R6 | Content is the real critical path: about 1,000 lines of JSON, and one missing trigger blocks start-up | Phase 0 first. The validator's error names file and turn. Recording tables above list every required turn. |
| R7 | The source-scan test depends on the working directory | Resolve `src/main/java` from the project base directory system property surefire sets; skip with a clear message if absent, never pass silently. |
| R8 | The heap-flat burst assertion is sensitive to garbage collection | `System.gc()` and a short pause between bursts, 10% tolerance, serial collector in tests too. |
| R9 | Cloud Run's proxy buffers or times out the event stream | Heartbeat comment every 15 seconds, reconnect with `after`, 3,600-second timeout. Verified at phase 10; it is the one behaviour local acceptance cannot cover, so the first hosted check is the stream. |
| R10 | Javalin's SSE and virtual-thread APIs move between minor versions | Pin one Javalin 6 version in the POM; the SSE handler is one class, so an API change is contained. |

## 8. Ambiguities and decisions

None of these blocks implementation. Each is resolved below with an assumption that can be overruled before the phase that depends on it.

| # | Question | Decision taken | Overrule by |
| --- | --- | --- | --- |
| A1 | The design says verification results are computed before the verifier sees them, and also lists three verification tools in the verifier's allowlist | The workflow runs the three checks as system code and stores the results on the `REMEDIATING` to `VERIFYING` move. The verifier's recorded calls to the same tools return the stored results with fresh evidence IDs. The verdict guard reads the store. | Phase 5 |
| A2 | The decision-legality guard names `proceed` and `reconcile`, but the runner ends a run on any decision | Remediation decisions are `applied` or `escalate`. `applied` is legal only when the operation is `APPLIED`. Pre-flight and reconciliation legality are broker preconditions on `apply_right_size_role` and `get_operation_status`. | Phase 5 |
| A3 | The nine beats reference a PRD that is not in the repository | Reconstructed in section 3. [IMPLEMENTATION_RULES.md](IMPLEMENTATION_RULES.md) states the PRD is intentionally excluded, so the reconstruction stands. | Phase 7 |
| A4 | How one automatic run reads as several beats without timers | Observation steps with no command, plus a client-side reveal stagger in the focus panel only. Timeline delivery is immediate. | Phase 7 |
| A5 | "6, 5, 6, 4, 4" step budgets for four agents | Analysis A 6, analysis B 5, remediation 6, verification 4, supervision 4 | Phase 4 |
| A6 | `start_burst` has only `count`, but guided mode is paced | The HTTP command is always paced at about 400 per second; `BurstPipeline.run(count, paced)` lets tests run unpaced | Phase 8 |
| A7 | Whether `read_catalog_object` NOT_FOUND needs a recorded turn | Declared stopping. The seed always has both objects, so it is unreachable in D0. | Phase 4 |
| A8 | What happens after a stale plan sends the finding back to `ANALYSING` | The same analysis recordings run again against the changed state and produce a new plan hash with a new `expected_before`. The test asserts the transition and the voided approval only. | Phase 5 |
| A9 | Fingerprint contents | SHA-256 of canonical JSON: role attributes, memberships where the role is the member, direct grants to the role, all sorted | Phase 3 |
| A10 | Retrieval scoring | Term overlap over title, tags and body, plus a fixed boost for matching `finding_type` and `asset_family`. Mandatory set: every outcome document of the finding's type. | Phase 4 |
| A11 | Javalin version | Latest Javalin 6.x at the time of phase 1, pinned | Phase 1 |
| A12 | The design's tool catalogue lists `reopen_finding` as a supervision tool, while its lifecycle section says no agent moves a finding | Resolution 1: the tool is removed. The policy entry `tools.reopen_finding.findingStates` is kept and evaluated by the workflow on the reopen transition, so policy still gates the move. | Agreed |
| A13 | Where `Lifecycle` lives so that `casState` stays package-private | Resolution 2: `adr.stores.Lifecycle`, public, with `SessionHandle` to avoid depending on `app` | Agreed |

Inputs needed only at phase 10: explicit approval to deploy, the Google Cloud project and region, and whether to keep one minimum instance warm during a scheduled review.

## 9. Definition of Done

**Local, required before any deployment request**

- [ ] Every test named in sections 2 and 6 passes in `./mvnw package`, with `duplicateDeliveryAppliesOnce` repeated 100 times.
- [ ] `RecordingValidator` and `GoldenFlow` pass at start-up and `/healthz` reports both.
- [ ] `./mvnw -q package && java @jvm.options -jar target/adr-demo.jar` starts the demo on `localhost:8080` with no environment variables and no network access.
- [ ] `docker build -t adr-demo . && docker run --rm -p 8080:8080 adr-demo` starts the same demo.
- [ ] The nine-step guided flow is completed in the browser against the Docker container, and a first-time reviewer can state the agent's decisions, the deterministic refusals, the human approval and the recovery from the lost response.
- [ ] The banner, the "recorded" tag, the "Simulated PostgreSQL" label, the "demo persona, not authenticated" tag and the in-memory note are visible on the page and present in the evidence export.
- [ ] Reload mid-run replays the timeline without gap or duplicate; reset from a stable state reloads every open tab onto the new timeline; reset while busy is refused with a visible gate event.
- [ ] Hostile corpus text renders as text.
- [ ] Every item in the design's review checklist is ticked, with the phase that owns it noted in section 6.
- [ ] Measured resident memory and local start-up time are recorded in the README.
- [ ] Every phase has its commit and the working tree is clean.

**Hosted, phase 10, after explicit approval**

- [ ] The Cloud Run service runs with maximum one instance and minimum zero, `/healthz` reports the same recording hash as the local build, and the guided flow completes on the hosted URL in under five minutes.
- [ ] The measured cold start is added to the README.
