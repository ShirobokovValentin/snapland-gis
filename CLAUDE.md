# CLAUDE.md

## Project

This repository contains the implementation of a collaborative GIS application.

The authoritative project documentation is:

- `docs/REQUIREMENTS.md` — functional and non-functional requirements.
- `docs/DESIGN.md` — system architecture and technical design.

Read the relevant sections of these documents before making implementation decisions.

---

## General Principles

- Follow the architecture defined in `docs/DESIGN.md`.
- Do not introduce architectural changes without explicit approval.
- Do not add functionality that is not required by the current task.
- Prefer simple solutions over unnecessary abstractions.
- Keep changes small and focused.
- Do not perform unrelated refactoring.
- Do not add dependencies unless they are necessary.
- Do not introduce workarounds before understanding the root cause of a problem.
- If a requirement or architectural decision is unclear, ask before implementing it.
- If implementation requires deviating from the approved plan, stop and ask for approval.

---

## Development Workflow

For each task:

1. Read the relevant requirements and design sections.
2. Inspect the existing implementation.
3. Produce a short implementation plan.
4. Identify files that will be created or modified.
5. Identify dependencies that will be added or changed.
6. Identify unresolved technical decisions, if any.
7. Wait for approval before implementation when explicitly requested.
8. Implement only the approved scope.
9. Add or update tests for the implemented behavior.
10. Run relevant tests and verification commands.
11. Review the resulting diff for unintended changes.
12. Summarize the changes and verification results.

Do not silently change the approved scope during implementation.

If an unexpected technical problem appears during implementation:

1. Diagnose the root cause.
2. Explain the cause.
3. Identify possible solutions and their trade-offs.
4. Prefer the solution that preserves portability and the existing architecture.
5. Request approval before applying a solution that:
  - changes architecture;
  - changes the approved scope;
  - modifies the developer machine;
  - modifies global configuration;
  - introduces a significant new dependency.

---

## Environment and Portability

The repository must remain portable across developer machines and CI environments.

A developer with the documented prerequisites should be able to clone the repository and run the documented build and test commands without undocumented machine-specific configuration.

### Repository Portability Rules

- Do not hardcode absolute local filesystem paths.
- Do not hardcode usernames or home-directory paths.
- Do not hardcode machine-specific Docker socket paths.
- Do not rely on configuration that exists only on the current developer machine.
- Do not add project configuration whose only purpose is to work around the current developer environment.
- Prefer dependency or project configuration fixes when the root cause is dependency compatibility.
- Keep local development configuration reproducible and documented.
- The same build and test commands should work in CI where practical.

### Machine-Level Changes

Do not modify anything outside the repository without explicit approval.

This includes, but is not limited to:

- `~/.zprofile`
- `~/.zshrc`
- `~/.bashrc`
- `~/.profile`
- `~/.testcontainers.properties`
- `~/.m2/settings.xml`
- global Git configuration
- global Maven configuration
- global npm configuration
- Docker Desktop settings
- operating-system settings
- system environment variables
- user environment variables
- files under the user's home directory
- files under system directories

Do not:

- install global packages;
- install or uninstall system software;
- create symlinks outside the repository;
- modify PATH or shell startup configuration;
- change Docker Desktop configuration;
- change global Java, Maven, Node, npm, Git, or Docker configuration;

unless explicitly approved.

### Local Environment Problems

If a build or test fails because of the local environment:

1. Diagnose the root cause before applying any workaround.
2. Determine whether the problem is caused by:
  - project configuration;
  - dependency compatibility;
  - developer environment;
  - operating-system-specific behavior;
  - external tooling.
3. Check whether the problem can be solved through a portable dependency or project-level fix.
4. Prefer upgrading or correctly configuring a dependency over introducing a machine-specific workaround.
5. If the only reasonable solution requires a machine-level change, stop and report:
  - the root cause;
  - the proposed change;
  - why it is required;
  - its scope;
  - its side effects;
  - alternatives considered.
6. Wait for explicit approval before applying the machine-level change.

Temporary environment variables may be used for diagnosis only when they affect the current command/process and do not persistently modify the machine.

Do not turn a diagnostic workaround into a permanent solution without explicit approval.

---

## Backend

Backend technology:

- Java 21
- Spring Boot
- Maven
- PostgreSQL
- PostGIS
- Flyway

Guidelines:

- Use feature-oriented package organization where practical.
- Keep controllers focused on HTTP concerns.
- Keep business logic out of controllers.
- Use database transactions explicitly where required by the design.
- Database schema changes must be implemented through Flyway migrations.
- Do not modify already-applied migration files; create a new migration instead.
- PostgreSQL + PostGIS is the source of truth for persistent GIS state.
- Validate input at system boundaries.
- Preserve the concurrency and versioning model defined in `docs/DESIGN.md`.
- Avoid unnecessary framework abstractions.
- Prefer explicit and predictable persistence behavior.

---

## Frontend

Frontend technology:

- Angular
- TypeScript
- Leaflet.js

Guidelines:

- Keep map-specific behavior isolated from unrelated UI logic.
- Treat backend state as authoritative for persisted Areas.
- Client-side area calculation may be used for UI preview only.
- Do not introduce third-party collaborative-editing libraries.
- Do not duplicate backend business rules unnecessarily in the frontend.
- Keep API communication isolated from presentation logic.

---

## Database

- PostgreSQL with PostGIS is the primary persistent store.
- PostgreSQL is the source of truth for persistent application state.
- Use SRID 4326 for persisted Area geometry unless the design is explicitly changed.
- Spatial queries and indexes must follow `docs/DESIGN.md`.
- Preserve database constraints defined by the design.
- Do not rely only on application validation for persistent data integrity.
- Schema changes must be made exclusively through Flyway migrations.
- Do not modify an existing migration after it has been applied; create a new migration.
- Do not add speculative indexes.
- Every explicit index should correspond to a known access pattern or constraint.
- Use PostgreSQL/PostGIS integration tests for database-specific behavior rather than replacing PostgreSQL with H2.

---

## Real-Time Communication

- WebSocket is used for real-time communication.
- Persistent state must not depend on WebSocket delivery.
- PostgreSQL remains the source of truth.
- Redis is infrastructure for Pub/Sub and presence, not authoritative Area storage.
- Clients must be able to resynchronize after missed WebSocket events.
- Do not treat Redis Pub/Sub as durable messaging.
- Persistent updates must be committed before they are exposed as authoritative state to other clients.

---

## Dependencies

- Do not add a dependency unless it is required by the current task.
- Prefer dependencies already managed by the Spring Boot or Angular dependency ecosystem.
- Do not override dependency versions without a concrete reason.
- When overriding a BOM-managed dependency version:
  - explain why the managed version is insufficient;
  - verify compatibility;
  - prefer the smallest compatible upgrade;
  - keep related artifacts on compatible versions.
- Do not introduce a library solely to avoid implementing trivial functionality.
- Verify dependency compatibility using authoritative documentation or actual dependency metadata when uncertainty exists.

---

## Code Quality

- Write production-quality code appropriate for a technical home assignment.
- Prefer readability over cleverness.
- Avoid premature abstraction.
- Avoid speculative functionality.
- Use descriptive names.
- All code comments must be in English.
- Comments should explain non-obvious reasoning, not restate the code.
- Remove unused code and imports.
- Do not leave placeholder implementations unless explicitly requested.
- Avoid dead code.
- Avoid unnecessary layers and wrapper classes.
- Keep public APIs as small as practical.
- Prefer explicit behavior over hidden framework magic when the trade-off is reasonable.

---

## Testing

- Add tests for important business behavior.
- Test behavior and invariants rather than implementation details.
- Use real infrastructure through Testcontainers when database-specific behavior matters.
- Do not replace meaningful integration tests with mocks.
- Tests must not depend on persistent state from previous test runs.
- Tests must not depend on the developer's local development database.
- Tests must be reproducible on another developer machine and in CI where practical.

Prioritize tests around:

- geometry validation;
- Area mutations;
- versioning;
- optimistic concurrency control;
- authorization;
- spatial queries;
- database constraints;
- real-time event behavior.

After implementation:

- run the relevant test suite;
- report failing tests;
- do not hide or disable failing tests to make the build pass;
- do not weaken assertions solely to make a failing test pass.

If a test exposes a real defect, fix the defect rather than changing the test expectation unless the expectation is demonstrably incorrect.

---

## README Maintenance

`README.md` is the primary entry point for project reviewers. It must always describe the current repository state — not planned or future state.

For every task, check whether the change affects `README.md`. Update it in the same task when the change affects any of:

- implemented features or their status;
- setup or run instructions;
- configuration properties or environment variables;
- architecture or component interactions;
- API behavior or endpoints;
- authentication or security;
- map providers;
- test count or test class coverage;
- known limitations;
- performance or scaling decisions.

Do not mechanically update README for every code change. Only update sections where the content would be factually incorrect without the change.

Do not describe planned or future functionality as implemented.

---

## Documentation

Update documentation when implementation changes:

- behavior;
- setup;
- architecture;
- API contracts;
- operational requirements;
- developer prerequisites.

Do not duplicate detailed architecture documentation in this file.

Keep:

- product requirements in `docs/REQUIREMENTS.md`;
- architecture and technical design in `docs/DESIGN.md`;
- repository working rules in `CLAUDE.md`.

If implementation reveals that `DESIGN.md` needs to change, do not silently modify the architecture. Report the discrepancy and request approval first.

---

## Git Safety

Git history and staging state are controlled by the developer.

Allowed without explicit approval:

```text
git status
git diff
git diff --cached
git log
git show
```

Do not run commands that modify Git state unless explicitly requested.

This includes:

```text
git add
git commit
git push
git pull
git merge
git rebase
git reset
git restore
git checkout
git switch
git cherry-pick
git revert
git stash
git clean
```

Do not:

- create commits;
- amend commits;
- stage or unstage files;
- push branches;
- create or delete branches;
- rewrite history;
- discard working-tree changes;

unless explicitly requested.

Never use destructive Git commands as a troubleshooting shortcut.

Do not commit:

- generated IDE files;
- build output;
- secrets;
- credentials;
- tokens;
- local environment files;
- machine-specific configuration.

Keep each implementation task logically isolated.

---

## External Side Effects

Do not perform actions outside the repository unless explicitly requested.

Examples include:

- modifying user or system configuration;
- changing cloud resources;
- changing remote repositories;
- creating or modifying GitHub issues or pull requests;
- pushing Docker images;
- publishing packages;
- deploying applications;
- modifying external databases;
- changing external services.

Read-only inspection and diagnostics are allowed when necessary to understand a problem, provided they do not modify external state.

If there is uncertainty about whether an operation has an external side effect, ask before executing it.

---

## Scope Control

The current task defines the implementation scope.

Do not implement future tasks early just because the architecture already describes them.

In particular, do not introduce:

- infrastructure;
- abstractions;
- dependencies;
- APIs;
- background processing;
- caching;
- security mechanisms;
- real-time functionality;

solely because they may be useful later.

When in doubt, implement the smallest solution that satisfies the current task and remains consistent with `docs/DESIGN.md`.

Do not silently expand the task in response to an implementation problem.

---

## Decision Escalation

Stop and ask for approval when:

- requirements conflict;
- implementation conflicts with `docs/DESIGN.md`;
- an architectural decision is missing;
- the approved implementation plan must materially change;
- a new infrastructure component is required;
- a significant dependency must be introduced;
- a BOM-managed dependency must be overridden for compatibility reasons;
- a machine-level or user-level configuration change is required;
- an operation would modify state outside the repository;
- a destructive operation may be required.

When asking for approval, explain:

1. What was discovered.
2. Why the current approach cannot continue unchanged.
3. Available options.
4. Trade-offs.
5. Your recommended option.

Do not apply the proposed change until approval is given.