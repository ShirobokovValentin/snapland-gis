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
- Map rendering and polygon drawing in the browser
- Area persistence and reload

**Explicitly out of scope (not yet implemented):**
- Authentication and authorization
- Area update, delete, and restore
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

**Discrepancy 2 — Error response `details` field is always empty.**
The design specifies that error responses include a structured `details`
object with error-specific data (e.g., `currentVersion` and `areaId` on a
version conflict). The prototype always returns `"details": {}`. TC-AREA-012
does not assert on `details` content. When OCC and other operations are
implemented, `details` assertions should be added.

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
| 4 | TC-UI-002 | Full draw-and-save browser workflow |
| 5 | TC-UI-003 | Area visible after browser refresh — end-to-end persistence |

**Phase 2 — Validation and isolation**

| # | ID | Reason |
|---|----|--------|
| 6 | TC-AREA-005 | 404 for unknown workspace on create |
| 7 | TC-AREA-006 | Interior rings rejected — explicit design constraint |
| 8 | TC-AREA-008 | Unclosed ring rejected — GeoJSON contract (resolved prototype gap) |
| 9 | TC-AREA-010 | Self-intersecting polygon rejected — database integrity |
| 10 | TC-VP-004 | Workspace isolation — data leakage would be a critical regression |
| 11 | TC-AREA-012 | Error response shape — API consumer contract |

**Phase 3 — Remaining coverage**

| # | ID | Reason |
|---|----|--------|
| 12 | TC-AREA-002 | areaKm2 accuracy — requires tolerance decision |
| 13 | TC-AREA-004 | Invalid name (blank / whitespace) |
| 14 | TC-AREA-007 | Ring with fewer than 4 positions |
| 15 | TC-AREA-009 | Coordinate out of WGS84 range |
| 16 | TC-AREA-011 | Non-Polygon geometry type |
| 17 | TC-VP-002 | Area outside viewport not returned |
| 18 | TC-VP-003 | Partial intersection returned |
| 19 | TC-VP-005 | Empty viewport returns empty array |
| 20 | TC-VP-006 | Malformed bbox inputs |
| 21 | TC-VP-007 | 404 for unknown workspace on query |
| 22 | TC-UI-004 | Viewport loading on pan/zoom |
| 23 | TC-UI-001 | Application opens and map is interactive |
| 24 | TC-UI-005 | Save failure communicated to user |
