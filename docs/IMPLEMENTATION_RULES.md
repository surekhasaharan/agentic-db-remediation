# Implementation rules

## Source of truth

1. `DESIGN.md` is authoritative for the D0 product scope, system behavior, safety invariants, user experience, and architectural intent.
2. `IMPLEMENTATION_PLAN.md` is authoritative for package structure, implementation sequencing, testing, and the explicitly approved resolutions listed at the beginning of the plan.
3. The approved resolutions in `IMPLEMENTATION_PLAN.md` supersede any conflicting implementation details in `DESIGN.md`, including:
   - The supervisor agent recommends reopening; the reopen-legality guard validates the decision before the workflow changes state. There is no agent-callable `reopen_finding` tool.
   - `Lifecycle` lives in `adr.stores` beside `Findings`.
   - The complete application is built and accepted locally before any Cloud Run deployment.
   - Git commits remain local until GitHub publishing is explicitly approved.
4. `IMPLEMENTATION_RULES.md` governs working practices, scope control, Git usage, deployment authorization, and external actions.
5. The nine-step guided flow in `IMPLEMENTATION_PLAN.md` is authoritative. The PRD and earlier production design are intentionally excluded and are not required for D0 implementation.
6. Do not infer additional requirements from references to the PRD or the production design.
7. If the documents contain another material conflict affecting scope, safety, architecture, or reviewer experience, stop and ask before implementing that part.
8. For minor implementation details not specified by these documents, choose the simplest solution consistent with the D0 invariants and record the assumption.

## Scope

- Implement only the approved D0 scope.
- Do not add a live LLM, real PostgreSQL, authentication, persistent storage, or other production features.
- Prefer the simplest implementation that preserves the specified invariants.
- Do not introduce additional frameworks or runtime dependencies without approval.
- Keep the UI clean, calm, and easy to understand. Reviewer comprehension takes priority over visual decoration.

## Workflow

- Implement in the approved phases.
- Run the relevant tests after every phase.
- The complete test suite and review checklist are the Definition of Done.
- Record any necessary deviation from the plan and explain why.
- Ask only when a decision would materially change scope, safety guarantees, architecture, or reviewer experience.

## Git and external actions

- Initialize Git at the project root if it is not already initialized.
- Respect and maintain `.gitignore`; never commit credentials, keys, or local environment files.
- Make logical local commits at meaningful milestones.
- Do not create a GitHub repository, configure a remote, or push without explicit approval.
- Do not deploy or modify Google Cloud resources without explicit approval.

## Completion

Before declaring implementation complete:

- Run the full test suite and package the JAR.
- Run the application locally and smoke-test the complete guided flow.
- Build and run the Docker image.
- Verify that no external service, account, API key, or data is required.
- Report tests run, results, remaining limitations, and local startup commands.
