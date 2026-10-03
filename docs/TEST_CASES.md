# Test Cases — Snapland GIS

## 1. Scope and Purpose

This document defines black-box acceptance and regression test cases for the
Snapland GIS application. Test cases describe externally observable system
behaviour and are intended for implementation by a separate QA Automation
project using API-level and browser-level tools.

Test cases do not reference implementation details such as database internals,
framework classes, or persistence layers. The system is treated as a black box
observed through its HTTP API and browser UI.

**Functionality covered (current prototype):**
- Area creation via REST API
- Viewport-based Area retrieval via REST API
- Area update via REST API with optimistic concurrency control
- Area deletion via REST API with optimistic concurrency control
- Area restore from historical version via REST API
- Area version history retrieval via REST API
- Real-time Area updates via WebSocket (STOMP over `/ws`, topic `/topic/workspace/{id}`)
- Map rendering and polygon drawing in the browser
- Area editing in the browser (rename and reshape)
- Area deletion and immediate restore in the browser
- Area history panel in the browser with version preview and restore
- Area persistence and reload
- Real-time cross-browser synchronization of Area mutations

**Explicitly out of scope (not yet implemented):**
- Authentication and authorization
- Multi-instance scaling with Redis Pub/Sub
- Presence and cursors
- Satellite map layer

---

## 2. Test Levels

**API Black-Box Tests** exercise the REST API directly via HTTP. They verify
status codes, response body structure, error codes, and data contracts. They
do not depend on a browser.

**UI End-to-End Tests** exercise the application through a browser. They
verify user-visible workflows from opening the application to saving and
viewing Areas.

---

## 3. Prototype Prerequisites and Baseline

### Running the System

| Component | Command | Address |
|-----------|---------|---------|
| Backend | `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` (in `backend/`) | `http://localhost:8081` |
| Frontend | `npm start` (in `frontend/`) | `http://localhost:4201` |

### Development Bootstrap Data

The dev profile seeds the following fixed identifiers on startup:

| Entity | ID |
|--------|----|
| Dev User | `00000000-0000-0000-0000-000000000001` |
| Dev Workspace | `00000000-0000-0000-0000-000000000002` |

All API test cases use the Dev Workspace ID unless stated otherwise.

### Authentication

Authentication is **not implemented** in the current prototype. No
`Authorization` header is required. All requests are processed as the seeded
Dev User. This will change when authentication is introduced; at that point
every test case will need to supply a valid token.

### Reference Polygon

A valid, closed polygon used as a baseline input in multiple test cases
(a roughly 30 km² square, SRID 4326 / WGS 84):

```json
{
  "type": "Polygon",
  "coordinates": [[
    [35.50, 32.70],
    [35.55, 32.70],
    [35.55, 32.75],
    [35.50, 32.75],
    [35.50, 32.70]
  ]]
}
```

### Alternate Reference Polygon

A second valid, closed polygon used in update test cases to supply a
geometrically distinct replacement geometry (a roughly 30 km² square
displaced north-east of the Reference Polygon):

```json
{
  "type": "Polygon",
  "coordinates": [[
    [35.60, 32.80],
    [35.65, 32.80],
    [35.65, 32.85],
    [35.60, 32.85],
    [35.60, 32.80]
  ]]
}
```

### API Base URL

API tests target the backend directly at `http://localhost:8081`. The
frontend proxy (`/api` prefix) is not used in API tests.

---

## 4. API Black-Box Tests

### 4.1 Area Creation

---

### TC-AREA-001 — Successful polygon creation

**Type:** API
**Priority:** Critical

**Preconditions:**
- Backend is running.
- Dev Workspace exists.

**Steps:**
1. Send a `POST` request with a valid name and the Reference Polygon geometry.

**Request:**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas`
- Body:
```json
{
  "name": "Test Area",
  "geometry": {
    "type": "Polygon",
    "coordinates": [[
      [35.50, 32.70], [35.55, 32.70], [35.55, 32.75],
      [35.50, 32.75], [35.50, 32.70]
    ]]
  }
}
```

**Expected Result:**
- HTTP `201 Created`.
- Response body contains all of the following fields:

| Field | Expected value |
|-------|---------------|
| `id` | Non-null UUID string |
| `workspaceId` | `"00000000-0000-0000-0000-000000000002"` |
| `name` | `"Test Area"` (matches input) |
| `geometry.type` | `"Polygon"` |
| `geometry.coordinates` | Non-empty array |
| `areaKm2` | Positive decimal number |
| `status` | `"ACTIVE"` |
| `currentVersion` | `1` |
| `createdAt` | Valid ISO-8601 timestamp string |

---

### TC-AREA-002 — Server computes areaKm2 from geometry

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace exists.

**Steps:**
1. Send `POST` with the Reference Polygon.
2. Record the `areaKm2` value from the response.
3. Send a second `POST` with a polygon of noticeably different geographic
   extent and compare the returned `areaKm2` values.

**Request:**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas`
- Note: The request body does not include an `areaKm2` field; the client
  cannot supply this value.

**Expected Response:**
- `areaKm2` is a positive decimal.
- `areaKm2` is within acceptable tolerance of the expected geodesic area for
  the submitted polygon. Exact tolerance to be defined when implementing the
  automated test; ±0.01 km² is a reasonable starting point for a ~30 km²
  polygon.
- A polygon of larger geographic extent returns a proportionally larger
  `areaKm2`.

---

### TC-AREA-003 — Created Area is retrievable via viewport query

**Type:** API
**Priority:** Critical

**Preconditions:**
- Backend is running.
- Dev Workspace exists.
- No Areas exist in the workspace.

**Steps:**
1. Send `POST` to create an Area using the Reference Polygon. Note the `id`
   from the response.
2. Send `GET` with a bbox that fully encloses the Reference Polygon.

**Request (step 2):**
- Method: `GET`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas?bbox=35.49,32.69,35.56,32.76`

**Expected Response:**
- `POST` returns `201`.
- `GET` returns `200 OK` with an array containing exactly one entry.
- The entry's `id` matches the `id` from the `POST` response.
- The entry's `name` matches the submitted name.

---

### TC-AREA-004 — Invalid name returns 400

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace exists.

**Steps:**

Send the following inputs as separate requests (automation: parameterise as
test data):

| Variant | `name` value |
|---------|-------------|
| Blank | `""` |
| Whitespace only | `"   "` |

**Request:**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas`
- Geometry: Reference Polygon

**Expected Response (both variants):**
- HTTP `400 Bad Request`.
- Response body `code` = `"INVALID_REQUEST"`.
- No Area is created.

---

### TC-AREA-005 — Nonexistent workspace returns 404

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- The target workspace ID does not exist.

**Steps:**
1. Send `POST` to a workspace ID that has not been seeded (e.g.,
   `00000000-0000-0000-0000-000000000099`).

**Request:**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000099/areas`
- Body: valid name + Reference Polygon

**Expected Response:**
- HTTP `404 Not Found`.
- Response body `code` = `"WORKSPACE_NOT_FOUND"`.

---

### TC-AREA-006 — Polygon with interior rings returns 400

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace exists.

**Steps:**
1. Send `POST` with a geometry whose `coordinates` array contains two rings
   (outer boundary + hole).

**Request:**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas`
- Body:
```json
{
  "name": "Holey",
  "geometry": {
    "type": "Polygon",
    "coordinates": [
      [[35.50,32.70],[35.60,32.70],[35.60,32.80],[35.50,32.80],[35.50,32.70]],
      [[35.52,32.72],[35.55,32.72],[35.55,32.75],[35.52,32.75],[35.52,32.72]]
    ]
  }
}
```

**Expected Response:**
- HTTP `400 Bad Request`.
- Response body `code` = `"INVALID_GEOMETRY"`.
- No Area is created.

---

### TC-AREA-007 — Ring with fewer than 4 coordinate positions returns 400

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace exists.

**Steps:**
1. Send `POST` with a polygon ring containing only 3 positions.

**Request:**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas`
- Body:
```json
{
  "name": "Too Short",
  "geometry": {
    "type": "Polygon",
    "coordinates": [[[35.50,32.70],[35.55,32.70],[35.55,32.75]]]
  }
}
```

**Expected Response:**
- HTTP `400 Bad Request`.
- Response body `code` = `"INVALID_GEOMETRY"`.
- No Area is created.

---

### TC-AREA-008 — Unclosed ring returns 400

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace exists.

**Steps:**
1. Send `POST` with a polygon ring where the first and last coordinate
   positions are not equal (the ring is not explicitly closed).

**Request:**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas`
- Body:
```json
{
  "name": "Open Ring",
  "geometry": {
    "type": "Polygon",
    "coordinates": [[[35.50,32.70],[35.55,32.70],[35.55,32.75],[35.50,32.75]]]
  }
}
```
The ring has 4 positions. The first `[35.50, 32.70]` and last `[35.50, 32.75]`
differ — the ring is not closed.

**Expected Response:**
- HTTP `400 Bad Request`.
- Response body `code` = `"INVALID_GEOMETRY"`.
- No Area is created.

---

### TC-AREA-009 — Coordinate outside WGS84 range returns 400

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace exists.

**Steps:**

Send the following inputs as separate requests (automation: parameterise as
test data):

| Variant | Offending coordinate |
|---------|---------------------|
| Longitude too large | `[200.0, 32.70]` |
| Latitude too large | `[35.50, 95.0]` |

**Request:**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas`
- Geometry: Reference Polygon with one coordinate replaced by the offending
  value above.

**Expected Response (both variants):**
- HTTP `400 Bad Request`.
- Response body `code` = `"INVALID_GEOMETRY"`.
- No Area is created.

---

### TC-AREA-010 — Self-intersecting polygon returns 400

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace exists.

**Steps:**
1. Send `POST` with a closed polygon that self-intersects (bowtie shape).

**Request:**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas`
- Body:
```json
{
  "name": "Bowtie",
  "geometry": {
    "type": "Polygon",
    "coordinates": [[[0.0,0.0],[2.0,2.0],[2.0,0.0],[0.0,2.0],[0.0,0.0]]]
  }
}
```

**Expected Response:**
- HTTP `400 Bad Request`.
- Response body `code` = `"INVALID_GEOMETRY"`.
- No Area is created.

---

### TC-AREA-011 — Non-Polygon geometry type returns 400

**Type:** API
**Priority:** Medium

**Preconditions:**
- Backend is running.
- Dev Workspace exists.

**Steps:**
1. Send `POST` with `geometry.type` set to `"LineString"`.

**Request:**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas`
- Body:
```json
{
  "name": "Wrong Type",
  "geometry": {
    "type": "LineString",
    "coordinates": [[35.50,32.70],[35.55,32.75]]
  }
}
```

**Expected Response:**
- HTTP `400 Bad Request`.
- Response body `code` = `"INVALID_GEOMETRY"`.
- No Area is created.

---

### TC-AREA-012 — Error response body contains required fields

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.

**Steps:**
1. Send any request that produces an error (e.g., `POST` to a nonexistent
   workspace as in TC-AREA-005).

**Expected Response:**
- The response body is a JSON object containing all of:
  - `code` — non-null string (machine-readable error identifier)
  - `message` — non-null string (human-readable description)
  - `traceId` — non-null string (correlation identifier, format: UUID)

---

### 4.2 Viewport Query

---

### TC-VP-001 — Area inside viewport is returned

**Type:** API
**Priority:** Critical

**Preconditions:**
- Backend is running.
- Dev Workspace exists.
- No Areas exist in the workspace.

**Steps:**
1. Create an Area using the Reference Polygon via `POST`. Note the `id`.
2. Send `GET` with a bbox that fully encloses the Reference Polygon.

**Request (step 2):**
- Method: `GET`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas?bbox=35.49,32.69,35.56,32.76`

**Expected Response:**
- HTTP `200 OK`.
- Response array contains at least one entry whose `id` matches the created
  Area.

---

### TC-VP-002 — Area outside viewport is not returned

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace exists.
- No Areas exist in the workspace.

**Steps:**
1. Create an Area using the Reference Polygon (located near 35.5°E, 32.7°N).
2. Send `GET` with a bbox far from the Reference Polygon.

**Request (step 2):**
- Method: `GET`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas?bbox=10.0,10.0,11.0,11.0`

**Expected Response:**
- HTTP `200 OK`.
- Response body is an empty array `[]`.

---

### TC-VP-003 — Area that partially intersects the viewport boundary is returned

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace exists.
- No Areas exist in the workspace.

**Steps:**
1. Create an Area using the Reference Polygon
   (bounds: 35.50–35.55°E, 32.70–32.75°N).
2. Send `GET` with a bbox that overlaps only the eastern portion of the
   polygon.

**Request (step 2):**
- Method: `GET`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas?bbox=35.53,32.73,35.60,32.80`

**Expected Response:**
- HTTP `200 OK`.
- Response array contains the created Area.

---

### TC-VP-004 — Area belonging to a different workspace is not returned

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace (`00000000-0000-0000-0000-000000000002`) exists with at
  least one Area.
- A second workspace with a distinct ID is available (or a nonexistent ID is
  used — see note below).

**Steps:**
1. Create an Area in the Dev Workspace via `POST`.
2. Send `GET` for a different workspace ID using a bbox that would include
   the created Area if it belonged to that workspace.

**Request (step 2):**
- Method: `GET`
- Path: `/workspaces/{otherWorkspaceId}/areas?bbox=35.49,32.69,35.56,32.76`

**Expected Response:**
- The Area created in the Dev Workspace is **not** present in the response.
- If `{otherWorkspaceId}` does not exist: HTTP `404 WORKSPACE_NOT_FOUND`.
- If `{otherWorkspaceId}` exists and has no Areas: HTTP `200 OK` with `[]`.

**Note:** The dev profile seeds exactly one workspace. Full workspace
isolation testing (two real workspaces, both with Areas) requires a second
workspace to be created manually or via a future workspace creation API.

---

### TC-VP-005 — Empty viewport returns 200 with empty array

**Type:** API
**Priority:** Medium

**Preconditions:**
- Backend is running.
- Dev Workspace exists.
- No Areas exist in the workspace.

**Steps:**
1. Send `GET` with a valid bbox.

**Request:**
- Method: `GET`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas?bbox=35.49,32.69,35.56,32.76`

**Expected Response:**
- HTTP `200 OK`.
- Response body is a JSON array `[]` (not `null`, not an error).

---

### TC-VP-006 — Malformed or out-of-range bbox returns 400

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace exists.

**Steps:**

Send the following inputs as separate requests (automation: parameterise as
test data):

| Variant | `bbox` value | Reason |
|---------|-------------|--------|
| Wrong count | `35.49,32.69,35.56` | Only 3 values |
| Non-numeric | `a,b,c,d` | Non-numeric tokens |
| Out of WGS84 | `200.0,-91.0,201.0,-90.0` | Longitude and latitude out of range |

**Request:**
- Method: `GET`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas?bbox={value}`

**Expected Response (all variants):**
- HTTP `400 Bad Request`.
- Response body `code` = `"INVALID_REQUEST"`.

---

### TC-VP-007 — Nonexistent workspace returns 404

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- The target workspace ID does not exist.

**Steps:**
1. Send `GET` using a workspace ID that has not been seeded.

**Request:**
- Method: `GET`
- Path: `/workspaces/00000000-0000-0000-0000-000000000099/areas?bbox=35.49,32.69,35.56,32.76`

**Expected Response:**
- HTTP `404 Not Found`.
- Response body `code` = `"WORKSPACE_NOT_FOUND"`.

---

### 4.3 Area Update

---

### TC-UPDATE-001 — Successful update returns 200 with updated fields

**Type:** API
**Priority:** Critical

**Preconditions:**
- Backend is running.
- Dev Workspace exists.
- An Active Area at version 1 exists. Note its `id`.

**Steps:**
1. Send a `PUT` request with `expectedVersion: 1`, a new name, and the
   Alternate Reference Polygon.

**Request:**
- Method: `PUT`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas/{id}`
- Body:
```json
{
  "expectedVersion": 1,
  "name": "Updated Name",
  "geometry": {
    "type": "Polygon",
    "coordinates": [[
      [35.60, 32.80], [35.65, 32.80], [35.65, 32.85],
      [35.60, 32.85], [35.60, 32.80]
    ]]
  }
}
```

**Expected Result:**
- HTTP `200 OK`.
- Response body contains all of the following:

| Field | Expected value |
|-------|---------------|
| `id` | Same UUID as the created Area |
| `workspaceId` | `"00000000-0000-0000-0000-000000000002"` |
| `name` | `"Updated Name"` |
| `geometry.type` | `"Polygon"` |
| `areaKm2` | Positive decimal reflecting the new geometry |
| `status` | `"ACTIVE"` |
| `currentVersion` | `2` |

---

### TC-UPDATE-002 — Version increments on each successive update

**Type:** API
**Priority:** High

**Preconditions:**
- Active Area at version 1 exists.

**Steps:**
1. `PUT` with `expectedVersion: 1`. Confirm `currentVersion: 2` in response.
2. `PUT` with `expectedVersion: 2`. Confirm `currentVersion: 3` in response.

**Expected Result:**
- Each successful update increments `currentVersion` by exactly 1.
- Both responses return HTTP `200 OK`.

---

### TC-UPDATE-003 — AreaVersion snapshot is persisted with all updated fields

**Type:** API
**Priority:** High

**Preconditions:**
- Active Area at version 1 exists, created with the Reference Polygon.

**Steps:**
1. `PUT` with `expectedVersion: 1`, name `"Snapshot Test"`, and the
   Alternate Reference Polygon.
2. Verify the persisted area_version record via
   `GET /workspaces/.../areas?bbox=...` (black-box) or direct database
   inspection in automated tests.

**Expected Result:**
- A new `area_version` row exists with all of:

| Field | Expected value |
|-------|---------------|
| `version` | `2` |
| `change_type` | `UPDATED` |
| `name` | `"Snapshot Test"` |
| `geometry` | Matches the Alternate Reference Polygon submitted in the `PUT` |
| `area_km2` | Positive decimal matching the recalculated area for the new geometry |

---

### TC-UPDATE-004 — Stale expectedVersion returns 409 VERSION_CONFLICT with structured details

**Type:** API
**Priority:** Critical

**Preconditions:**
- Active Area at version 2 (one update already applied).

**Steps:**
1. `PUT` with `expectedVersion: 1` (stale).

**Request:**
- Method: `PUT`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas/{id}`
- Body: `{"expectedVersion": 1, "name": "Conflict", "geometry": <Reference Polygon>}`

**Expected Result:**
- HTTP `409 Conflict`.
- Response body `code` = `"VERSION_CONFLICT"`.
- Response body `details` object contains:

| Field | Expected value |
|-------|---------------|
| `areaId` | UUID of the Area |
| `expectedVersion` | `1` |
| `currentVersion` | `2` |

- No new `area_version` row is created.

---

### TC-UPDATE-005 — Unknown areaId returns 404 AREA_NOT_FOUND

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace exists.

**Steps:**
1. `PUT` to a random Area ID that does not exist.

**Request:**
- Method: `PUT`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas/{randomUuid}`
- Body: `{"expectedVersion": 1, "name": "X", "geometry": <Reference Polygon>}`

**Expected Result:**
- HTTP `404 Not Found`.
- Response body `code` = `"AREA_NOT_FOUND"`.

---

### TC-UPDATE-006 — Area belonging to a different workspace returns 404 AREA_NOT_FOUND

**Type:** API
**Priority:** Critical

**Preconditions:**
- Area X exists in Workspace A (`00000000-0000-0000-0000-000000000002`).
- Workspace B exists with a distinct ID.

**Steps:**
1. `PUT` to `/workspaces/{workspaceBId}/areas/{areaXId}` using the correct
   `expectedVersion` for Area X.

**Expected Result:**
- HTTP `404 Not Found`.
- Response body `code` = `"AREA_NOT_FOUND"`.
- Area X is **not** modified.
- Workspace B membership does **not** grant access to Area X. The existence
  and version of Area X are not leaked in the response.

---

### TC-UPDATE-007 — Deleted Area returns 409 AREA_DELETED

**Type:** API
**Priority:** High

**Preconditions:**
- Area exists with `status = DELETED`.

**Steps:**
1. `PUT` with the correct `expectedVersion`.

**Expected Result:**
- HTTP `409 Conflict`.
- Response body `code` = `"AREA_DELETED"`.

---

### TC-UPDATE-008 — Unknown workspaceId returns 404 WORKSPACE_NOT_FOUND

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- The target workspace ID does not exist.

**Steps:**
1. `PUT` to a random workspace ID.

**Request:**
- Method: `PUT`
- Path: `/workspaces/00000000-0000-0000-0000-000000000099/areas/{randomUuid}`
- Body: `{"expectedVersion": 1, "name": "X", "geometry": <Reference Polygon>}`

**Expected Result:**
- HTTP `404 Not Found`.
- Response body `code` = `"WORKSPACE_NOT_FOUND"`.

---

### TC-UPDATE-009 — Blank name returns 400 INVALID_REQUEST

**Type:** API
**Priority:** High

**Preconditions:**
- Active Area at version 1 exists.

**Steps:**
1. `PUT` with `name: ""`.

**Expected Result:**
- HTTP `400 Bad Request`.
- Response body `code` = `"INVALID_REQUEST"`.
- No new `area_version` row is created.

---

### TC-UPDATE-010 — Invalid polygon returns 400 INVALID_GEOMETRY, no version persisted

**Type:** API
**Priority:** High

**Preconditions:**
- Active Area at version 1 exists.

**Steps:**
1. `PUT` with a self-intersecting polygon (bowtie shape, same as TC-AREA-010).

**Expected Result:**
- HTTP `400 Bad Request`.
- Response body `code` = `"INVALID_GEOMETRY"`.
- No new `area_version` row is created. Area remains at version 1.

---

### 4.4 Area Delete

---

### TC-DELETE-001 — Successful delete returns 200 with DELETED status and incremented version

**Type:** API
**Priority:** Critical

**Preconditions:**
- Backend is running.
- Dev Workspace exists.
- An Active Area at version 1 exists. Note its `id`.

**Steps:**
1. Send a `DELETE` request with `expectedVersion=1`.

**Request:**
- Method: `DELETE`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas/{id}?expectedVersion=1`

**Expected Result:**
- HTTP `200 OK`.
- Response body contains:

| Field | Expected value |
|-------|---------------|
| `id` | Same UUID as the created Area |
| `workspaceId` | `"00000000-0000-0000-0000-000000000002"` |
| `status` | `"DELETED"` |
| `currentVersion` | `2` |

---

### TC-DELETE-002 — AreaVersion snapshot is persisted with DELETED change type and original fields

**Type:** API
**Priority:** High

**Preconditions:**
- Active Area at version 1, created with the Reference Polygon and name `"Test"`.

**Steps:**
1. `DELETE` with `expectedVersion=1`.
2. Verify the persisted `area_version` record (direct database inspection in automated
   tests).

**Expected Result:**
- A new `area_version` row exists with all of:

| Field | Expected value |
|-------|---------------|
| `version` | `2` |
| `change_type` | `DELETED` |
| `status` | `DELETED` |
| `name` | `"Test"` (unchanged from the Area before deletion) |
| `geometry` | Matches the Reference Polygon (unchanged) |
| `area_km2` | Positive decimal matching the original area (unchanged) |

---

### TC-DELETE-003 — Missing expectedVersion returns 400 INVALID_REQUEST

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace exists.
- An Active Area exists.

**Steps:**
1. `DELETE` without the `expectedVersion` query parameter.

**Request:**
- Method: `DELETE`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas/{id}` (no query parameter)

**Expected Result:**
- HTTP `400 Bad Request`.
- Response body `code` = `"INVALID_REQUEST"`.
- Area is not modified.

---

### TC-DELETE-004 — Stale expectedVersion returns 409 VERSION_CONFLICT with structured details

**Type:** API
**Priority:** Critical

**Preconditions:**
- Active Area at version 2 (one update already applied).

**Steps:**
1. `DELETE` with `expectedVersion=1` (stale).

**Request:**
- Method: `DELETE`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas/{id}?expectedVersion=1`

**Expected Result:**
- HTTP `409 Conflict`.
- Response body `code` = `"VERSION_CONFLICT"`.
- Response body `details` object contains:

| Field | Expected value |
|-------|---------------|
| `areaId` | UUID of the Area |
| `expectedVersion` | `1` |
| `currentVersion` | `2` |

- Area is not modified.

---

### TC-DELETE-005 — Deleting an already-DELETED Area returns 409 AREA_DELETED

**Type:** API
**Priority:** High

**Preconditions:**
- Area has already been deleted. Its `status = DELETED` and `currentVersion = N`.

**Steps:**
1. `DELETE` with `expectedVersion=N` (matching the current post-deletion version).

**Request:**
- Method: `DELETE`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas/{id}?expectedVersion={N}`

**Expected Result:**
- HTTP `409 Conflict`.
- Response body `code` = `"AREA_DELETED"`.

**Note:** This case is semantically distinct from VERSION_CONFLICT: the version
matches the current version, but the operation is rejected because the Area is
already in the DELETED state.

---

### TC-DELETE-006 — Concurrent deletes: exactly one succeeds, other receives VERSION_CONFLICT

**Type:** API
**Priority:** High

**Preconditions:**
- Active Area at version 1.

**Steps:**
1. Send two concurrent `DELETE` requests, both with `expectedVersion=1`.

**Expected Result:**
- Exactly one response is `200 OK`.
- Exactly one response is `409 Conflict` with `code = "VERSION_CONFLICT"` (not
  `"AREA_DELETED"`).
- Final `currentVersion` of the Area is `2`.

**Note:** The concurrent loser observes `currentVersion = 2` after the winner
completes, which does not match `expectedVersion = 1`, triggering
VERSION_CONFLICT before the DELETED status check is reached.

---

### TC-DELETE-007 — Unknown areaId returns 404 AREA_NOT_FOUND

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace exists.

**Steps:**
1. `DELETE` using a random UUID that has not been created.

**Request:**
- Method: `DELETE`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas/{randomUuid}?expectedVersion=1`

**Expected Result:**
- HTTP `404 Not Found`.
- Response body `code` = `"AREA_NOT_FOUND"`.

---

### TC-DELETE-008 — Area belonging to a different workspace returns 404 AREA_NOT_FOUND

**Type:** API
**Priority:** Critical

**Preconditions:**
- Area X exists in Workspace A (`00000000-0000-0000-0000-000000000002`).
- Workspace B exists with a distinct ID.

**Steps:**
1. `DELETE` `/workspaces/{workspaceBId}/areas/{areaXId}?expectedVersion=1`.

**Expected Result:**
- HTTP `404 Not Found`.
- Response body `code` = `"AREA_NOT_FOUND"`.
- Area X is **not** modified.
- The existence and version of Area X are not leaked in the response.

---

### TC-DELETE-009 — Unknown workspace returns 404 WORKSPACE_NOT_FOUND

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- The target workspace ID does not exist.

**Steps:**
1. `DELETE` using a random workspace ID.

**Request:**
- Method: `DELETE`
- Path: `/workspaces/00000000-0000-0000-0000-000000000099/areas/{randomUuid}?expectedVersion=1`

**Expected Result:**
- HTTP `404 Not Found`.
- Response body `code` = `"WORKSPACE_NOT_FOUND"`.

---

### TC-DELETE-010 — Deleted Area is excluded from viewport query

**Type:** API
**Priority:** Critical

**Preconditions:**
- Backend is running.
- Dev Workspace exists.
- Active Area exists using the Reference Polygon.

**Steps:**
1. `DELETE` the Area with the correct `expectedVersion`.
2. `GET` with a bbox that fully encloses the Reference Polygon.

**Request (step 2):**
- Method: `GET`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas?bbox=35.49,32.69,35.56,32.76`

**Expected Result:**
- `DELETE` returns `200 OK`.
- `GET` returns `200 OK` with an empty array: the deleted Area does not appear.

---

### 4.5 Area Restore

---

### TC-RESTORE-001 — Successful restore returns 200 with ACTIVE status and incremented version

**Type:** API
**Priority:** Critical

**Preconditions:**
- Area exists at version 2. Status is `DELETED` (Area was created then deleted).
- Version 1 exists in the AreaVersion history.

**Steps:**
1. Send `POST .../areas/{id}/versions/1/restore` with `{ "expectedVersion": 2 }`.

**Request:**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas/{id}/versions/1/restore`
- Body: `{ "expectedVersion": 2 }`

**Expected Result:**
- HTTP `200 OK`.
- Response body contains:

| Field | Expected value |
|-------|---------------|
| `id` | Same UUID as the Area |
| `status` | `"ACTIVE"` |
| `currentVersion` | `3` |

---

### TC-RESTORE-002 — AreaVersion snapshot is persisted with RESTORED change type and restored fields

**Type:** API
**Priority:** High

**Preconditions:**
- Area at version 2 (`DELETED`). Version 1 had name `"Original"` and the Reference Polygon.

**Steps:**
1. Restore version 1 with `expectedVersion=2`.
2. Verify the persisted `area_version` record (direct database inspection in automated tests).

**Expected Result:**
- A new `area_version` row exists with all of:

| Field | Expected value |
|-------|---------------|
| `version` | `3` |
| `change_type` | `RESTORED` |
| `status` | `ACTIVE` |
| `name` | `"Original"` (from version 1) |
| `geometry` | Matches the Reference Polygon (from version 1) |
| `area_km2` | Positive decimal matching the Reference Polygon area (from version 1) |

---

### TC-RESTORE-003 — Restore copies name, geometry, and area_km2 from the target version

**Type:** API
**Priority:** Critical

**Preconditions:**
- Area at version 1 with name `"Original"` and Reference Polygon.
- Area updated to version 2 with name `"Modified"` and Alternate Reference Polygon.

**Steps:**
1. Restore version 1 with `expectedVersion=2`.

**Expected Result:**
- HTTP `200 OK`.
- Response body `name` = `"Original"`.
- Response body `areaKm2` reflects the Reference Polygon area (not the Alternate).
- Response body `currentVersion` = `3`.
- Response body `status` = `"ACTIVE"`.

---

### TC-RESTORE-004 — Restore increments version monotonically; existing AreaVersions are unchanged

**Type:** API
**Priority:** High

**Preconditions:**
- Area exists with versions 1 through N.

**Steps:**
1. Restore version 1 with `expectedVersion=N`.
2. Verify AreaVersion records (direct database inspection in automated tests).

**Expected Result:**
- A new version N+1 exists with `change_type = RESTORED`.
- Versions 1 through N are unchanged (verified by count and individual record checks).
- `currentVersion` of the Area = N+1.

---

### TC-RESTORE-005 — Stale expectedVersion returns 409 VERSION_CONFLICT

**Type:** API
**Priority:** Critical

**Preconditions:**
- Active Area at version 2.

**Steps:**
1. `POST .../versions/1/restore` with `{ "expectedVersion": 99 }`.

**Expected Result:**
- HTTP `409 Conflict`.
- Response body `code` = `"VERSION_CONFLICT"`.
- Area is unchanged.

---

### TC-RESTORE-006 — Unknown target version returns 404 AREA_VERSION_NOT_FOUND

**Type:** API
**Priority:** High

**Preconditions:**
- Area exists at version 1.

**Steps:**
1. `POST .../versions/999/restore` with `{ "expectedVersion": 1 }`.

**Request:**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas/{id}/versions/999/restore`
- Body: `{ "expectedVersion": 1 }`

**Expected Result:**
- HTTP `404 Not Found`.
- Response body `code` = `"AREA_VERSION_NOT_FOUND"`.

---

### TC-RESTORE-007 — Unknown areaId returns 404 AREA_VERSION_NOT_FOUND

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.
- Dev Workspace exists.
- The target area ID does not exist.

**Steps:**
1. `POST .../areas/{randomUuid}/versions/1/restore` with `{ "expectedVersion": 1 }`.

**Request:**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas/{randomUuid}/versions/1/restore`
- Body: `{ "expectedVersion": 1 }`

**Expected Result:**
- HTTP `404 Not Found`.
- Response body `code` = `"AREA_VERSION_NOT_FOUND"`.

**Note:** The server looks up the AreaVersion by `(area_id, version)` before checking the
Area itself. When the area does not exist, no AreaVersion is found, so the response
code is `AREA_VERSION_NOT_FOUND` rather than `AREA_NOT_FOUND`.

---

### TC-RESTORE-008 — Restore a historical version of an ACTIVE Area

**Type:** API
**Priority:** Critical

**Preconditions:**
- Area v1 contains the original snapshot.
- Area is updated to v2 with a different name and geometry.
- Area remains `ACTIVE`.
- `currentVersion = 2`.

**Steps:**
1. Send `POST .../areas/{areaId}/versions/1/restore` with `{ "expectedVersion": 2 }`.

**Request:**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas/{id}/versions/1/restore`
- Body: `{ "expectedVersion": 2 }`

**Expected Result:**
- HTTP `200 OK`.
- Response body `status` = `"ACTIVE"`.
- Response body `currentVersion` = `3`.
- Response body `name`, `geometry`, and `areaKm2` correspond to the v1 snapshot.
- A new `area_version` v3 exists with `change_type = RESTORED`.
- Existing v1 and v2 remain unchanged.

**Note:** This case verifies that Restore is not limited to undeleting Areas.
Restore explicitly supports reverting an ACTIVE Area to any historical snapshot.

---

### TC-RESTORE-009 — Null expectedVersion returns 400 INVALID_REQUEST

**Type:** API
**Priority:** High

**Preconditions:**
- Area at version 1 exists.

**Steps:**
1. `POST .../versions/1/restore` with `{ "expectedVersion": null }`.

**Expected Result:**
- HTTP `400 Bad Request`.
- Response body `code` = `"INVALID_REQUEST"`.
- Area is not modified.

---

## 4b. Area Version History Tests

### Shared Preconditions

- Backend is running.
- Dev Workspace and Dev User are seeded.
- Reference Polygon is defined in Section 3.

---

### TC-HISTORY-001 — Version list returns all versions ordered version DESC

**Type:** API
**Priority:** Critical

**Preconditions:**
- An Area `{areaId}` has been created once and updated twice, producing versions 1, 2, 3.

**Steps:**
1. `GET /workspaces/00000000-0000-0000-0000-000000000002/areas/{areaId}/versions`

**Expected Result:**
- HTTP `200 OK`.
- Response is a JSON array of 3 objects.
- `[0].version` = `3`, `[1].version` = `2`, `[2].version` = `1`.

---

### TC-HISTORY-002 — Version list contains all expected change types

**Type:** API
**Priority:** High

**Preconditions:**
- Area created, updated once, deleted, then restored from version 1.
- Area is now at version 4.

**Steps:**
1. `GET .../versions`

**Expected Result:**
- HTTP `200 OK`.
- Response is an array of 4 objects ordered version DESC.
- Change types newest-first: `RESTORED`, `DELETED`, `UPDATED`, `CREATED`.

---

### TC-HISTORY-003 — Each version snapshot contains correct name, geometry, areaKm2, status, and changeType

**Type:** API
**Priority:** High

**Preconditions:**
- Area created with name `"First"` and geometry A (Reference Polygon).
- Area updated with name `"Second"` and geometry B (a different valid polygon).

**Steps:**
1. `GET .../versions`

**Expected Result:**
- HTTP `200 OK`.
- `[0]` (v2): `name` = `"Second"`, `status` = `"ACTIVE"`, `changeType` = `"UPDATED"`,
  geometry matches B, `areaKm2` > 0.
- `[1]` (v1): `name` = `"First"`, `status` = `"ACTIVE"`, `changeType` = `"CREATED"`,
  geometry matches A.
- `geometry.coordinates` of v1 and v2 differ.
- Each entry contains `id` (UUID), `createdBy` (UUID), and `createdAt`
  (ISO-8601 timestamp).

---

### TC-HISTORY-004 — Version list is available for a DELETED Area

**Type:** API
**Priority:** Medium

**Preconditions:**
- Area created and then deleted. `currentVersion` = 2.

**Steps:**
1. `GET .../versions`

**Expected Result:**
- HTTP `200 OK`.
- Response is an array of 2 objects.
- `[0].changeType` = `"DELETED"`, `[0].status` = `"DELETED"`.
- `[1].changeType` = `"CREATED"`, `[1].status` = `"ACTIVE"`.

---

### TC-HISTORY-005 — Unknown areaId returns 404 AREA_NOT_FOUND

**Type:** API
**Priority:** High

**Steps:**
1. `GET /workspaces/00000000-0000-0000-0000-000000000002/areas/{randomUUID}/versions`

**Expected Result:**
- HTTP `404 Not Found`.
- Response body `code` = `"AREA_NOT_FOUND"`.

---

### TC-HISTORY-006 — Area in another workspace returns 404 AREA_NOT_FOUND

**Type:** API
**Priority:** High

**Preconditions:**
- A second Workspace W2 exists. An Area `{areaId-W2}` belongs to W2.

**Steps:**
1. `GET /workspaces/00000000-0000-0000-0000-000000000002/areas/{areaId-W2}/versions`

**Expected Result:**
- HTTP `404 Not Found`.
- Response body `code` = `"AREA_NOT_FOUND"`.
- The existence of the Area in W2 is not revealed.

---

### TC-HISTORY-007 — Unknown workspace returns 404 WORKSPACE_NOT_FOUND

**Type:** API
**Priority:** Medium

**Steps:**
1. `GET /workspaces/{randomUUID}/areas/{anyAreaId}/versions`

**Expected Result:**
- HTTP `404 Not Found`.
- Response body `code` = `"WORKSPACE_NOT_FOUND"`.

---

### TC-HISTORY-008 — Restore from history creates a new RESTORED version; previous versions are unchanged

**Type:** API
**Priority:** Critical

**Preconditions:**
- Area at version 2 with name `"v2"` and geometry B.
- Version 1 has name `"v1"` and geometry A.

**Steps:**
1. `POST .../areas/{areaId}/versions/1/restore` with `{ "expectedVersion": 2 }`.
2. `GET .../areas/{areaId}/versions`.

**Request (step 1):**
- Method: `POST`
- Path: `/workspaces/00000000-0000-0000-0000-000000000002/areas/{areaId}/versions/1/restore`
- Body: `{ "expectedVersion": 2 }`

**Expected Result:**
- Step 1 returns HTTP `200 OK`.
- Response body `currentVersion` = `3`, `status` = `"ACTIVE"`.
- Response body `name`, `geometry`, and `areaKm2` correspond to the v1 snapshot.
- Step 2 returns an array of 3 objects.
- `[0]` (v3): `changeType` = `"RESTORED"`, `name` = `"v1"`, `status` = `"ACTIVE"`,
  geometry and `areaKm2` match the v1 snapshot.
- `[1]` (v2): `changeType` = `"UPDATED"`, `name` = `"v2"`, geometry matches B.
  Values are identical to those recorded before the restore.
- `[2]` (v1): `changeType` = `"CREATED"`, `name` = `"v1"`, geometry matches A.
  Values are identical to those recorded at creation.
- Total number of AreaVersions is exactly 3. No historical version is
  modified or removed.

---

## 4c. WebSocket / Real-Time Tests

### Shared Preconditions

- Backend is running with the dev profile active.
- Dev Workspace and Dev User are seeded.
- A STOMP client is connected to `ws://localhost:8081/ws` and subscribed to
  `/topic/workspace/00000000-0000-0000-0000-000000000002` before the triggering
  HTTP request is sent.

---

### TC-WS-001 — WebSocket connection to /ws succeeds

**Type:** API
**Priority:** High

**Preconditions:**
- Backend is running.

**Steps:**
1. Open a STOMP WebSocket connection to `ws://localhost:8081/ws`.
2. Wait for the CONNECTED frame.

**Expected Result:**
- Connection is established and the STOMP CONNECTED frame is received without
  error.

---

### TC-WS-002 — Create area publishes AREA_CREATED event on the workspace topic

**Type:** API
**Priority:** Critical

**Preconditions:**
- STOMP client is subscribed to the Dev Workspace topic.
- No Areas exist in the workspace.

**Steps:**
1. Send `POST /workspaces/00000000-0000-0000-0000-000000000002/areas` with a
   valid name and the Reference Polygon.
2. Wait for a message to arrive on the subscribed topic.

**Expected Result:**
- HTTP `201 Created` is returned.
- A message is received within a reasonable timeout.
- Message body fields:

| Field | Expected value |
|-------|---------------|
| `type` | `"AREA_CREATED"` |
| `workspaceId` | `"00000000-0000-0000-0000-000000000002"` |
| `timestamp` | Non-blank ISO-8601 string |
| `payload.id` | UUID matching the `id` in the `201` response |
| `payload.currentVersion` | `1` |
| `payload.status` | `"ACTIVE"` |

---

### TC-WS-003 — Update area publishes AREA_UPDATED event with new version number

**Type:** API
**Priority:** High

**Preconditions:**
- An Active Area at version 1 exists. Note its `id`.
- STOMP client is subscribed to the Dev Workspace topic.

**Steps:**
1. Send `PUT .../areas/{id}` with `expectedVersion: 1`, a new name, and valid geometry.
2. Wait for a message to arrive on the subscribed topic.

**Expected Result:**
- HTTP `200 OK`.
- Message received with:
  - `type` = `"AREA_UPDATED"`
  - `payload.id` matches `{id}`
  - `payload.currentVersion` = `2`
  - `payload.status` = `"ACTIVE"`

---

### TC-WS-004 — Delete area publishes AREA_DELETED event with DELETED status

**Type:** API
**Priority:** High

**Preconditions:**
- An Active Area at version 1 exists. Note its `id`.
- STOMP client is subscribed to the Dev Workspace topic.

**Steps:**
1. Send `DELETE .../areas/{id}?expectedVersion=1`.
2. Wait for a message to arrive on the subscribed topic.

**Expected Result:**
- HTTP `200 OK`.
- Message received with:
  - `type` = `"AREA_DELETED"`
  - `payload.id` matches `{id}`
  - `payload.currentVersion` = `2`
  - `payload.status` = `"DELETED"`

---

### TC-WS-005 — Restore area publishes AREA_RESTORED event with ACTIVE status and incremented version

**Type:** API
**Priority:** High

**Preconditions:**
- Area exists at version 2 (`DELETED`). Version 1 exists in history.
- STOMP client is subscribed to the Dev Workspace topic.

**Steps:**
1. Send `POST .../areas/{id}/versions/1/restore` with `{ "expectedVersion": 2 }`.
2. Wait for a message to arrive on the subscribed topic.

**Expected Result:**
- HTTP `200 OK`.
- Message received with:
  - `type` = `"AREA_RESTORED"`
  - `payload.id` matches `{id}`
  - `payload.currentVersion` = `3`
  - `payload.status` = `"ACTIVE"`

---

### TC-WS-006 — Event for workspace A is not received by a subscriber on workspace B topic

**Type:** API
**Priority:** Critical

**Preconditions:**
- Dev Workspace A (`00000000-0000-0000-0000-000000000002`) and a second
  Workspace B (distinct ID) both exist.
- STOMP client is subscribed only to `/topic/workspace/{workspaceA-id}`.

**Steps:**
1. Create an Area in Workspace B via `POST`.
2. Wait for the timeout period used in positive-event tests.

**Expected Result:**
- No message is received on the Workspace A topic.

---

### TC-WS-007 — Invalid geometry (rejected before DB write) produces no WebSocket event

**Type:** API
**Priority:** High

**Preconditions:**
- STOMP client is subscribed to the Dev Workspace topic.

**Steps:**
1. Send `POST` with a self-intersecting (bowtie) polygon.
2. Wait for the timeout period.

**Expected Result:**
- HTTP `400 Bad Request`.
- No message is received on the topic during the timeout window.

---

### TC-WS-008 — Version conflict (rolled-back transaction) produces no WebSocket event

**Type:** API
**Priority:** Critical

**Preconditions:**
- An Active Area at version 2 exists.
- STOMP client is subscribed to the Dev Workspace topic.

**Steps:**
1. Send `PUT .../areas/{id}` with `expectedVersion: 1` (stale).
2. Wait for the timeout period.

**Expected Result:**
- HTTP `409 Conflict` with `code = "VERSION_CONFLICT"`.
- No message is received on the topic during the timeout window.

---

## 5. UI End-to-End Tests

### Shared UI Preconditions

Unless stated otherwise:
- Backend is running on `http://localhost:8081` with the dev profile active.
- Frontend is running on `http://localhost:4201`.
- Dev Workspace and Dev User are seeded.
- The browser is at `http://localhost:4201`.
- No Areas exist in the workspace (clean state).

---

### TC-UI-001 — Application opens and map is interactive

**Type:** UI
**Priority:** Critical

**Preconditions:**
- Backend and Frontend are running.

**Steps:**
1. Navigate to `http://localhost:4201`.
2. Wait for the page to finish loading.
3. Observe the page content and toolbar.

**Expected Result:**
- The page title is "Snapland GIS".
- A map fills the viewport.
- A drawing toolbar with a polygon draw tool is visible.
- The user can interact with the map (pan and zoom respond to input).

---

### TC-UI-002 — Draw and save Area end-to-end

**Type:** UI
**Priority:** Critical

**Preconditions:**
- Application is open and map is interactive (TC-UI-001 passes).
- No Areas exist in the workspace.

**Steps:**
1. Click the polygon draw tool in the map toolbar.
2. Click at least three distinct points on the map to define polygon vertices.
3. Complete the polygon (double-click the last point, or click the "Finish
   drawing" button in the toolbar).
4. Observe the panel that appears.
5. Note the area preview value displayed.
6. Observe the Save button state while the name field is empty.
7. Type a name (e.g., `"My Area"`) in the name input field.
8. Click Save.
9. Observe the map and panel after the save completes.

**Expected Result:**
- After completing the polygon (step 3): a floating "New Area" panel appears
  in the top-right corner of the map, showing a computed area preview in km²
  and an empty name input field.
- The Save button is disabled while the name field is empty (step 6).
- After clicking Save (step 8):
  - The panel is dismissed.
  - The saved Area polygon is rendered on the map.
  - The Area is retrievable via
    `GET /workspaces/.../areas?bbox=...` covering the drawn region.

---

### TC-UI-003 — Saved Area is visible after browser refresh

**Type:** UI
**Priority:** Critical

**Preconditions:**
- At least one Area has been successfully saved (TC-UI-002 has passed).
- The saved Area polygon is visible on the map.

**Steps:**
1. Confirm the saved Area is visible on the map.
2. Perform a full browser page refresh.
3. Wait for the page and map to fully reload.
4. Observe the map.

**Expected Result:**
- The previously saved Area polygon is visible on the map after the refresh.
- No user action is required to make it reappear.

---

### TC-UI-004 — Panning or zooming loads Areas for the new viewport

**Type:** UI
**Priority:** High

**Preconditions:**
- At least one Area exists in the system (TC-UI-002 has passed).

**Steps:**
1. Pan or zoom the map away from the region containing the saved Area so it
   is no longer in the visible viewport.
2. Pan or zoom back to the region containing the saved Area.
3. Wait for the viewport to settle.
4. Observe the map.

**Expected Result:**
- After returning to the region containing the saved Area: the Area polygon
  is loaded and rendered on the map.
- Areas outside the current viewport are not expected to be shown.

---

### TC-UI-005 — Backend save failure is communicated to the user

**Type:** UI
**Priority:** Low

**Preconditions:**
- Application is open and map is interactive.
- The backend is made temporarily unreachable or returns an error for the
  save request.

**Steps:**
1. Draw a polygon on the map and complete it.
2. Enter a name in the save panel.
3. Click Save while the backend is unavailable.
4. Observe the UI.

**Expected Result:**
- A failure notification is shown to the user without leaving the UI in a
  silent broken state.
- **Current prototype behaviour:** a browser `alert()` dialog appears with
  the message "Failed to save area. See console for details."
- The failure notification mechanism is expected to be improved in future
  iterations.

---

### TC-UI-UPDATE-001 — Edit an existing Area and save name and geometry changes

**Type:** UI
**Priority:** Critical

**Preconditions:**
- Application is open and map is interactive.
- At least one persisted Active Area is visible on the map.

**Steps:**
1. Click the saved Area polygon on the map. A popup appears showing the Area
   name and size.
2. Click the "Edit" button in the popup.
3. Observe the map and panel.
4. Move at least one vertex of the editable polygon to change its shape.
5. Change the name in the edit panel to a new value (e.g., `"Renamed Area"`).
6. Click "Save".
7. Observe the map and panel after save completes.

**Expected Result:**
- After clicking "Edit" (step 3): the polygon is highlighted as editable
  (orange outline), and an "Edit Area" panel appears showing the current name
  and version.
- After clicking "Save" (step 6):
  - The edit panel is dismissed.
  - The Area on the map shows the new shape.
  - Clicking the Area polygon opens a popup showing the new name and the
    recalculated area in km².

---

### TC-UI-UPDATE-002 — Updated Area state persists after browser refresh

**Type:** UI
**Priority:** Critical

**Preconditions:**
- TC-UI-UPDATE-001 has passed. The Area has been renamed and reshaped.

**Steps:**
1. Confirm the updated name and shape are visible on the map.
2. Perform a full browser page refresh.
3. Wait for the page and map to fully reload.
4. Pan/zoom to the Area's location and observe the map.

**Expected Result:**
- The Area polygon appears in its updated shape.
- Clicking the polygon opens a popup showing the updated name, not the
  original name.
- No user action is required to reload the current state.

---

### TC-UI-UPDATE-003 — Saving a stale edit informs the user and reloads authoritative state

**Type:** UI
**Priority:** High

**Preconditions:**
- Application is open in two browser tabs (Tab A and Tab B), both showing
  the same Active Area.

**Steps:**
1. In Tab A: click the Area, click "Edit", change the name, and click "Save".
   Confirm Tab A shows the updated name.
2. In Tab B (without refreshing): click the same Area, click "Edit".
3. In Tab B: change the name to a different value and click "Save".
4. Observe Tab B.

**Expected Result:**
- Tab B displays an alert dialog informing the user that the Area was
  modified by another user.
- After the alert is dismissed, the map in Tab B reloads and shows the
  version saved by Tab A (the authoritative state), not the value Tab B
  attempted to save.

---

### TC-UI-DELETE-001 — Delete an Area via the Edit panel and confirm removal from the map

**Type:** UI
**Priority:** Critical

**Preconditions:**
- Application is open and map is interactive.
- At least one persisted Active Area is visible on the map.

**Steps:**
1. Click the Area polygon on the map. A popup appears showing the Area name and size.
2. Click the "Edit" button in the popup.
3. Observe the edit panel.
4. Click the "Delete" button.
5. Observe the map and panel.

**Expected Result:**
- The edit panel is dismissed.
- The Area polygon is replaced by a grayed-out polygon at the same location,
  indicating the Area has been deleted.
- An "Area Deleted" panel appears in the top-right corner showing the deleted
  Area's name and a "Restore" button.
- The Area is no longer returned by a viewport query (`status = DELETED`).

---

### TC-UI-DELETE-002 — Deleted Area can be immediately restored via the Restore panel

**Type:** UI
**Priority:** Critical

**Preconditions:**
- TC-UI-DELETE-001 has completed. The "Area Deleted" panel is visible.
- The grayed-out polygon is on the map.

**Steps:**
1. Click the "Restore" button in the "Area Deleted" panel.
2. Observe the map and panel.

**Expected Result:**
- The "Area Deleted" panel is dismissed.
- The grayed-out polygon is removed.
- The Area reappears as an active polygon (standard appearance) at the same location.
- The Area is returned by a subsequent viewport query with `status = "ACTIVE"`.

---

### TC-UI-DELETE-003 — Dismissing the Restore panel removes the deleted indicator from the map

**Type:** UI
**Priority:** High

**Preconditions:**
- TC-UI-DELETE-001 has completed. The "Area Deleted" panel is visible.

**Steps:**
1. Click the "Dismiss" button in the "Area Deleted" panel.
2. Observe the map and panel.

**Expected Result:**
- The "Area Deleted" panel is dismissed.
- The grayed-out polygon is removed from the map.
- The Area remains DELETED. It does not reappear on map reload within its
  former viewport.

---

### TC-UI-HISTORY-001 — Open the History panel and view the version list

**Type:** UI
**Priority:** High

**Preconditions:**
- An ACTIVE Area with at least 2 versions is visible on the map.

**Steps:**
1. Click the Area polygon — popup opens showing the Area name, area in km², an
   **Edit** button, and a **History** button.
2. Click **History**.
3. Observe the map and panel.

**Expected Result:**
- Popup closes.
- A History panel appears.
- Panel header shows the Area name.
- Panel lists version rows ordered newest version first.
- Each row displays: version number, change type, date/time.
- A **Close** button is present.

---

### TC-UI-HISTORY-002 — Clicking a version row shows a geometry preview on the map

**Type:** UI
**Priority:** Medium

**Preconditions:**
- History panel is open. At least 2 versions with different geometries exist.

**Steps:**
1. Click a version row whose geometry differs from the current live polygon.
2. Observe the map.
3. Click a different version row.

**Expected Result:**
- After step 2: the selected row is visually highlighted; an orange dashed
  polygon appears on the map representing the selected version's geometry.
  The live polygon is still visible.
- After step 3: the previous preview is removed and a new preview reflects
  the newly selected version's geometry.

---

### TC-UI-HISTORY-003 — Restore from the History panel updates the map

**Type:** UI
**Priority:** High

**Preconditions:**
- History panel is open. Area is at version 2. Version 1 has name `"v1"`.

**Steps:**
1. Click **Restore** on the version 1 row.
2. Read the browser confirm dialog and confirm the action.
3. Observe the map and panel.

**Expected Result:**
- The confirm dialog message explains that restoring creates a new version
  and does not modify or remove history.
- After confirmation: History panel closes, orange preview polygon is removed.
- Map reloads; the Area polygon reflects the restored state. Clicking the
  polygon opens a popup showing name `"v1"`.

---

### TC-UI-HISTORY-004 — Closing the History panel removes the preview without changing the Area

**Type:** UI
**Priority:** Medium

**Preconditions:**
- History panel is open; a version row is selected so an orange preview polygon
  is visible on the map.

**Steps:**
1. Click **Close**.
2. Observe the map.

**Expected Result:**
- History panel closes.
- Orange preview polygon is removed from the map.
- The live persisted Area polygon is unchanged.

---

### TC-UI-HISTORY-005 — Stale restore from History informs the user and reloads authoritative state

**Type:** UI
**Priority:** Medium

**Preconditions:**
- History panel is open (captured `expectedVersion` = 2). While the panel
  is open, another client mutates the Area so it is now at version 3.

**Steps:**
1. Click **Restore** on any version row in the History panel.
2. Confirm the browser dialog.
3. Observe the result.

**Expected Result:**
- The restore request returns `409 VERSION_CONFLICT`.
- The user sees an informational alert about the concurrent modification.
- The History panel closes and the preview is removed.
- The map reloads and displays the authoritative current state of the Area.

---

### TC-UI-WS-001 — Created Area appears in another browser

**Type:** UI
**Priority:** Critical

**Preconditions:**
- The same Workspace is open in Browser/Tab A and Browser/Tab B.
- Both clients are connected to WebSocket.

**Steps:**
1. In A, create and save an Area inside the viewport visible in B.

**Expected Result:**
- B displays the newly persisted Area without manual refresh.
- The Area name and geometry correspond to the saved state.

---

### TC-UI-WS-002 — Updated Area is refreshed in another browser

**Type:** UI
**Priority:** High

**Preconditions:**
- The same persisted Area is visible in A and B.

**Steps:**
1. In A, edit the Area name and geometry.
2. Save.

**Expected Result:**
- B displays the new persisted name and geometry without manual refresh.

---

### TC-UI-WS-003 — Deleted Area disappears in another browser

**Type:** UI
**Priority:** High

**Preconditions:**
- The same Area is visible in A and B.

**Steps:**
1. Delete the Area in A and confirm.

**Expected Result:**
- The Area disappears from B without manual refresh.

---

### TC-UI-WS-004 — Restored Area reappears in another browser

**Type:** UI
**Priority:** High

**Preconditions:**
- A and B are open on the same Workspace.
- An Area has just been deleted in A.

**Steps:**
1. Use the immediate Restore action in A.

**Expected Result:**
- The restored Area appears in B without manual refresh.
- It reflects the restored persisted state.

---

### TC-UI-WS-005 — Reconnect resynchronizes authoritative viewport state

**Type:** UI
**Priority:** High

**Preconditions:**
- A and B show the same Workspace.

**Steps:**
1. Disconnect B from the backend/WebSocket.
2. Mutate an Area in A while B is disconnected.
3. Reconnect B.

**Expected Result:**
- B resynchronizes the current viewport through REST.
- B displays the authoritative persisted state even though it missed the
  WebSocket event.

---

### TC-UI-WS-006 — Peer update does not destroy an edit in progress

**Type:** UI
**Priority:** High

**Preconditions:**
- Area is at version N and visible in A and B.

**Steps:**
1. In B, start editing the Area and make unsaved changes.
2. In A, edit and save the same Area, producing version N+1.
3. Observe B.
4. Attempt to save B's edit.

**Expected Result:**
- B's unsaved geometry and form values are not replaced by the realtime
  update.
- B shows the non-blocking warning that a newer version was saved by
  another user.
- Saving B's stale edit returns VERSION_CONFLICT.
- B then reloads authoritative persisted state according to the existing
  conflict-handling behavior.

---

### TC-UI-AREA-CALC-001 — Live area indicator appears once the third vertex is placed

**Type:** UI
**Priority:** Medium

**Preconditions:**
- Application is open and map is interactive.
- No polygon is being drawn.

**Steps:**
1. Click the polygon draw tool in the Leaflet Draw toolbar.
2. Click two points on the map.
3. Observe the map.
4. Click a third point.
5. Observe the map.

**Expected Result:**
- After step 3: no live area indicator is visible.
- After step 5: a live area indicator appears showing "Area: X.XXX km²" (0 to 3 decimal places).

---

### TC-UI-AREA-CALC-002 — Live area indicator updates as additional vertices are added

**Type:** UI
**Priority:** Medium

**Preconditions:**
- TC-UI-AREA-CALC-001 conditions: three vertices have been placed and the indicator is visible.

**Steps:**
1. Click a fourth point that visibly enlarges or changes the polygon shape.
2. Observe the indicator value.
3. Click a fifth point.
4. Observe the indicator value again.

**Expected Result:**
- The indicator value changes with each new vertex.
- The value is always a non-negative number formatted as 0–3 decimal places followed by " km²".

---

### TC-UI-AREA-CALC-003 — Live area indicator disappears when drawing is cancelled

**Type:** UI
**Priority:** Medium

**Preconditions:**
- A polygon is being drawn and the live area indicator is visible (≥3 vertices placed).

**Steps:**
1. Press Escape or click the cancel control to stop drawing without completing the polygon.
2. Observe the map.

**Expected Result:**
- The live area indicator is no longer visible.
- No "New Area" panel appears.

---

### TC-UI-AREA-CALC-004 — Live area indicator disappears and "New Area" panel appears after polygon completion

**Type:** UI
**Priority:** Medium

**Preconditions:**
- A polygon is being drawn and the live area indicator is visible (≥3 vertices placed).

**Steps:**
1. Double-click or click the first vertex to close and complete the polygon.
2. Observe the map.

**Expected Result:**
- The live area indicator is no longer visible.
- The "New Area" panel appears showing the final area value in the panel preview.

---

### TC-UI-AREA-CALC-005 — Live area value is a reasonable approximation

**Type:** UI
**Priority:** Low

**Preconditions:**
- A polygon with a known approximate area (e.g., a square of known dimensions) is drawn.

**Steps:**
1. Draw a polygon approximating a known shape.
2. Note the live area value shown during drawing.
3. Complete the polygon and note the value in the "New Area" panel.
4. Save the area and compare `areaKm2` in the map popup against the live value.

**Expected Result:**
- The live value shown during drawing is close to the saved `areaKm2`.
- Small differences are expected: the frontend uses a spherical approximation while the
  backend calculates on the WGS-84 spheroid. These differences are informational; the
  saved `areaKm2` from the backend is authoritative.

---

### TC-UI-AREA-EDIT-001 — Edit panel shows live area during editing

**Type:** UI
**Priority:** Medium

**Preconditions:**
- At least one Area exists.

**Steps:**
1. Click an Area polygon to open its popup.
2. Click "Edit" in the popup.
3. Observe the Edit Area panel.
4. Drag a vertex to reshape the polygon.
5. Observe the Edit Area panel.

**Expected Result:**
- When the Edit panel opens, an area value is displayed alongside the version number
  (e.g., "v1 · 0.245 km²").
- After dragging a vertex (step 4), the displayed area value updates to reflect the
  new shape.
- The area value is always 0–3 decimal places followed by " km²".
- The value need not exactly match the stored `areaKm2`; small differences between the
  spherical frontend approximation and the persisted spheroidal value are acceptable.

---

### TC-UI-AREA-EDIT-002 — Edit panel area value updates on each vertex drag

**Type:** UI
**Priority:** Low

**Preconditions:**
- An Area is open in the Edit panel and a vertex has been dragged once.

**Steps:**
1. Drag the same or a different vertex to a clearly different position.
2. Observe the area value in the Edit panel.

**Expected Result:**
- The area value changes after each vertex drag, reflecting the updated polygon shape.

---

### TC-UI-AREA-EDIT-003 — Cancelling an edit does not leave a stale area value

**Type:** UI
**Priority:** Low

**Preconditions:**
- An Area is open in the Edit panel. A vertex has been dragged so the displayed area
  differs from the original.

**Steps:**
1. Click "Cancel" in the Edit panel.
2. Immediately open the same Area for editing again.
3. Observe the area value in the Edit panel.

**Expected Result:**
- The Edit panel does not show a value from the previous editing session.
- The value shown corresponds to the Area's current persisted geometry.

---

## 6. Discrepancies and Observations

The following items describe gaps between the current prototype and the
documented design. QA Automation must be aware of which behaviours reflect
the intended final design versus current prototype limitations.

**Discrepancy 1 — Authentication is not implemented.**
The design requires JWT-based authentication via an API Gateway. The
prototype uses a hardcoded dev user and requires no `Authorization` header.
All API test cases currently omit authentication. When authentication is
added, every test case will need to supply a valid token. Test cases for
`401 UNAUTHENTICATED` and `403 ACCESS_DENIED` responses are out of scope
until then.

**Discrepancy 2 — Error response `details` field (partially resolved).**
The design specifies that error responses include a structured `details`
object with error-specific data. `VERSION_CONFLICT` responses now include a
populated `details` object containing `areaId`, `expectedVersion`, and
`currentVersion` (see TC-UPDATE-004). All other error responses continue to
return `"details": {}`. TC-AREA-012 does not assert on `details` content for
non-conflict errors; this remains the expected behaviour until further error
types are extended.

**Discrepancy 3 — Resolved.**
The design (Functional Requirements §3) states: "While drawing, the user
sees the current area." Live area preview during vertex placement is now
implemented. A floating indicator appears once the third vertex is placed and
updates as further vertices are added (TC-UI-AREA-CALC-001 through -005).
The edit panel also shows a live area value that updates on vertex drag
(TC-UI-AREA-EDIT-001 through -003).

**Discrepancy 4 — Deleted Area exclusion from viewport (resolved).**
The `DELETE` endpoint is now implemented. TC-DELETE-010 provides black-box API
coverage: after deletion, a `GET` with a bbox covering the Area's location
returns an empty array.

**Discrepancy 5 — TC-VP-004 workspace isolation requires a second
workspace.**
The dev profile seeds exactly one workspace. A fully isolated workspace
isolation test (Area in workspace A absent from workspace B results) requires
a second workspace with its own Areas. In the current prototype, querying a
nonexistent workspace ID returns `404`, which is a partial proxy for this
behaviour but does not cover the two-workspace scenario.

**Discrepancy 6 — Ring closure validation (resolved).**
The GeoJSON specification requires that a LinearRing's first and last
positions be identical. The original prototype did not enforce this
explicitly; JTS would silently close unclosed rings. Explicit ring closure
validation was added to resolve TC-AREA-008. The production API contract is:
an unclosed ring returns `400 INVALID_GEOMETRY`.

---

## 7. Recommended Automation Priority

**Phase 1 — Core contract (implement first)**

| # | ID | Reason |
|---|----|--------|
| 1 | TC-AREA-001 | Golden path: validates the create response contract end-to-end |
| 2 | TC-AREA-003 | Persistence: area survives and is found by viewport query |
| 3 | TC-VP-001 | Viewport query returns area in bounds — core spatial feature |
| 4 | TC-UPDATE-001 | Update golden path: response contract and version increment |
| 5 | TC-UPDATE-004 | OCC: version conflict returns structured 409 — core concurrency contract |
| 6 | TC-UI-002 | Full draw-and-save browser workflow |
| 7 | TC-UI-003 | Area visible after browser refresh — end-to-end persistence |
| 8 | TC-UI-AREA-CALC-001 | Live area indicator appears at 3rd vertex — design requirement |
| 9 | TC-UI-AREA-EDIT-001 | Edit panel shows live area and updates on drag |
| 10 | TC-UI-UPDATE-001 | Full edit-and-save browser workflow |
| 11 | TC-UI-UPDATE-002 | Edited Area visible after browser refresh |
| 12 | TC-DELETE-001 | Delete golden path: validates status, version, response contract |
| 13 | TC-DELETE-010 | Deleted Area excluded from viewport — spatial query regression |
| 14 | TC-RESTORE-001 | Restore golden path: DELETED Area returns to ACTIVE |
| 15 | TC-RESTORE-008 | Restore historical version of ACTIVE Area — validates full restore semantics |
| 16 | TC-HISTORY-001 | History list golden path: versions ordered DESC |
| 17 | TC-HISTORY-008 | Restore from history: new RESTORED version created; history unchanged |
| 18 | TC-UI-HISTORY-001 | Open History panel and view version list |
| 19 | TC-UI-HISTORY-003 | Restore from History panel updates the map |
| 20 | TC-WS-002 | AREA_CREATED event: connection and delivery golden path |
| 21 | TC-UI-WS-001 | Created Area appears in another browser without refresh |

**Phase 2 — Validation and isolation**

| # | ID | Reason |
|---|----|--------|
| 1 | TC-AREA-005 | 404 for unknown workspace on create |
| 2 | TC-AREA-006 | Interior rings rejected — explicit design constraint |
| 3 | TC-AREA-008 | Unclosed ring rejected — GeoJSON contract (resolved prototype gap) |
| 4 | TC-AREA-010 | Self-intersecting polygon rejected — database integrity |
| 5 | TC-VP-004 | Workspace isolation — data leakage would be a critical regression |
| 6 | TC-UPDATE-006 | Cross-workspace update returns 404 — workspace boundary must not leak data |
| 7 | TC-UPDATE-007 | Deleted area returns 409 AREA_DELETED |
| 8 | TC-UPDATE-010 | Invalid polygon on update rejected — database integrity |
| 9 | TC-AREA-012 | Error response shape — API consumer contract |
| 10 | TC-UI-UPDATE-003 | Stale edit informs user and reloads authoritative state |
| 11 | TC-DELETE-004 | OCC on delete: VERSION_CONFLICT details — core concurrency contract |
| 12 | TC-DELETE-005 | AREA_DELETED distinct from VERSION_CONFLICT — semantic correctness |
| 13 | TC-DELETE-006 | Concurrent deletes: exactly one succeeds — OCC correctness |
| 14 | TC-DELETE-008 | Cross-workspace delete returns 404 — workspace boundary must not leak data |
| 15 | TC-RESTORE-003 | Restore copies correct snapshot — data integrity |
| 16 | TC-UI-DELETE-001 | Delete via UI: basic delete workflow |
| 17 | TC-UI-DELETE-002 | Immediate restore: undo-delete workflow |
| 18 | TC-HISTORY-003 | History snapshot completeness: name, geometry, areaKm2, status, changeType |
| 19 | TC-HISTORY-005 | 404 for unknown area on history — boundary contract |
| 20 | TC-HISTORY-006 | Cross-workspace history returns 404 — workspace boundary must not leak data |
| 21 | TC-UI-HISTORY-005 | Stale restore from History informs user and reloads authoritative state |
| 22 | TC-WS-001 | WebSocket connection succeeds — prerequisite for all WS tests |
| 23 | TC-WS-006 | Workspace isolation: no cross-workspace event leakage |
| 24 | TC-WS-007 | Invalid geometry produces no event — transaction boundary |
| 25 | TC-WS-008 | Version conflict (rollback) produces no event — transaction boundary |
| 26 | TC-UI-WS-005 | Reconnect resynchronizes authoritative viewport state |
| 27 | TC-UI-WS-006 | Peer update does not destroy an edit in progress |

**Phase 3 — Remaining coverage**

| # | ID | Reason |
|---|----|--------|
| 1 | TC-AREA-002 | areaKm2 accuracy — requires tolerance decision |
| 2 | TC-AREA-004 | Invalid name (blank / whitespace) |
| 3 | TC-AREA-007 | Ring with fewer than 4 positions |
| 4 | TC-AREA-009 | Coordinate out of WGS84 range |
| 5 | TC-AREA-011 | Non-Polygon geometry type |
| 6 | TC-VP-002 | Area outside viewport not returned |
| 7 | TC-VP-003 | Partial intersection returned |
| 8 | TC-VP-005 | Empty viewport returns empty array |
| 9 | TC-VP-006 | Malformed bbox inputs |
| 10 | TC-VP-007 | 404 for unknown workspace on query |
| 11 | TC-UPDATE-002 | Version counter advances correctly across multiple updates |
| 12 | TC-UPDATE-003 | AreaVersion snapshot completeness |
| 13 | TC-UPDATE-005 | 404 for unknown areaId on update |
| 14 | TC-UPDATE-008 | 404 for unknown workspace on update |
| 15 | TC-UPDATE-009 | Blank name on update returns 400 |
| 16 | TC-UI-004 | Viewport loading on pan/zoom |
| 17 | TC-UI-001 | Application opens and map is interactive |
| 18 | TC-UI-005 | Save failure communicated to user |
| 19 | TC-DELETE-002 | AreaVersion snapshot completeness on delete |
| 20 | TC-DELETE-003 | Missing expectedVersion on delete returns 400 |
| 21 | TC-DELETE-007 | 404 for unknown areaId on delete |
| 22 | TC-DELETE-009 | 404 for unknown workspace on delete |
| 23 | TC-RESTORE-002 | AreaVersion snapshot completeness on restore |
| 24 | TC-RESTORE-004 | Version monotonicity on restore |
| 25 | TC-RESTORE-005 | Stale expectedVersion on restore returns 409 |
| 26 | TC-RESTORE-006 | Unknown target version returns 404 AREA_VERSION_NOT_FOUND |
| 27 | TC-RESTORE-007 | Unknown areaId on restore returns 404 AREA_VERSION_NOT_FOUND |
| 28 | TC-RESTORE-009 | Null expectedVersion on restore returns 400 |
| 29 | TC-UI-DELETE-003 | Dismiss restore panel removes deleted indicator |
| 30 | TC-HISTORY-002 | All change types present in history list |
| 31 | TC-HISTORY-004 | History available for DELETED Area |
| 32 | TC-HISTORY-007 | 404 for unknown workspace on history |
| 33 | TC-UI-HISTORY-002 | Version row click shows geometry preview on map |
| 34 | TC-UI-HISTORY-004 | Close History panel removes preview without modifying Area |
| 35 | TC-WS-003 | AREA_UPDATED event carries new version number |
| 36 | TC-WS-004 | AREA_DELETED event carries DELETED status |
| 37 | TC-WS-005 | AREA_RESTORED event carries ACTIVE status and incremented version |
| 38 | TC-UI-WS-002 | Updated Area is refreshed in another browser |
| 39 | TC-UI-WS-003 | Deleted Area disappears in another browser |
| 40 | TC-UI-WS-004 | Restored Area reappears in another browser |
