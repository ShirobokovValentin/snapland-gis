# Snapland GIS

A collaborative GIS application where multiple users can draw and analyse areas on a map in real-time.

## Prerequisites

- Docker and Docker Compose
- Java 21
- Maven (or use the included `./mvnw` wrapper)
- Node.js ≥ 20.19.0 (v26+ recommended) and npm

## Architecture Overview

See `docs/DESIGN.md` for the full system design.

| Component | Technology |
|-----------|-----------|
| Backend | Java 21, Spring Boot 3.5.3, Maven |
| Frontend | Angular 22, TypeScript, Leaflet.js |
| Database | PostgreSQL 16 + PostGIS 3.4 |
| Messaging | Redis 7 |

## 1. Start Infrastructure

```bash
docker compose up -d
```

Wait for both services to be healthy:

```bash
docker compose ps
```

The `postgres` and `redis` containers must show `(healthy)` before starting the backend.

## 2. Start the Backend

```bash
cd backend
./mvnw spring-boot:run
```

The backend starts on `http://localhost:8080`.

**Health check:**

```bash
curl http://localhost:8080/actuator/health
```

Expected response:

```json
{"status":"UP","components":{"db":{"status":"UP",...},"diskSpace":{"status":"UP",...}}}
```

**Build and run all tests** (requires infrastructure to be running):

```bash
cd backend
./mvnw clean verify
```

## 3. Start the Frontend

```bash
cd frontend
npm install      # only needed on first run or after dependency changes
npm start        # starts the dev server at http://localhost:4200
```

**Production build:**

```bash
cd frontend
npm run build
```

Output is written to `frontend/dist/frontend/`.

## Database

Flyway manages the database schema. Migrations are in `backend/src/main/resources/db/migration/`.

| Migration | Description |
|-----------|-------------|
| V1 | Enable PostGIS extension |

Connection details for local development:

| Property | Value |
|----------|-------|
| Host | `localhost:5433` |
| Database | `snapland_gis` |
| Username | `snapland` |
| Password | `snapland` |

## Technical Decisions

- **PostgreSQL + PostGIS** as the authoritative spatial store. SRID 4326 (WGS 84) for all geometry.
- **Flyway** for database migrations. Schema changes are version-controlled and applied automatically on startup.
- **Redis** for Pub/Sub event distribution and presence state (not a persistent store).
- **Spring Boot Actuator** exposes `/actuator/health` for liveness/readiness monitoring.
- **Optimistic Concurrency Control** on Area mutations via `current_version` (see `docs/DESIGN.md`).
