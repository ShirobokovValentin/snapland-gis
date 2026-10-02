# CLAUDE.md

## Project

This repository contains the implementation of a collaborative GIS application.

The authoritative project documentation is:

- `docs/REQUIREMENTS.md` — functional and non-functional requirements.
- `docs/DESIGN.md` — system architecture and technical design.

Read the relevant sections of these documents before making implementation decisions.

## General Principles

- Follow the architecture defined in `docs/DESIGN.md`.
- Do not introduce architectural changes without explicit approval.
- Do not add functionality that is not required by the current task.
- Prefer simple solutions over unnecessary abstractions.
- Keep changes small and focused.
- Do not perform unrelated refactoring.
- Do not add dependencies unless they are necessary.
- If a requirement or architectural decision is unclear, ask before implementing it.

## Development Workflow

For each task:

1. Read the relevant requirements and design sections.
2. Inspect the existing implementation.
3. Produce a short implementation plan.
4. Identify files that will be created or modified.
5. Identify unresolved technical decisions, if any.
6. Wait for approval before implementation when explicitly requested.
7. Implement only the approved scope.
8. Add or update tests for the implemented behavior.
9. Run relevant tests and verification commands.
10. Summarize the changes and verification results.

Do not silently change the approved scope during implementation.

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

## Database

- PostgreSQL with PostGIS is the primary persistent store.
- Use SRID 4326 for persisted Area geometry unless the design is explicitly changed.
- Spatial queries and indexes must follow `docs/DESIGN.md`.
- Preserve database constraints defined by the design.
- Do not rely only on application validation for persistent data integrity.

## Real-Time Communication

- WebSocket is used for real-time communication.
- Persistent state must not depend on WebSocket delivery.
- PostgreSQL remains the source of truth.
- Redis is infrastructure for Pub/Sub and presence, not authoritative Area storage.
- Clients must be able to resynchronize after missed WebSocket events.

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

## Testing

- Add tests for important business behavior.
- Prioritize tests around:
    - geometry validation;
    - Area mutations;
    - versioning;
    - optimistic concurrency control;
    - authorization;
    - spatial queries;
    - real-time event behavior.
- Do not replace meaningful tests with excessive mocking.
- Run the relevant test suite after implementation.

## Documentation

Update documentation when implementation changes behavior, setup, architecture, API contracts, or operational requirements.

Do not duplicate detailed architecture documentation in this file. Keep architectural decisions in `docs/DESIGN.md`.

## Git

- Do not commit generated IDE files.
- Do not commit secrets, credentials, tokens, or local environment files.
- Do not create commits unless explicitly requested.
- Keep each implementation task logically isolated.

## Scope Control

The current task defines the implementation scope.

Do not implement future tasks early just because the architecture already describes them.

In particular, do not introduce infrastructure, abstractions, or functionality solely because they may be useful later.

When in doubt, implement the smallest solution that satisfies the current task and remains consistent with `docs/DESIGN.md`.