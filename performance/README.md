# Performance Tests — Viewport Query

This directory contains tooling to measure the performance of the spatial viewport
query (`GET /workspaces/{wid}/areas?bbox=…`) under concurrent read load.

**Scope:** read performance only — the WebSocket, mutation, and auth paths are
out of scope for this test.

---

## Contents

| File | Purpose |
|------|---------|
| `seed-areas.sql` | Populates a dedicated performance workspace with synthetic polygons |
| `viewport-load.js` | k6 load-test script for the viewport endpoint |
| `README.md` | This file |

---

## Prerequisites

| Dependency | Minimum version | Install |
|-----------|----------------|---------|
| PostgreSQL + PostGIS | as in `docker-compose.yml` | `docker compose up -d postgres` |
| Java 21 | 21 | — |
| Maven | 3.9 | — |
| k6 | 0.50 | `brew install k6` (macOS) |
| psql client | 14 | bundled with PostgreSQL |

The backend must be running and reachable before you run k6.

---

## Workflow

### Step 1 — Start infrastructure and the backend

```bash
# from repo root
docker compose up -d
cd backend
mvn spring-boot:run
```

### Step 2 — Register a performance test user (once)

```bash
curl -s -X POST http://localhost:8081/auth/register \
     -H 'Content-Type: application/json' \
     -d '{"name":"perf-user","email":"perf@snapland.internal"}'
```

If the user already exists (from a previous seed run), skip this step.
The seed SQL uses `ON CONFLICT … DO NOTHING` so the user record is preserved.

### Step 3 — Obtain a JWT token

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"email":"perf@snapland.internal"}' | jq -r '.token')
echo $TOKEN
```

### Step 4 — Seed the performance workspace

```bash
# Default: 1 000 areas
psql -h localhost -p 5433 -U snapland -d snapland_gis \
     -f performance/seed-areas.sql

# Or specify a dataset size:
psql -h localhost -p 5433 -U snapland -d snapland_gis \
     -v n=10000 -f performance/seed-areas.sql

psql -h localhost -p 5433 -U snapland -d snapland_gis \
     -v n=50000 -f performance/seed-areas.sql
```

The script is **idempotent**: it removes any existing data for the performance
workspace before reinserting, so you can switch dataset sizes by re-running.

#### Verify the seed

```sql
SELECT COUNT(*) FROM area
WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001';

SELECT COUNT(*) FROM area_version
WHERE area_id IN (
    SELECT id FROM area
    WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001'
);
```

Both counts should equal the requested `n`.

### Step 5 — Verify the endpoint returns data

```bash
curl -s "http://localhost:8081/workspaces/e0000000-0000-0000-0000-000000000001/areas?bbox=10.0,47.0,15.0,52.0" \
     -H "Authorization: Bearer $TOKEN" | jq 'length'
```

This should return a number equal to the seeded count (for the full-region viewport).

### Step 6 — Smoke run (verify k6 setup before a full load test)

```bash
k6 run --vus 2 --duration 10s \
   -e BASE_URL=http://localhost:8081 \
   -e WORKSPACE_ID=e0000000-0000-0000-0000-000000000001 \
   -e TOKEN=$TOKEN \
   performance/viewport-load.js
```

All checks should pass (`✓ status is 200`, `✓ body is JSON array`).

### Step 7 — Full load test

```bash
k6 run \
   -e BASE_URL=http://localhost:8081 \
   -e WORKSPACE_ID=e0000000-0000-0000-0000-000000000001 \
   -e TOKEN=$TOKEN \
   performance/viewport-load.js
```

The built-in load profile ramps from 10 to 100 virtual users over ~4 minutes.

---

## Load Profile

| Stage | Duration | Target VUs |
|-------|----------|-----------|
| Ramp-up | 30 s | 10 |
| Hold | 60 s | 25 |
| Ramp | 60 s | 50 |
| Peak | 60 s | 100 |
| Ramp-down | 30 s | 0 |

Each VU picks a random viewport from 8 pre-defined bounding boxes covering the
seeded region at different zoom levels, waits ~1 s between requests, and records
the number of areas returned.

---

## Understanding the Results

k6 prints a summary after the run. Key metrics:

| Metric | What it tells you |
|--------|------------------|
| `http_req_duration` | End-to-end request latency (p50, p90, p95, p99) |
| `http_reqs` | Total requests and requests/second |
| `http_req_failed` | Error rate (non-2xx or network errors) |
| `viewport_result_count` | Distribution of result-set sizes across viewports |
| `viewport_empty_responses` | How often a viewport returned 0 areas |

The `http_req_duration` percentiles are the primary indicator.  Compare runs at
different dataset sizes (1k / 10k / 50k) to observe how the GiST index scales.

---

## EXPLAIN ANALYZE

To inspect the query plan the database uses, connect to PostgreSQL and run:

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT * FROM area
WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001'
  AND status = 'ACTIVE'
  AND ST_Intersects(
        geometry,
        ST_MakeEnvelope(10.0, 47.0, 15.0, 52.0, 4326)
      );
```

Look for:
- `Index Scan using idx_area_active_geometry` — confirms the partial GiST index
  is used for the spatial filter.
- `Rows Removed by Filter` / `Rows Removed by Index Recheck` — indicates how
  many candidates the GiST index returns that fail the exact predicate.
- `Buffers: shared hit` vs `shared read` — `hit` means the page was already in
  the shared-buffer cache; `read` means a disk fetch.

Run `EXPLAIN ANALYZE` a second time after the first run to see the warm-cache
behaviour (most `shared hit`, fewer `shared read`).

---

## Warm-up and Repeatability

- The first k6 run after seeding may be slower because PostgreSQL's shared-buffer
  cache is cold.  Run the smoke step (Step 6) before recording numbers to warm
  the cache.
- Dataset seeding is deterministic: the same `n` always produces the same grid
  of polygons with the same spatial distribution.
- To get comparable numbers across machines, record the hardware context in the
  Results table below before comparing.

---

## Results Matrix

Fill in this table after each measurement run. Do not commit invented numbers.

| Dataset | VUs (peak) | p50 ms | p90 ms | p95 ms | p99 ms | RPS | Error % | Notes |
|---------|-----------|--------|--------|--------|--------|-----|---------|-------|
| 1 000 areas | 100 | | | | | | | |
| 10 000 areas | 100 | | | | | | | |
| 50 000 areas | 100 | | | | | | | |

### Hardware context

| Field | Value |
|-------|-------|
| CPU | |
| RAM | |
| Storage | |
| PostgreSQL shared_buffers | |
| Docker resource limits (if any) | |
| OS | |
| JVM heap (-Xmx) | |

---

## Connection to the Caching Decision

The system design documents a decision to defer geographic viewport caching
(see `docs/DESIGN.md` — "Geographic Data Caching Decision").

The purpose of this test is to gather the baseline latency data that would
inform any future caching decision.  Before adding caching:

1. Measure the viewport query at each dataset size using this script.
2. Record the results in the matrix above.
3. Identify whether p95/p99 latency actually presents a user-facing problem.

Do not add caching based on theoretical concern alone.  Use the measured numbers
to make that decision.

---

## Cleanup

To remove all seeded data:

```sql
DELETE FROM area_version
WHERE area_id IN (
    SELECT id FROM area
    WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001'
);
DELETE FROM area         WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001';
DELETE FROM workspace_member WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001';
DELETE FROM workspace    WHERE id = 'e0000000-0000-0000-0000-000000000001';
DELETE FROM users        WHERE id = 'e0000000-0000-0000-0000-000000000002';
```
