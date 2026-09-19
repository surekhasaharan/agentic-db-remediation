# Implementation rules

## Source of truth

1. `DESIGN.md` is the authoritative product and technical specification.
2. `IMPLEMENTATION_PLAN.md` is the approved execution plan.
3. If they conflict, `DESIGN.md` takes precedence.
4. The PRD and earlier production design are intentionally excluded and are not required.

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
