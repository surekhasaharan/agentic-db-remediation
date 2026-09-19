# Implementation notes

What was built against [DESIGN.md](DESIGN.md) and [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md), the deviations made on the way and why, the measurements, and the limitations that remain. Phases 0 to 9 are complete and committed locally. Phase 10, Cloud Run, has not started.

## Deviations from the plan and the design

Each one is small and deliberate. None changes an invariant.

| # | What | Why |
| --- | --- | --- |
| 1 | The build compiles with `--release 21` on the host's JDK 25. The Docker image builds on JDK 21 and runs on a JRE 21. | No JDK 21 is installed on this machine and installing one was outside the task. The bytecode and the image are Java 21. |
| 2 | Broker step 5, the protected-target check, applies to write and probe tools, not to reads. | The supervisor legitimately reads `orders_owner` through `read_membership_grantor` and `read_role_change_log`. |
| 3 | A policy `gate.checked` event is appended for write and probe tools and for every refusal, not for every read. | Six passing gate strips per read-heavy turn would bury the decisions the reviewer should see. Read refusals still appear. |
| 4 | Required recording coverage is computed for each tool that ends a turn, not for every tool a turn calls. | The design's wording would demand dead turns for the first two calls of a three-call turn. Reachability is exact this way. |
| 5 | Remediation decisions are `applied` or `escalate`. Pre-flight and reconciliation legality are broker preconditions on `apply_right_size_role`. | Plan decision A2. The runner ends a run on any decision, so `proceed` and `reconcile` cannot be decisions. |
| 6 | The move from `REMEDIATING` to `VERIFYING` happens when the remediation run ends with the operation `APPLIED`, rather than from a callback the moment `apply` returns. | The verifier's cards then follow the remediation agent's final card in the timeline instead of interleaving with it. The guard's outcome still drives the move. |
| 7 | Verification tools return results stored by the workflow before the verifier's run. | Plan decision A1. |
| 8 | Analysis phases A and B share one run context. | The reserved `retrieval:<doc_id>` aliases and the citation guard span the whole stage. |
| 9 | A workflow refusal that a service already gated carries a `recorded` flag so command admission does not append a second gate event. | Otherwise the self-approval counted as two refusals. |
| 10 | `finding.state_changed` carries `actor_type: system` and renders as a state line, not as a gate strip. | A transition is not a pass or refuse verdict; the state line reads better under the rail. |
| 11 | The focus panel reveals a step's items with a client-side stagger; the timeline receives every event immediately. When the tab is hidden everything is revealed at once. | Plan decision A4, plus timer throttling in background tabs. |
| 12 | Step budgets: analysis A 6, B 5, remediation 6, verification 4, supervision 4. | Plan decision A5. |
| 13 | The evidence export adds `target_mode: simulated` to each evidence item whose source label is the simulated target or the application simulator. | Evidence records themselves carry only a source label; the export is where the design asks for the field. |
| 14 | The reopen marker `reopened_by_seq` records the sequence of the `REOPENED` transition event. | Its two companion events are the next two sequence numbers. |
| 15 | `OutOfBandClient` lives in `adr.target` but takes the audit feed as a parameter; the chaos event is appended by the workflow. | Keeps the target package free of the app package. |
| 17 | In guided mode the page renders the finding state, rail, counters and timeline only up to the current step's awaited event (the guided cursor), deriving that view from events; live state is shown when the reviewer reaches the step or enters explore mode. Explore controls are state-aware, disabled controls explain why, every refused command shows a toast, and Export evidence downloads `evidence.json`. | UI feedback after local acceptance: during step 5 the rail and timeline were already showing recovery and verification. |
| 18 | Explore controls depend on the finding state and the persona acting: Start analysis needs Sam, Approve and Reject need Dana. The group is labelled with the state and shows the next valid action or the persona switch it needs; changing the persona re-renders the controls. | UI feedback: after the guided flow Dana is selected and the reopened finding is OPEN, so Start analysis looked available but was refused. |
| 16 | The policy is deny by default for tools: `evaluateTool` refuses any tool without an explicit entry, and `policy.json` lists every tool the demo permits, with state constraints where they apply. Start-up fails if a recording calls a tool the policy does not permit. | Review comment after local acceptance. An earlier version allowed unlisted tools implicitly, which contradicted `"default": "deny"`. Deterministic least privilege is the product's point. |

## Measurements

| Measure | Value | How |
| --- | --- | --- |
| Start-up to `/healthz` ok, JAR | 0.6 s | Wall clock from launch to first healthy reply, including seed and policy validation, the recording validator and four golden-flow runs |
| Resident memory, JAR, idle | 83 MB | `ps -o rss` on the JVM with `jvm.options` |
| Resident memory, JAR, after the nine beats and a 5,000-finding burst | 70 MB | Same, after the serial collector returned memory |
| Container memory, `eclipse-temurin:21-jre-alpine` | 62 MiB idle, 65 MiB after the nine beats and a burst | `docker stats` |
| Container start to healthy | under 1 s | polled `/healthz` after `docker run` |
| Image size | 302 MB | `docker images` |
| Burst, paced (guided) | 5,000 in about 12.5 s, queue peak 2 to 5 of 256, in flight peak 2 of 8, about 1,430 unique | `burst.completed` payload |
| Burst, unpaced (tests) | 5,000 accounted for, queue never above 256, in flight never above 8, heap flat across three runs | `burstIsBounded` |
| Test suite | 274 tests, 0 failures, `duplicateDeliveryAppliesOnce` x100 | `./mvnw package` |

## Local acceptance record

- Full suite green from `./mvnw clean package`.
- Release review: the `.gitignore` pattern `target/` had also matched `src/main/java/adr/target`, so the simulated-target package and its test were missing from every earlier commit although present in the working tree and in every local build. The pattern is now `/target/` and the package is committed.
- JAR started with `java @jvm.options -jar target/adr-demo.jar`; `/healthz` reported the recordings hash and all four golden configurations ok.
- The nine beats were driven over HTTP and in the browser. State sequence for the parent finding: `ANALYSING, PLAN_READY, AWAITING_APPROVAL, REMEDIATING, VERIFYING, CLOSED, DRIFT_DETECTED, REOPENED`; a child finding opened in `OPEN`; counters ended at mutations 1, duplicates refused 1, refusals 1.
- Browser checks: the four visual forms; folded checks and tool calls; the plan diff; reload mid-run resumes into explore mode with the timeline replayed; reset from a stable state reloads onto a new epoch; reset is disabled while busy; the About sheet; the evidence export; hostile corpus text renders as text.
- Docker: `docker build -t adr-demo .` ran the suite inside the build stage; `docker run --rm -p 8080:8080 adr-demo` served the page; the nine steps were completed in the browser against the container with the same state sequence, counters and burst bounds; reset produced a new epoch; the container log held no errors.

## Limitations that remain

- No automated browser tests, by design. The page was checked by hand through the built-in browser.
- The drift poll runs only while a browser holds the event stream open, as the design intends for request-based billing. A headless HTTP client must keep `/api/events` open to see drift within two seconds.
- In the paced guided burst the workers outrun the generator, so the queue gauge rarely climbs above single digits. The bounds are enforced regardless, and the unpaced test shows the same limits under load.
- Rollback, live model mode, a real PostgreSQL target, authentication and persistence are deferred, as decision D1 and the design's deferred list state.
- The simulation does not model PostgreSQL's grantor rules on revoke, MVCC, connections or a real lock manager. The banner and About sheet say so.
- Cloud Run has not been exercised. The event stream through Cloud Run's proxy is the one behaviour local acceptance cannot cover.
