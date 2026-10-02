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
- Map rendering and polygon drawing in the browser
- Area editing in the browser (rename and reshape)
- Area persistence and reload

**Explicitly out of scope (not yet implemented):**
- Authentication and authorization
- Area delete and restore
- Version history API
- WebSocket / real-time collaboration
- Redis and presence
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

**Discrepancy 3 — Client-side area preview appears after polygon completion,
not during drawing.**
The design (Functional Requirements §3) states: "While drawing, the user
sees the current area." The prototype displays the area only after the
polygon is completed (TC-UI-002 step 5). This is a known prototype
limitation; live preview during vertex placement is not yet implemented.

**Discrepancy 4 — Deleted Area exclusion from viewport cannot be black-box
tested.**
The design requires that `DELETED` Areas are excluded from viewport query
results. The prototype enforces this at the data level, but no `DELETE`
endpoint is implemented. A black-box API test for this behaviour is deferred
until the delete operation is available.

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
| 8 | TC-UI-UPDATE-001 | Full edit-and-save browser workflow |
| 9 | TC-UI-UPDATE-002 | Edited Area visible after browser refresh |

**Phase 2 — Validation and isolation**

| # | ID | Reason |
|---|----|--------|
| 10 | TC-AREA-005 | 404 for unknown workspace on create |
| 11 | TC-AREA-006 | Interior rings rejected — explicit design constraint |
| 12 | TC-AREA-008 | Unclosed ring rejected — GeoJSON contract (resolved prototype gap) |
| 13 | TC-AREA-010 | Self-intersecting polygon rejected — database integrity |
| 14 | TC-VP-004 | Workspace isolation — data leakage would be a critical regression |
| 15 | TC-UPDATE-006 | Cross-workspace update returns 404 — workspace boundary must not leak data |
| 16 | TC-UPDATE-007 | Deleted area returns 409 AREA_DELETED |
| 17 | TC-UPDATE-010 | Invalid polygon on update rejected — database integrity |
| 18 | TC-AREA-012 | Error response shape — API consumer contract |
| 19 | TC-UI-UPDATE-003 | Stale edit informs user and reloads authoritative state |

**Phase 3 — Remaining coverage**

| # | ID | Reason |
|---|----|--------|
| 20 | TC-AREA-002 | areaKm2 accuracy — requires tolerance decision |
| 21 | TC-AREA-004 | Invalid name (blank / whitespace) |
| 22 | TC-AREA-007 | Ring with fewer than 4 positions |
| 23 | TC-AREA-009 | Coordinate out of WGS84 range |
| 24 | TC-AREA-011 | Non-Polygon geometry type |
| 25 | TC-VP-002 | Area outside viewport not returned |
| 26 | TC-VP-003 | Partial intersection returned |
| 27 | TC-VP-005 | Empty viewport returns empty array |
| 28 | TC-VP-006 | Malformed bbox inputs |
| 29 | TC-VP-007 | 404 for unknown workspace on query |
| 30 | TC-UPDATE-002 | Version counter advances correctly across multiple updates |
| 31 | TC-UPDATE-003 | AreaVersion snapshot completeness |
| 32 | TC-UPDATE-005 | 404 for unknown areaId on update |
| 33 | TC-UPDATE-008 | 404 for unknown workspace on update |
| 34 | TC-UPDATE-009 | Blank name on update returns 400 |
| 35 | TC-UI-004 | Viewport loading on pan/zoom |
| 36 | TC-UI-001 | Application opens and map is interactive |
| 37 | TC-UI-005 | Save failure communicated to user |
