# Viewport Performance Test Report

## 1. Goal

The goal of this test was to measure the current performance of the Snapland viewport query:

```http
GET /workspaces/{workspaceId}/areas?bbox={minLon,minLat,maxLon,maxLat}
```

The main questions were:

1. How does the current Spring Boot + PostgreSQL/PostGIS implementation behave under concurrent viewport read load?
2. Does the existing PostGIS GiST index work effectively for normal viewport queries?
3. Is geographic caching currently justified by measured performance?

No production optimizations or geographic caching were introduced as part of this test.

---

## 2. Test Architecture

```text
k6
 │
 │ HTTP + JWT
 ▼
Spring Boot
 │
 │ JPA / SQL
 ▼
PostgreSQL + PostGIS
 │
 ▼
GiST spatial index
```

The benchmark uses a dedicated performance workspace:

```text
e0000000-0000-0000-0000-000000000001
```

Synthetic polygons are generated directly in PostgreSQL using:

```text
performance/seed-areas.sql
```

The HTTP workload is generated using:

```text
performance/viewport-load.js
```

---

# 3. Environment Preparation

## 3.1 Start infrastructure

From the repository root:

```bash
docker compose up -d
```

Check the containers:

```bash
docker compose ps
```

---

## 3.2 Start the backend

The backend was started using the development profile:

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Health can be verified with:

```bash
curl http://localhost:8081/actuator/health
```

Expected result:

```json
{
  "status": "UP"
}
```

---

## 3.3 Install / verify k6

```bash
brew install k6
```

Verify:

```bash
k6 version
```

The PostgreSQL client is also required:

```bash
psql --version
```

---

# 4. Authentication Setup

## 4.1 Register performance user

```bash
curl -s -X POST http://localhost:8081/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"name":"perf-user","email":"perf@snapland.internal"}'
```

Registration is only required once.

If the user already exists, the existing account can be used.

---

## 4.2 Obtain JWT

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"perf@snapland.internal"}' | jq -r '.token')
```

Verify that the token was received:

```bash
echo $TOKEN
```

The token is passed to k6 as the `Authorization: Bearer` token.

---

# 5. Dataset — 1,000 Areas

## 5.1 Seed

```bash
PGPASSWORD=snapland psql \
  -h localhost \
  -p 5433 \
  -U snapland \
  -d snapland_gis \
  -v n=1000 \
  -f performance/seed-areas.sql
```

---

## 5.2 Verify number of Areas

```bash
PGPASSWORD=snapland psql \
  -h localhost \
  -p 5433 \
  -U snapland \
  -d snapland_gis \
  -c "SELECT COUNT(*) FROM area WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001';"
```

Expected:

```text
1000
```

---

## 5.3 Verify REST endpoint

```bash
curl -s \
  "http://localhost:8081/workspaces/e0000000-0000-0000-0000-000000000001/areas?bbox=10.0,47.0,15.0,52.0" \
  -H "Authorization: Bearer $TOKEN" \
  | jq 'length'
```

Expected:

```text
1000
```

This verifies the complete path:

```text
JWT
 ↓
Spring Security
 ↓
Area API
 ↓
PostGIS
 ↓
JSON response
```

---

# 6. k6 Smoke Test

Before the full benchmark, a short test was executed with 2 virtual users for 10 seconds.

## Command

```bash
k6 run \
  --vus 2 \
  --duration 10s \
  -e BASE_URL=http://localhost:8081 \
  -e WORKSPACE_ID=e0000000-0000-0000-0000-000000000001 \
  -e TOKEN=$TOKEN \
  performance/viewport-load.js
```

## Output

```text
scenarios: (100.00%) 1 scenario, 2 max VUs, 40s max duration:
  * default: 2 looping VUs for 10s

THRESHOLDS

http_req_failed
✓ 'rate<1.0' rate=0.00%

TOTAL RESULTS

checks_total.......: 40
checks_succeeded...: 100.00% 40 out of 40
checks_failed......: 0.00%   0 out of 40

✓ status is 200
✓ body is JSON array

CUSTOM
viewport_empty_responses.......: 12
viewport_result_count..........:
  avg=196
  min=0
  med=0
  max=1000
  p(90)=504
  p(95)=528.8

HTTP
http_req_duration..............:
  avg=35.25ms
  min=16.58ms
  med=31.64ms
  max=60.4ms
  p(90)=52.46ms
  p(95)=54.05ms

http_req_failed................: 0.00% 0 out of 20
http_reqs......................: 20 / 1.823752 req/s

NETWORK
data_received..................: 1.3 MB
data_sent......................: 7.9 kB
```

## Interpretation

The smoke test confirmed:

- authentication works;
- all requests return HTTP 200;
- response bodies are valid JSON arrays;
- no HTTP failures occurred;
- k6 can successfully exercise the real viewport endpoint.

The smoke test was used for setup validation only and was not treated as the primary benchmark.

---

# 7. Full Benchmark — 1,000 Areas

## Command

```bash
k6 run \
  -e BASE_URL=http://localhost:8081 \
  -e WORKSPACE_ID=e0000000-0000-0000-0000-000000000001 \
  -e TOKEN=$TOKEN \
  performance/viewport-load.js
```

## Load profile

```text
30 s → 10 VUs
60 s → 25 VUs
60 s → 50 VUs
60 s → 100 VUs
30 s → 0 VUs
```

Each virtual user waits approximately one second between viewport requests.

## Output

```text
checks_total.......: 18434
checks_succeeded...: 100.00% 18434 out of 18434
checks_failed......: 0.00%   0 out of 18434

✓ status is 200
✓ body is JSON array

CUSTOM
viewport_empty_responses.......: 4602
viewport_result_count..........:
  avg=290.883368
  min=0
  med=304
  max=1000
  p(90)=1000
  p(95)=1000

HTTP
http_req_duration..............:
  avg=13.26ms
  min=1.42ms
  med=10.91ms
  max=160.86ms
  p(90)=26.98ms
  p(95)=32.9ms

http_req_failed................: 0.00% 0 out of 9217
http_reqs......................: 9217 / 38.328061 req/s

EXECUTION
iterations.....................: 9217
vus_max........................: 100

NETWORK
data_received..................: 918 MB / 3.8 MB/s
data_sent......................: 3.6 MB
```

## Result

| Metric | Result |
|---|---:|
| Dataset | 1,000 Areas |
| Peak VUs | 100 |
| Requests | 9,217 |
| RPS | 38.33 |
| p50 | 10.91 ms |
| p90 | 26.98 ms |
| p95 | 32.90 ms |
| Max | 160.86 ms |
| Error rate | 0% |
| Data received | 918 MB |

No p99 value was emitted by the current k6 summary, so no p99 value is reported.

### Interpretation

At 1,000 Areas the API remained stable up to the configured peak of 100 virtual users.

No requests failed.

The test also exercised relatively large responses: some viewports returned all 1,000 Areas.

---

# 8. PostgreSQL Analysis — 1,000 Areas

A full-region query was inspected using:

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT *
FROM area
WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001'
  AND status = 'ACTIVE'
  AND ST_Intersects(
      geometry,
      ST_MakeEnvelope(10.0, 47.0, 15.0, 52.0, 4326)
  );
```

## Output

```text
Bitmap Heap Scan on area
  (actual time=3.965..5.780 rows=1000 loops=1)

Recheck Cond:
  workspace_id = 'e0000000-0000-0000-0000-000000000001'
  AND status = 'ACTIVE'

Filter:
  st_intersects(geometry, ...)

Heap Blocks: exact=31
Buffers: shared hit=38

-> BitmapAnd

   -> Bitmap Index Scan on idx_area_workspace_id
      (actual time=0.451..0.451 rows=1010 loops=1)

   -> Bitmap Index Scan on idx_area_active_geometry
      (actual time=0.733..0.733 rows=1000 loops=1)

Planning Time: 50.460 ms
Execution Time: 7.439 ms
```

## Interpretation

PostgreSQL used both:

```text
idx_area_workspace_id
```

and:

```text
idx_area_active_geometry
```

The two index results were combined using:

```text
BitmapAnd
```

The actual execution time for returning 1,000 rows was:

```text
7.439 ms
```

All required pages were already in PostgreSQL shared buffers:

```text
Buffers: shared hit=38
```

This confirms that the intended spatial GiST index participates in the query at this dataset size.

---

# 9. Dataset — 10,000 Areas

The performance workspace was reseeded with 10,000 Areas:

```bash
PGPASSWORD=snapland psql \
  -h localhost \
  -p 5433 \
  -U snapland \
  -d snapland_gis \
  -v n=10000 \
  -f performance/seed-areas.sql
```

The same k6 workload was then executed without changing the load profile.

---

# 10. Full Benchmark — 10,000 Areas

## Command

```bash
k6 run \
  -e BASE_URL=http://localhost:8081 \
  -e WORKSPACE_ID=e0000000-0000-0000-0000-000000000001 \
  -e TOKEN=$TOKEN \
  performance/viewport-load.js
```

## Output

```text
checks_total.......: 11270
checks_succeeded...: 100.00% 11270 out of 11270
checks_failed......: 0.00%   0 out of 11270

✓ status is 200
✓ body is JSON array

CUSTOM
viewport_empty_responses.......: 2779
viewport_result_count..........:
  avg=2910.906832
  min=0
  med=3040
  max=10000
  p(90)=10000
  p(95)=10000

HTTP
http_req_duration..............:
  avg=129.58ms
  min=1.53ms
  med=66.46ms
  max=3.2s
  p(90)=238.93ms
  p(95)=533.92ms

http_req_failed................: 0.00% 0 out of 5635
http_reqs......................: 5635 / 23.37657 req/s

EXECUTION
iterations.....................: 5635
vus_max........................: 100

NETWORK
data_received..................: 5.6 GB / 23 MB/s
data_sent......................: 2.2 MB
```

## Result

| Metric | Result |
|---|---:|
| Dataset | 10,000 Areas |
| Peak VUs | 100 |
| Requests | 5,635 |
| RPS | 23.38 |
| p50 | 66.46 ms |
| p90 | 238.93 ms |
| p95 | 533.92 ms |
| Max | 3.2 s |
| Error rate | 0% |
| Data received | 5.6 GB |

Again, no p99 value was emitted by the current summary.

---

# 11. 1k vs 10k Comparison

| Metric | 1,000 Areas | 10,000 Areas |
|---|---:|---:|
| Peak VUs | 100 | 100 |
| Requests | 9,217 | 5,635 |
| RPS | 38.33 | 23.38 |
| p50 | 10.91 ms | 66.46 ms |
| p90 | 26.98 ms | 238.93 ms |
| p95 | 32.90 ms | 533.92 ms |
| Max | 160.86 ms | 3.2 s |
| Errors | 0% | 0% |
| Average result count | 290.9 | 2,910.9 |
| Maximum result count | 1,000 | 10,000 |
| Data received | 918 MB | 5.6 GB |

The end-to-end latency increased substantially with the larger dataset.

However, the number of objects returned also increased by approximately one order of magnitude.

The 10k test returned on average approximately:

```text
2,911 Areas per request
```

and some viewport requests returned:

```text
10,000 Areas
```

Therefore the HTTP benchmark measures more than the spatial lookup itself:

```text
PostGIS query
    ↓
row/entity materialization
    ↓
DTO mapping
    ↓
geometry serialization
    ↓
JSON serialization
    ↓
HTTP transfer
```

The 10k run transferred approximately:

```text
5.6 GB
```

in four minutes.

Therefore the increase in HTTP latency cannot be attributed to PostGIS alone.

---

# 12. PostgreSQL Analysis — 10,000 Areas, Full Region

The full-region query was repeated against the 10,000-Area dataset:

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT *
FROM area
WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001'
  AND status = 'ACTIVE'
  AND ST_Intersects(
      geometry,
      ST_MakeEnvelope(10.0, 47.0, 15.0, 52.0, 4326)
  );
```

## Output

```text
Seq Scan on area
  (actual time=188.557..198.159 rows=10000 loops=1)

Filter:
  workspace_id = 'e0000000-0000-0000-0000-000000000001'
  AND status = 'ACTIVE'
  AND st_intersects(geometry, ...)

Rows Removed by Filter: 11

Buffers: shared hit=325

Planning:
  Buffers: shared hit=233

Planning Time: 48.573 ms

JIT:
  Functions: 2
  Options:
    Inlining false
    Optimization false
    Expressions true
    Deforming true

  Timing:
    Generation:     7.459 ms
    Inlining:       0.000 ms
    Optimization:  20.253 ms
    Emission:      165.914 ms
    Total:         193.626 ms

Execution Time: 363.052 ms
```

## Interpretation

For this query PostgreSQL deliberately selected:

```text
Seq Scan on area
```

instead of the GiST index.

This query covers the entire generated region and returns:

```text
10,000 / 10,000 performance Areas
```

Therefore the spatial predicate has almost no selectivity.

Using an index is not necessarily advantageous when almost every row in the table must be returned.

The test also showed significant JIT overhead:

```text
JIT Total: 193.626 ms
```

out of:

```text
Execution Time: 363.052 ms
```

This full-region query should therefore not be treated as representative of normal map viewport behavior.

---

# 13. PostgreSQL Analysis — 10,000 Areas, Smaller Viewport

A smaller and more representative viewport was tested:

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT *
FROM area
WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001'
  AND status = 'ACTIVE'
  AND ST_Intersects(
      geometry,
      ST_MakeEnvelope(10.0, 47.0, 10.5, 47.5, 4326)
  );
```

## Output

```text
Bitmap Heap Scan on area
  (actual time=4.805..6.134 rows=546 loops=1)

Recheck Cond:
  status = 'ACTIVE'

Filter:
  workspace_id = 'e0000000-0000-0000-0000-000000000001'
  AND st_intersects(geometry, ...)

Heap Blocks: exact=36
Buffers: shared hit=56

-> Bitmap Index Scan on idx_area_active_geometry
   (actual time=3.209..3.210 rows=546 loops=1)

   Index Cond:
     geometry && <viewport geometry>

   Buffers: shared hit=20

Planning Time: 6.656 ms
Execution Time: 9.646 ms
```

## Interpretation

For the smaller viewport PostgreSQL again used:

```text
idx_area_active_geometry
```

The query selected:

```text
546 Areas
```

from the 10,000-Area dataset.

Execution time was:

```text
9.646 ms
```

This is an important result because it separates normal spatial lookup performance from the cost of returning very large result sets.

The measured path is approximately:

```text
10,000 Areas
      ↓
small viewport
      ↓
GiST spatial index
      ↓
546 matching Areas
      ↓
9.646 ms SQL execution
```

The spatial lookup itself therefore did not demonstrate a performance bottleneck at the tested 10,000-Area scale.

---

# 14. Key Findings

## 14.1 Correctness under load

Both full k6 runs completed with:

```text
0% HTTP errors
```

All checks passed:

```text
status is 200
body is JSON array
```

No stability failure was observed up to the configured peak of 100 concurrent virtual users.

---

## 14.2 GiST works for selective viewport queries

The 10,000-Area smaller-viewport query used:

```text
Bitmap Index Scan on idx_area_active_geometry
```

and completed in:

```text
9.646 ms
```

for 546 returned Areas.

The intended spatial indexing strategy is therefore functioning.

---

## 14.3 A very large viewport is a different workload

When the viewport covered essentially the entire dataset, PostgreSQL selected:

```text
Seq Scan
```

This is reasonable because nearly every row had to be returned.

An index cannot avoid the fundamental cost of returning almost the entire dataset.

---

## 14.4 Large result sets dominate the end-to-end workload

The 10,000-Area k6 test returned on average approximately:

```text
2,911 Areas/request
```

with some responses containing all:

```text
10,000 Areas
```

The benchmark transferred:

```text
5.6 GB
```

over approximately four minutes.

The observed `p95 = 533.92 ms` must therefore be interpreted as end-to-end API performance for large responses, not as PostGIS spatial-index latency.

---

# 15. Geographic Caching Decision

Based on the measurements performed in this test, geographic viewport caching is **not justified at this stage**.

The primary evidence is the representative 10,000-Area spatial query:

```text
Rows returned:   546
Execution Time:  9.646 ms
Index:           idx_area_active_geometry
```

No clear database spatial-query bottleneck was demonstrated for a normal selective viewport.

The largest latency increase appeared when viewport requests returned thousands or all 10,000 Areas.

Caching such responses would not eliminate other costs such as:

```text
large result materialization
JSON serialization
geometry serialization
network transfer
browser-side processing
```

Therefore adding geographic cache now would introduce additional complexity without addressing a demonstrated bottleneck.

---

# 16. Potential Future Optimizations

If large viewport responses become a real product problem, the first options to investigate should be related to controlling result-set size rather than immediately introducing cache.

Possible future approaches include:

```text
zoom-dependent querying
viewport/result-size limits
geometry simplification
clustering
generalization
```

These were not implemented or benchmarked as part of this task.

Caching can be reconsidered later if measurements demonstrate repeated expensive spatial queries where caching would materially reduce the bottleneck.

---

# 17. Final Result

Task 20 performance verification demonstrated:

- the application remained functional under the tested concurrent read load;
- no HTTP errors occurred in either the 1k or 10k benchmark;
- PostGIS uses the GiST spatial index for selective viewport queries;
- a representative viewport over 10,000 Areas executed in approximately 9.6 ms at the database level;
- very large viewport responses cause substantial end-to-end latency and network traffic;
- the observed large-response cost should not be attributed solely to PostGIS;
- no measured evidence currently justifies introducing geographic viewport caching.

The decision remains:

> Geographic caching is deferred. The current PostGIS/GiST implementation is sufficient for the tested prototype workload. If performance becomes a problem, result-set size and viewport behavior should be investigated before adding cache.

The planned 50,000-Area stress test was intentionally not performed because the 1,000- and 10,000-Area measurements, together with the selective and full-region PostgreSQL query plans, were sufficient for the current prototype evaluation.