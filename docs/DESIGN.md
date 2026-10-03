# GIS System Design

## Requirements

### Functional Requirements

1.  User Authentication The user can authenticate. The system manages
    the user session.

2.  Map Display the standard OpenStreetMap map. Display the
    govmap.gov.il satellite layer. Switch between the standard and
    satellite map layers. Drawn objects retain their correct position
    when switching layers.

3.  Drawing Areas The user can draw polygons on the map. The user can
    save a polygon. The following data is stored for a polygon: name
    geometry area_km2 The system calculates the polygon area in km².
    While drawing, the user sees the current area.

4.  Area Search Retrieve all Areas whose geometry intersects the user's
    current Viewport.

5.  Real-time Collaboration The user sees saved changes made by other
    users in real time. Real-time notifications are sent after a change
    has been successfully saved. The user sees active users on the map.
    Multiple users can draw simultaneously. Multiple users can edit
    existing Areas.

6.  Versioning The system stores Area versions. The user can retrieve
    the Area change history.

7.  Conflict Resolution The system detects conflicts when multiple users
    modify the same Area concurrently. The system resolves such
    conflicts according to a defined strategy.

8.  Deletion / Retention Area deletion is implemented as a soft delete.
    Data is retained according to the retention policy.

9.  Fallback When WebSocket is unavailable, the user can still work with
    the system in a limited mode.

### Non-Functional Requirements

#### Performance & Scalability

-   Support 10,000+ polygons in a single map region.
-   Fast polygon lookup within the current Viewport.
-   The system must efficiently handle large volumes of spatial data.
-   Frequently accessed geographic data must be returned quickly.

#### Real-time performance

-   Changes made by one user must appear to other users with minimal
    latency.
-   Real-time updates must support multiple concurrent users.
-   Real-time collaboration applies to persistent changes after the
    state has been successfully saved.
-   Committed Area update propagation latency: p95 \< 200 ms.

#### Reliability

-   The system must continue operating when the WebSocket connection is
    lost.
-   A request timeout must not cause the system to hang.
-   Health checks must be provided.
-   Errors must be logged and monitored. \### Security
-   WebSocket connections must be authenticated.
-   All user inputs must be validated and sanitized.
-   A CORS policy must be configured.
-   Users must not be able to perform operations without the required
    permissions. \### Abuse Protection
-   Area Mutations (Create, Update, Delete, Restore) \<= 50
    requests/min/user

#### Data Integrity

Invalid coordinates must not be persisted. Geometrically invalid
polygons must not be persisted. Concurrent modifications must not cause
data corruption or silent data loss. \### Auditability All significant
user actions must be recorded in the audit log. The history must make it
possible to determine: who / what / when. \### Maintainability /
Operability The database schema must support migrations. The system must
expose health endpoints. Error logging and monitoring must be provided.

## Glossary

| Term | Definition |
|---|---|
| **User** | An authenticated user of the system. |
| **Workspace** | A logical space/project in which users collaborate on Areas. |
| **WorkspaceMember** | The relationship between a User and a Workspace. Defines membership and the user's role (`OWNER`, `EDITOR`, `VIEWER`). |
| **Area** | The current state of a drawn geographic area. Used for primary read and spatial queries. |
| **AreaVersion** | An immutable snapshot of an Area state at a specific version. Used for history, audit, and restore. |
| **Area.current_version** | The current Area version number. Also used for Optimistic Concurrency Control. |
| **Area.status** | The current logical state of an Area: `ACTIVE` or `DELETED`. |
| **Area Mutation** | An operation that changes Area state: Create, Update, Delete, or Restore. |
| **Soft Delete** | Logical deletion of an Area by creating a new AreaVersion with `status = DELETED`, without physically deleting the Area or its history. |
| **Restore** | Creation of a new AreaVersion based on a historical AreaVersion and making that state the current Area state. |
| **Retention Policy** | Rules defining how long and under what conditions historical data and deleted Areas are retained. |
| **geometry** | The geometric shape of an Area, stored in PostGIS as `geometry(POLYGON, 4326)`. |
| **area_km2** | The calculated Area size in square kilometers. |
| **GeoJSON** | The format used to transfer geographic geometry between the GIS API Service and Frontend. |
| **SRID 4326 / EPSG:4326** | The WGS 84 coordinate reference system, in which coordinates are represented as longitude/latitude. |
| **Viewport** | The rectangular map area currently visible to the user. |
| **Bounding Box / BBox** | A Viewport represented by four coordinates: `minLon`, `minLat`, `maxLon`, `maxLat`. |
| **ST_Intersects** | A PostGIS spatial predicate that determines whether two geometries intersect. In this system, it is used in particular to find Areas inside or intersecting the Viewport boundary. |
| **GiST Spatial Index** | A spatial index in PostgreSQL/PostGIS used to accelerate geometry lookup by spatial location. |
| **Frontend** | The client web application that displays the map, Areas, and user interface. |
| **Leaflet.js** | The client-side GIS/map library used to display the map, layers, and geometry. |
| **Tile Provider** | An external service that provides map/satellite tiles to the Frontend. |
| **Map Tile** | A small fragment of the base map or satellite layer loaded by the Frontend directly from the Tile Provider. |
| **OpenStreetMap** | The provider/source of the standard map layer in the current design. |
| **govmap.gov.il** | The satellite imagery source in the current design. |
| **API Gateway** | A single entry point for REST and WebSocket traffic. Responsible for Authentication, Rate Limiting, and Routing to the GIS API Service or Realtime Gateway. |
| **GIS API Service** | The backend service implementing Area and Workspace business logic, spatial queries, versioning, and persistence. |
| **PostgreSQL + PostGIS** | The system's primary persistent storage and Source of Truth. |
| **PostGIS** | A PostgreSQL GIS extension providing spatial data types, functions, and indexes. |
| **Source of Truth** | The authoritative system state. For Areas, this is PostgreSQL + PostGIS, not Redis or Frontend state. |
| **Realtime Gateway** | A component that maintains WebSocket connections and delivers real-time events to connected users. |
| **WebSocket** | A persistent bidirectional connection between the Frontend and Realtime Gateway for real-time notifications and presence. |
| **Workspace Subscription** | Realtime Gateway routing state that determines which WebSocket connections receive events for a Workspace. |
| **Active User** | A User with at least one active WebSocket connection in a Workspace. |
| **Presence Store** | Ephemeral state in Redis containing information about active user WebSocket connections in a Workspace. |
| **Redis** | In-memory infrastructure used for Redis Pub/Sub and the Presence Store. |
| **Redis Pub/Sub** | A mechanism for distributing events among multiple GIS API Service / Realtime Gateway instances. |
| **AREA_CREATED** | An event published after a new Area has been successfully created and committed. |
| **AREA_UPDATED** | An event published after an Area change has been successfully committed. |
| **AREA_DELETED** | An event published after an Area soft delete has been successfully committed. |
| **AREA_RESTORED** | An event published after a historical AreaVersion has been restored as the new current Area state. |
| **Optimistic Concurrency Control (OCC)** | A strategy for detecting concurrent modifications by comparing `expectedVersion` with `Area.current_version`. |
| **expectedVersion** | The Area version that the Frontend considers current when performing a mutation. |
| **409 Conflict** | The HTTP response returned when `expectedVersion` does not match `Area.current_version`. |
| **DB Transaction** | An atomic PostgreSQL operation within which Area and AreaVersion are changed consistently. |
| **Transactional Outbox** | A reliable event-publication pattern: the Area change and Outbox event are written in the same DB Transaction, after which a separate publisher sends the event. |
| **Outbox Publisher** | A component that reads unpublished Outbox events and publishes them to the messaging infrastructure. |
| **Resynchronization** | Reloading the current state from the GIS API Service after reconnect to recover from events missed during a WebSocket disconnect. |
| **Fallback / Degraded Mode** | Operation through the REST API when WebSocket is unavailable, without real-time notifications/presence. |
| **IdP (Identity Provider)** | An authentication component that issues credentials/tokens to the user. |
| **JWT** | A token used to carry authentication information in REST/WebSocket interactions. |
| **Rate Limiting** | A limit on the frequency of user requests/actions. Applied at the API Gateway. Exceeding the limit returns `429 Too Many Requests`. |
| **Debounce** | A mechanism for limiting Viewport request frequency: a request is sent after a short pause following pan/zoom rather than on every map movement. |

> **Prototype Implementation Note:** Esri World Imagery is used as the temporary satellite imagery provider for the prototype. GovMap remains the target provider from the requirements. The temporary provider configuration is isolated from application GIS state and business logic so that replacing the base-map integration later has minimal impact on Area functionality. GovMap integration research is documented in `docs/research/GOVMAP_SATELLITE_RESEARCH.md`.

> **Prototype Authentication Note:** The full architecture places authentication at the API Gateway (an external IdP issues JWTs; the Gateway validates them before forwarding requests). In this prototype there is no API Gateway, so the GIS API Service acts directly as a JWT Resource Server: it validates and consumes every incoming JWT itself using Spring Security OAuth2 Resource Server. The GIS API Service does not trust or blindly forward identity asserted by any intermediary — it validates the JWT signature and claims on every request.
>
> Prototype registration (`POST /auth/register`) and login (`POST /auth/login`) are implemented directly in the GIS API Service. Registration requires only a name and email address — there is no password, no email verification, and no magic link. This is not production-quality authentication; it is intentionally simplified for the prototype and is intended to be replaced by a real IdP. All registered users are added as EDITORs of the single configured prototype Workspace (`app.prototype.workspace-id`).
>
> WebSocket STOMP CONNECT frames are authenticated using the JWT carried in the `Authorization: Bearer` header. The HTTP upgrade endpoint (`/ws/**`) is permit-all because the browser WebSocket API cannot send custom HTTP headers during the handshake; authentication is enforced at the STOMP protocol level via `JwtChannelInterceptor`.
>
> A `DevTokenController` (active only under the `dev` Spring profile) issues JWTs for the seeded dev user without credential verification; it is development scaffolding and must not be deployed to production.

> **Prototype Rate Limiting Note:** The full architecture applies rate limiting at the API Gateway, which can enforce limits across multiple backend instances using shared state. In this prototype there is no API Gateway, so a fixed-window per-user drawing action rate limiter is implemented directly in the GIS API Service as a Spring MVC `HandlerInterceptor` (`DrawingRateLimiter`, `DrawingRateLimitInterceptor`). State is held in a `ConcurrentHashMap` within a single JVM instance and is lost on application restart.
>
> The limiter enforces a maximum of `app.rate-limit.drawing-actions.max-actions` mutations (default: 50) per user per `app.rate-limit.drawing-actions.window` (default: `PT1M`). Covered mutation endpoints: `POST /workspaces/*/areas`, `PUT /workspaces/*/areas/*`, `DELETE /workspaces/*/areas/*`, `POST /workspaces/*/areas/*/versions/*/restore`. Read requests (`GET`) are not counted. Requests beyond the quota are rejected with `429 Too Many Requests` and a `Retry-After` header (ceiling seconds until the current window expires, minimum 1). Failed authenticated mutations (e.g., `400 INVALID_GEOMETRY`) count against the quota. Unauthenticated requests (`401`) consume no quota. The counter stays at `maxActions` for the remainder of the window — rejected requests do not increment it. A new window starts on the first request after the current window expires.
>
> Memory is proportional to the number of distinct users who have made mutation attempts in any window. Opportunistic cleanup runs every 128 operations to remove entries for expired windows. This is sufficient for a prototype; a production deployment should move rate limiting to the API Gateway with a shared backing store (e.g., Redis sliding window).

## Domain Entities

User --- System user {id, name, email, created_at}

Area --- Drawn geographic area/polygon {id, workspace_id, name,
geometry: geometry(POLYGON, 4326), area_km2, status: ACTIVE \| DELETED,
current_version, created_by, created_at}

AreaVersion --- Area change history {id, area_id, version, name, status:
ACTIVE \| DELETED, geometry: geometry(POLYGON, 4326), area_km2,
created_by, created_at, change_type: CREATED \| UPDATED \| DELETED \|
RESTORED}

Workspace --- Shared map/project on which users collaborate {id, name,
created_by, created_at}

WorkspaceMember --- User-to-project membership and permissions {id,
workspace_id, user_id, role}

## High-Level Architecture

````
            ┌───────────────┐
            │ Tile Provider │
            │ Map/Satellite │
            └───────▲───────┘
       ┌────────────┴────────────┐ tiles
┌──────▼───────┐          ┌──────▼───────┐
│   User A     │          │   User B     │
│ Web Browser  │          │ Web Browser  │
│ Leaflet.js   │          │ Leaflet.js   │
└──────┬───────┘          └──────┬───────┘
       │     REST / WebSocket    │
       └───────────┬─────────────┘
            ┌──────▼─────────┐
            │   API Gateway  │
            │                │
            │ Authentication │
            │ Rate Limiting  │
            │ Routing        │
            └──────┬─────────┘
        REST       │   WebSocket
        ┌──────────┴───────────────────────┐
┌───────▼──────────────┐       ┌───────────▼─────────────┐
│ GIS API Service      │       │ Realtime Gateway        │
│                      │       │                         │
│ Authorization        │       │                         │
│ Geometry Validation  │       │                         │
│ Business Logic       │       │                         │
│ Area Calculation     │       │ WebSocket Connections   │
│ Versioning           │       │ Workspace Subscriptions │
│ Concurrency Control  │       │ Presence Management     │
└───────┬──────────────┘       └───────────▲─────────────┘
        │                            events│
        │           events         ┌───────┴────────┐
        ├─────────────────────────►│     Redis      │
        │                          │                │
        │                          │ Pub/Sub        │
        │ read / write             │ Presence Store │
┌───────▼───────────────┐          └────────────────┘
│ PostgreSQL + PostGIS  │
│   Source of Truth     │
│ User                  │
│ Workspace             │
│ WorkspaceMember       │
│ Area                  │
│ AreaVersion           │
│ GiST Spatial Index    │
└───────────────────────┘

````

### Flow #1: Load Areas for Viewport

#### Goal

Retrieve all active Areas whose geometry intersects the user's current
Viewport.

#### Actors

-   User
-   Frontend
-   API Gateway
-   GIS API Service
-   PostgreSQL + PostGIS

#### Preconditions

-   Workspace exists.
-   Map is opened.

#### Main Flow

1.  User -\> Frontend: Opens, moves, or zooms the map.

2.  Frontend: Calculates the current Viewport: minLon, minLat, maxLon,
    maxLat.

3.  Frontend -\> API Gateway: GET
    /workspaces/{workspaceId}/areas?bbox=...

4.  API Gateway: Authenticates the request. Applies Rate Limiting.
    Routes the request to GIS API Service.

5.  API Gateway -\> GIS API Service: Forwards the authenticated request.

6.  GIS API Service: Authorizes User access to the Workspace.

7.  GIS API Service -\> PostgreSQL + PostGIS: Executes a spatial query
    against Area:

    -   workspace_id = workspaceId
    -   status = ACTIVE
    -   ST_Intersects(geometry, Viewport)

8.  PostgreSQL + PostGIS: Uses the GiST Spatial Index to find matching
    Areas.

9.  PostgreSQL + PostGIS -\> GIS API Service: Returns matching Areas.

10. GIS API Service -\> API Gateway: Returns matching Areas as GeoJSON.

11. API Gateway -\> Frontend: Returns the response.

12. Frontend: Renders the Areas on the map.

#### Alternative Flows

A1. Authentication fails Return 401 Unauthorized.

A2. Rate Limit is exceeded Return 429 Too Many Requests.

A3. User has no access to the Workspace Return 403 Forbidden.

A4. No Areas intersect the Viewport Return an empty collection.

A5. User moves or zooms the map again Frontend applies Debounce and
sends a new Viewport request.

#### Result

-   User sees all ACTIVE Areas intersecting the current Viewport.

### Flow #2: Create Area

#### Goal

Create a new Area, persist its current state and the first immutable
AreaVersion, then notify the other connected users.

#### Actors

-   User
-   Frontend
-   API Gateway
-   GIS API Service
-   PostgreSQL + PostGIS
-   Redis
-   Realtime Gateway
-   Other Users

#### Preconditions

-   User has drawn a polygon in the Frontend.

#### Main Flow

1.  User -\> Frontend: Enters the Area name and saves the polygon.

2.  Frontend -\> API Gateway: POST /workspaces/{workspaceId}/areas

    { name, geometry }

3.  API Gateway: Authenticates the request. Applies Rate Limiting.
    Routes the request to GIS API Service.

4.  API Gateway -\> GIS API Service: Forwards the authenticated request.

5.  GIS API Service: Authorizes User access to the Workspace with EDIT
    permission.

6.  GIS API Service: Validates the geometry and coordinates.

7.  GIS API Service: Calculates area_km2.

8.  GIS API Service -\> PostgreSQL + PostGIS: Starts DB Transaction.

9.  GIS API Service -\> PostgreSQL + PostGIS: Creates Area:

    -   name = name
    -   geometry = geometry
    -   area_km2 = calculated area
    -   status = ACTIVE
    -   current_version = 1

10. GIS API Service -\> PostgreSQL + PostGIS: Creates immutable
    AreaVersion:

    -   version = 1
    -   name = name
    -   geometry = geometry
    -   area_km2 = calculated area
    -   status = ACTIVE 
    -   change_type = CREATED

11. PostgreSQL + PostGIS: Commits DB Transaction.

12. GIS API Service -\> Redis: Publishes AREA_CREATED using Redis
    Pub/Sub.

13. Redis -\> Realtime Gateway: Delivers AREA_CREATED.

14. Realtime Gateway -\> Other Users: Sends AREA_CREATED through
    WebSocket.

#### Alternative Flows

A1. Authentication fails Return 401 Unauthorized.

A2. Rate Limit is exceeded Return 429 Too Many Requests.

A3. User has no EDIT permission Return 403 Forbidden.

A4. Geometry or coordinates are invalid Return 400 Bad Request.

A5. DB Transaction fails Roll back DB Transaction. AREA_CREATED is not
published.

A6. Redis or Realtime Gateway is unavailable DB Transaction remains
committed. Other Users obtain the current state during subsequent
Resynchronization.

#### Result

-   Area contains the current state.
-   Immutable AreaVersion version = 1 exists.
-   Other Users receive AREA_CREATED after the DB Transaction is
    committed.

### Flow #3: Update Area

#### Goal

Modify an existing Area, persist a new immutable AreaVersion, and
prevent silent loss of concurrent changes.

#### Actors

-   User
-   Frontend
-   API Gateway
-   GIS API Service
-   PostgreSQL + PostGIS
-   Redis
-   Realtime Gateway
-   Other Users

#### Preconditions

-   Area exists.
-   Frontend knows Area.current_version.

#### Main Flow

1.  User -\> Frontend: Modifies the Area and saves the changes.

2.  Frontend -\> API Gateway: PUT /areas/{areaId}

    { expectedVersion: 5, name, geometry }

3.  API Gateway: Authenticates the request. Applies Rate Limiting.
    Routes the request to GIS API Service.

4.  API Gateway -\> GIS API Service: Forwards the authenticated request.

5.  GIS API Service: Authorizes User access to the Workspace with EDIT
    permission.

6.  GIS API Service: Validates the geometry and coordinates.

7.  GIS API Service: Calculates area_km2.

8.  GIS API Service -\> PostgreSQL + PostGIS: Starts DB Transaction.

9.  GIS API Service -\> PostgreSQL + PostGIS: Checks
    Area.current_version = expectedVersion.

10. GIS API Service -\> PostgreSQL + PostGIS: Creates immutable
    AreaVersion:

    -   version = expectedVersion + 1
    -   name = name
    -   geometry = geometry
    -   area_km2 = calculated area
    -   status = ACTIVE 
    -   change_type = UPDATED

11. GIS API Service -\> PostgreSQL + PostGIS: Updates Area:

    -   name = name
    -   geometry = geometry
    -   area_km2 = calculated area
    -   status = ACTIVE
    -   current_version = expectedVersion + 1

12. PostgreSQL + PostGIS: Commits DB Transaction.

13. GIS API Service -\> Redis: Publishes AREA_UPDATED using Redis
    Pub/Sub.

14. Redis -\> Realtime Gateway: Delivers AREA_UPDATED.

15. Realtime Gateway -\> Other Users: Sends AREA_UPDATED through
    WebSocket.

#### Alternative Flows

A1. Authentication fails Return 401 Unauthorized.

A2. Rate Limit is exceeded Return 429 Too Many Requests.

A3. User has no EDIT permission Return 403 Forbidden.

A4. Geometry or coordinates are invalid Return 400 Bad Request.

A5. Area.current_version != expectedVersion Roll back DB Transaction.
Return 409 Conflict.

A6. Area.status = DELETED Roll back DB Transaction. Reject the update.

A7. Redis or Realtime Gateway is unavailable DB Transaction remains
committed. Other Users obtain the current state during subsequent
Resynchronization.

#### Result

-   Area contains the new current state.
-   A new immutable AreaVersion exists.
-   Area.current_version is incremented.
-   Concurrent overwrite is prevented.
-   Other Users receive AREA_UPDATED after the DB Transaction is
    committed.

### Flow #4: Delete Area

#### Goal

Soft-delete an Area as a new immutable AreaVersion without deleting the
existing Area or its history.

#### Actors

-   User
-   Frontend
-   API Gateway
-   GIS API Service
-   PostgreSQL + PostGIS
-   Redis
-   Realtime Gateway
-   Other Users

#### Preconditions

-   Area exists.
-   Frontend knows Area.current_version.

#### Main Flow

1.  User -\> Frontend: Requests Area deletion.

2.  Frontend -\> API Gateway: DELETE /areas/{areaId}?expectedVersion=5

3.  API Gateway: Authenticates the request. Applies Rate Limiting.
    Routes the request to GIS API Service.

4.  API Gateway -\> GIS API Service: Forwards the authenticated request.

5.  GIS API Service: Authorizes User access to the Workspace with EDIT
    permission.

6.  GIS API Service -\> PostgreSQL + PostGIS: Starts DB Transaction.

7.  GIS API Service -\> PostgreSQL + PostGIS: Checks
    Area.current_version = expectedVersion.

8.  GIS API Service -\> PostgreSQL + PostGIS: Creates immutable
    AreaVersion:

    - version = expectedVersion + 1 
    - name = current name 
    - geometry = current geometry 
    - area_km2 = current area 
    - status = DELETED 
    - change_type = DELETED

9.  GIS API Service -\> PostgreSQL + PostGIS: Updates Area:

    -   status = DELETED
    -   current_version = expectedVersion + 1

10. PostgreSQL + PostGIS: Commits DB Transaction.

11. GIS API Service -\> Redis: Publishes AREA_DELETED using Redis
    Pub/Sub.

12. Redis -\> Realtime Gateway: Delivers AREA_DELETED.

13. Realtime Gateway -\> Other Users: Sends AREA_DELETED through
    WebSocket.

#### Alternative Flows

A1. Authentication fails Return 401 Unauthorized.

A2. Rate Limit is exceeded Return 429 Too Many Requests.

A3. User has no EDIT permission Return 403 Forbidden.

A4. Area.current_version != expectedVersion Roll back DB Transaction.
Return 409 Conflict.

A5. Area.status = DELETED Roll back DB Transaction. Return 409 Conflict
/ AREA_DELETED.

A6. Redis or Realtime Gateway is unavailable DB Transaction remains
committed. Other Users obtain the current state during subsequent
Resynchronization.

#### Result

-   Area.status = DELETED.
-   A new immutable AreaVersion with status = DELETED exists.
-   Area.current_version is incremented.
-   Historical data and geometry are preserved.
-   Other Users receive AREA_DELETED after the DB Transaction is
    committed.

### Flow #5: Get Area History

#### Goal

Retrieve the immutable state history of a specific Area.

#### Actors

-   User
-   Frontend
-   API Gateway
-   GIS API Service
-   PostgreSQL + PostGIS

#### Preconditions

-   Area exists.

#### Main Flow

1.  User -\> Frontend: Opens Area history.

2.  Frontend -\> API Gateway: GET /areas/{areaId}/versions

3.  API Gateway: Authenticates the request. Applies Rate Limiting.
    Routes the request to GIS API Service.

4.  API Gateway -\> GIS API Service: Forwards the authenticated request.

5.  GIS API Service: Authorizes User access to the Workspace.

6.  GIS API Service -\> PostgreSQL + PostGIS: Queries AreaVersion by
    area_id ordered by version DESC.

7.  PostgreSQL + PostGIS -\> GIS API Service: Returns matching
    AreaVersions.

8.  GIS API Service -\> API Gateway: Returns AreaVersions.

9.  API Gateway -\> Frontend: Returns the response.

10. User -\> Frontend: Selects a specific version.

11. Frontend -\> API Gateway: GET /areas/{areaId}/versions/{version}

12. API Gateway: Authenticates the request. Applies Rate Limiting.
    Routes the request to GIS API Service.

13. API Gateway -\> GIS API Service: Forwards the authenticated request.

14. GIS API Service: Authorizes User access to the Workspace.

15. GIS API Service -\> PostgreSQL + PostGIS: Loads the requested
    AreaVersion.

16. PostgreSQL + PostGIS -\> GIS API Service: Returns the requested
    AreaVersion.

17. GIS API Service -\> API Gateway: Returns historical geometry and
    metadata.

18. API Gateway -\> Frontend: Returns the response.

#### Alternative Flows

A1. Authentication fails Return 401 Unauthorized.

A2. Rate Limit is exceeded Return 429 Too Many Requests.

A3. User has no access to the Workspace Return 403 Forbidden.

A4. Area does not exist Return 404 Not Found.

A5. AreaVersion does not exist Return 404 Not Found.

#### Result

-   User can inspect immutable AreaVersions and see how the Area changed
    over time.

### Flow #6: Restore Area Version

#### Goal

Make a historical state the current Area state, by creating a new
immutable AreaVersion without modifying the existing history.

#### Actors

-   User
-   Frontend
-   API Gateway
-   GIS API Service
-   PostgreSQL + PostGIS
-   Redis
-   Realtime Gateway
-   Other Users

#### Preconditions

-   Area exists.
-   Frontend knows Area.current_version.

#### Main Flow

1.  User -\> Frontend: Selects an AreaVersion and requests Restore.

2.  Frontend -\> API Gateway: POST
    /areas/{areaId}/versions/{version}/restore

    { expectedVersion: 6 }

3.  API Gateway: Authenticates the request. Applies Rate Limiting.
    Routes the request to GIS API Service.

4.  API Gateway -\> GIS API Service: Forwards the authenticated request.

5.  GIS API Service: Authorizes User access to the Workspace with EDIT
    permission.

6.  GIS API Service -\> PostgreSQL + PostGIS: Loads the requested
    AreaVersion.

7.  GIS API Service -\> PostgreSQL + PostGIS: Starts DB Transaction.

8.  GIS API Service -\> PostgreSQL + PostGIS: Checks
    Area.current_version = expectedVersion.

9.  GIS API Service -\> PostgreSQL + PostGIS: Creates immutable
    AreaVersion:

    -   version = expectedVersion + 1
    -   name = restored name
    -   geometry = restored geometry
    -   area_km2 = restored area
    -   status = ACTIVE 
    -   change_type = RESTORED

10. GIS API Service -\> PostgreSQL + PostGIS: Updates Area:

    -   name = restored name
    -   geometry = restored geometry
    -   area_km2 = restored area
    -   status = ACTIVE
    -   current_version = expectedVersion + 1

11. PostgreSQL + PostGIS: Commits DB Transaction.

12. GIS API Service -\> Redis: Publishes AREA_RESTORED using Redis
    Pub/Sub.

13. Redis -\> Realtime Gateway: Delivers AREA_RESTORED.

14. Realtime Gateway -\> Other Users: Sends AREA_RESTORED through
    WebSocket.

#### Alternative Flows

A1. Authentication fails Return 401 Unauthorized.

A2. Rate Limit is exceeded Return 429 Too Many Requests.

A3. User has no EDIT permission Return 403 Forbidden.

A4. Requested AreaVersion does not exist Return 404 Not Found.

A5. Area.current_version != expectedVersion Roll back DB Transaction.
Return 409 Conflict.

A6. Redis or Realtime Gateway is unavailable DB Transaction remains
committed. Other Users obtain the current state during subsequent
Resynchronization.

#### Result

-   Area contains the restored state.
-   Existing AreaVersions remain unchanged.
-   A new immutable AreaVersion is created.
-   Area.current_version is incremented.
-   Other Users receive AREA_RESTORED after the DB Transaction is
    committed.

### Flow #7: Connect to Realtime Gateway

#### Goal

Establish an authenticated WebSocket connection and register the user as
active in the Workspace.

#### Actors

-   User
-   Frontend
-   API Gateway
-   Realtime Gateway
-   Redis
-   Other Users

#### Preconditions

-   Frontend has an authentication token.

#### Main Flow

1.  Frontend -\> API Gateway: Opens a WebSocket connection with
    authentication credentials.

2.  API Gateway: Authenticates the WebSocket connection. Applies Rate
    Limiting. Routes the connection to Realtime Gateway.

3.  API Gateway -\> Realtime Gateway: Forwards the authenticated
    WebSocket connection.

4.  Realtime Gateway: Authorizes User access to the Workspace.

5.  Realtime Gateway -\> Redis: Adds the user connection to the
    Workspace Presence Store.

6.  Realtime Gateway: Associates the WebSocket connection with the
    Workspace Subscription.

7.  Realtime Gateway -\> Other Users: Sends the presence update through
    WebSocket.

#### Alternative Flows

A1. Authentication fails Reject the WebSocket connection.

A2. Rate Limit is exceeded Reject the WebSocket connection.

A3. User has no access to the Workspace Reject the WebSocket connection.

A4. Redis is unavailable Apply the configured Degraded Mode strategy for
Presence Store.

#### Result

-   User has an authenticated WebSocket connection.
-   User is registered in the Workspace Presence Store.
-   User has a Workspace Subscription.
-   Other Users see the user as active.

### Flow #8: Disconnect from Realtime Gateway

#### Goal

Remove the WebSocket connection from the Workspace Presence Store and
update the list of active users.

#### Actors

-   User
-   Frontend
-   API Gateway
-   Realtime Gateway
-   Redis
-   Other Users

#### Preconditions

-   User has an active WebSocket connection.
-   User connection is registered in the Workspace Presence Store.

#### Main Flow

1.  Frontend: Closes the WebSocket connection.

2.  API Gateway: Propagates the WebSocket connection closure to Realtime
    Gateway.

3.  Realtime Gateway -\> Redis: Removes the user connection from the
    Workspace Presence Store.

4.  Realtime Gateway: Removes the WebSocket connection from the
    Workspace Subscription.

5.  Realtime Gateway -\> Other Users: Sends the presence update through
    WebSocket.

#### Alternative Flows

A1. WebSocket connection terminates unexpectedly Realtime Gateway
detects the connection loss and removes the user connection from the
Workspace Presence Store.

A2. Realtime Gateway does not detect connection loss immediately
Heartbeat timeout expires. The user connection is removed from the
Workspace Presence Store.

A3. User has multiple active WebSocket connections User remains an
Active User while at least one connection remains in the Workspace
Presence Store.

#### Result

-   The WebSocket connection is removed from the Workspace Presence
    Store.
-   The WebSocket connection is removed from the Workspace Subscription.
-   User is no longer shown as an Active User when no active connections
    remain.

### Flow #9: WebSocket Failure and Resynchronization

#### Goal

Allow the user to continue working when WebSocket is unavailable and
restore the current state after reconnect.

#### Actors

-   User
-   Frontend
-   API Gateway
-   GIS API Service
-   PostgreSQL + PostGIS
-   Realtime Gateway
-   Redis

#### Preconditions

-   User has authentication credentials.
-   WebSocket connection is unavailable or has been lost.

#### Main Flow

1.  Frontend: Detects WebSocket connection loss.

2.  Frontend: Marks real-time functionality as temporarily unavailable.

3.  User -\> Frontend: Continues normal operations.

4.  Frontend -\> API Gateway: Uses REST API for normal GIS operations.

5.  API Gateway: Authenticates REST requests. Applies Rate Limiting.
    Routes REST requests to GIS API Service.

6.  API Gateway -\> GIS API Service: Forwards authenticated REST
    requests.

7.  GIS API Service: Authorizes requests and processes normal GIS
    operations.

8.  GIS API Service -\> PostgreSQL + PostGIS: Reads and modifies the
    Source of Truth normally.

9.  Frontend -\> API Gateway: Attempts to re-establish the WebSocket
    connection.

10. API Gateway: Authenticates the WebSocket connection. Applies Rate
    Limiting. Routes the connection to Realtime Gateway.

11. API Gateway -\> Realtime Gateway: Forwards the authenticated
    WebSocket connection.

12. Realtime Gateway: Authorizes User access to the Workspace.

13. Realtime Gateway -\> Redis: Adds the user connection to the
    Workspace Presence Store.

14. Realtime Gateway: Associates the WebSocket connection with the
    Workspace Subscription.

15. Frontend -\> API Gateway: Requests Areas for the current Viewport.

16. API Gateway: Authenticates the request. Applies Rate Limiting.
    Routes the request to GIS API Service.

17. API Gateway -\> GIS API Service: Forwards the authenticated request.

18. GIS API Service: Authorizes User access to the Workspace.

19. GIS API Service -\> PostgreSQL + PostGIS: Reads the current Area
    states.

20. PostgreSQL + PostGIS -\> GIS API Service: Returns the current Area
    states.

21. GIS API Service -\> API Gateway: Returns the current Area states.

22. API Gateway -\> Frontend: Returns the response.

23. Frontend: Replaces potentially stale local state with the current
    state.

#### Alternative Flows

A1. WebSocket remains unavailable User continues working through the
REST API without real-time updates.

A2. Another User modified an Area while WebSocket was unavailable
Frontend receives the latest Area.current_version during
Resynchronization.

A3. User attempts to save a stale Area before Resynchronization
Area.current_version != expectedVersion. Return 409 Conflict.

A4. Authentication fails during reconnect Reject the WebSocket
connection.

A5. Rate Limit is exceeded during reconnect Reject the WebSocket
connection.

#### Result

-   Core GIS functionality remains available without WebSocket.
-   Missed Redis Pub/Sub events do not cause permanent inconsistency.
-   PostgreSQL + PostGIS remains the Source of Truth.
-   Frontend eventually resynchronizes with the current state.

### Flow #10: Alternative Event Publication with Transactional Outbox

#### Goal

Guarantee that a committed Area change is not lost between the DB
Transaction and event publication to Redis Pub/Sub.

#### Actors

-   GIS API Service
-   PostgreSQL + PostGIS
-   Outbox Publisher
-   Redis
-   Realtime Gateway
-   Other Users

#### Preconditions

-   Transactional Outbox is enabled.
-   An Area Mutation is being processed.

#### Main Flow

1.  GIS API Service -\> PostgreSQL + PostGIS: Starts DB Transaction.

2.  GIS API Service -\> PostgreSQL + PostGIS: Creates or updates Area
    and AreaVersion.

3.  GIS API Service -\> PostgreSQL + PostGIS: Creates the corresponding
    Outbox event:

    -   event_type
    -   workspace_id
    -   area_id
    -   version
    -   created_at

4.  PostgreSQL + PostGIS: Commits DB Transaction.

5.  Outbox Publisher -\> PostgreSQL + PostGIS: Reads unpublished Outbox
    events.

6.  Outbox Publisher -\> Redis: Publishes the event using Redis Pub/Sub.

7.  Redis -\> Realtime Gateway: Delivers the event.

8.  Realtime Gateway -\> Other Users: Sends the event through WebSocket.

9.  Outbox Publisher -\> PostgreSQL + PostGIS: Marks the Outbox event as
    published.

#### Alternative Flows

A1. DB Transaction fails Roll back Area, AreaVersion, and Outbox event.

A2. Redis is unavailable Outbox event remains unpublished. Outbox
Publisher retries later.

A3. Outbox Publisher crashes after publishing but before marking the
event as published Event may be published again. Consumers must tolerate
duplicate events.

A4. Realtime Gateway is unavailable after Redis publication Redis
Pub/Sub does not retain the event for later delivery. Other Users
recover the current state through Resynchronization.

#### Result

-   Every committed Area Mutation has a corresponding durable Outbox
    event.
-   Temporary failure between PostgreSQL + PostGIS and Redis does not
    permanently lose the event.
-   Redis Pub/Sub remains non-durable.
-   Missed WebSocket events are recovered through Resynchronization.

## Data Model & GIS Storage

### Data Model & Database Constraints

User 1-N WorkspaceMember N-1 Workspace 1-N Area 1-N AreaVersion

```
User
  ├──────── created_by ────────► Workspace
  ├──────── created_by ────────► Area
  └──────── created_by ────────► AreaVersion
```

#### USER

`id` PK 

`name` 

`email` 

`created_at`

`PRIMARY KEY (id)`

`UNIQUE (email)`

`NOT NULL: - email - created_at`

#### WORKSPACE

id PK name created_by FK -\> USER.id created_at

PRIMARY KEY (id)

FOREIGN KEY (created_by) REFERENCES users(id)

NOT NULL: - name - created_by - created_at

ON DELETE RESTRICT

#### WORKSPACE_MEMBER

id PK workspace_id FK -\> WORKSPACE.id user_id FK -\> USER.id role

UNIQUE(workspace_id, user_id)

FOREIGN KEY (workspace_id) REFERENCES workspace(id)

FOREIGN KEY (user_id) REFERENCES users(id)

#### AREA

id PK workspace_id FK -\> WORKSPACE.id

name geometry geometry(POLYGON, 4326) area_km2 status current_version

    created_by FK -\> USER.id created_at
    PRIMARY KEY (id)
    FOREIGN KEY (workspace_id) REFERENCES workspace(id)
    FOREIGN KEY (created_by) REFERENCES users(id)

#### AREA_VERSION

id PK area_id FK -\> AREA.id

version name geometry geometry(POLYGON, 4326) area_km2 status
change_type

created_by FK -\> USER.id created_at

UNIQUE(area_id, version)

PRIMARY KEY (id)

FOREIGN KEY (area_id) REFERENCES area(id)

FOREIGN KEY (created_by) REFERENCES users(id)

CHECK (version \>= 1) CHECK (status IN ('ACTIVE', 'DELETED')) CHECK
(change_type IN ('CREATED', 'UPDATED', 'DELETED', 'RESTORED')) geometry
geometry(POLYGON, 4326) CHECK (ST_IsValid(geometry)) CHECK (area_km2 \>=
0) UNIQUE (area_id, version)

Invariant Enforced By User.email unique Database A User appears only
once in a Workspace Database Area.status ∈ {ACTIVE, DELETED} Database
current_version \>= 1 Database AreaVersion.version \>= 1 Database
(area_id, version) unique Database Geometry = POLYGON, 4326 Database
Geometry valid GIS API Service + Database area_km2 \>= 0 Database
area_km2 corresponds to geometry GIS API Service AreaVersion immutable
GIS API Service / DB permissions Area corresponds to
AreaVersion(current_version) GIS API Service + DB Transaction A
concurrent update does not overwrite newer state OCC + DB Transaction

Database constraints protect persistent data integrity independently of
application-level validation.

Key constraints:

-   User.email is unique.
-   WorkspaceMember is unique by (workspace_id, user_id).
-   Area.current_version \>= 1.
-   AreaVersion.version \>= 1.
-   AreaVersion is unique by (area_id, version).
-   Area.status and AreaVersion.status are limited to ACTIVE or DELETED.
-   Area.geometry and AreaVersion.geometry use POLYGON with SRID 4326.
-   Persisted geometry must be valid.
-   area_km2 must be non-negative.

AreaVersion is append-only. Existing AreaVersion records are not
modified by normal application operations.

The following invariants are maintained by GIS API Service within a DB
Transaction:

-   Area current state corresponds to AreaVersion identified by
    Area.current_version.
-   area_km2 corresponds to the persisted geometry.
-   Area and its new AreaVersion are persisted atomically.
-   concurrent Area modifications are protected by Optimistic
    Concurrency Control.

### Area / AreaVersion Storage Model

Current-state reads

SELECT \* FROM area WHERE workspace_id = ? AND status = 'ACTIVE' AND
ST_Intersects(geometry, ?);

For Area.current_version = N:

Area current state must correspond to AreaVersion(area_id = Area.id,
version = N).

### Geometry Storage

POLYGON

POLYGON(( 35.50 32.70, 35.55 32.70, 35.55 32.75, 35.50 32.75, 35.50
32.70 ))

35.50 32.70 │ └── latitude └────────── longitude

4326 is the WGS 84 / EPSG:4326 coordinate system (SRID).

ST_Intersects( geometry, ST_MakeEnvelope( :minLon, :minLat, :maxLon,
:maxLat, 4326 ) )

ST_Area(geometry::geography) - m2 ST_Area(geometry::geography) /
1000000.0 - km2

The resulting value is stored as area_km2.

area_km2 is derived from geometry and is not accepted from the Frontend
as an authoritative value.

During drawing, the Frontend may calculate the current area locally for
UI preview. The persisted area_km2 is calculated again by the backend
when the Area is saved.

### Indexes

Indexes are defined from the system's primary access patterns.

B-tree Indexes

Primary Key and Unique constraints automatically provide indexes for:

-   User.id
-   Workspace.id
-   WorkspaceMember.id
-   Area.id
-   AreaVersion.id
-   WorkspaceMember(workspace_id, user_id)
-   AreaVersion(area_id, version)

An additional B-tree index is maintained for:

-   Area(workspace_id)

The AreaVersion(area_id, version) index supports both Area history
queries and lookup of a specific AreaVersion.

No separate index is required for Area.current_version because OCC
updates locate Area by its Primary Key before checking current_version.

Spatial Index

The primary spatial access pattern retrieves ACTIVE Areas intersecting
the current Viewport.

A partial GiST Spatial Index is maintained on Area.geometry:

CREATE INDEX idx_area_active_geometry ON area USING GIST (geometry)
WHERE status = 'ACTIVE';

The index reduces the number of candidate geometries that require exact
spatial intersection checks.

AreaVersion.geometry is not spatially indexed because historical
AreaVersions are accessed by area_id and version rather than by spatial
queries.

Additional indexes should be introduced only when supported by actual
access patterns and verified using query execution plans.

Spatial Queries

Viewport Query

SELECT id, name, geometry, area_km2, current_version FROM area WHERE
workspace_id = :workspaceId AND status = 'ACTIVE' AND ST_Intersects(
geometry, ST_MakeEnvelope( :minLon, :minLat, :maxLon, :maxLat, 4326 ) );

Bounding Box Construction

minLon minLat maxLon maxLat

GET /workspaces/{workspaceId}/areas ?bbox=minLon,minLat,maxLon,maxLat

ST_MakeEnvelope( :minLon, :minLat, :maxLon, :maxLat, 4326 )

Spatial Query Execution

Viewport ↓ BBox ↓ ST_MakeEnvelope(..., 4326) ↓ Partial GiST Spatial
Index ↓ Candidate Areas ↓ ST_Intersects ↓ Matching ACTIVE Areas ↓
GeoJSON ↓ Frontend

The spatial query uses the partial GiST index defined for ACTIVE
Area.geometry together with filtering by workspace_id.

Frontend applies Debounce to Viewport changes to avoid sending a request
for every intermediate pan or zoom movement.

Large result set optimization is outside the current scope. \## API &
Realtime Contract

REST API --- OpenAPI

Operation Method Endpoint Load Areas for Viewport GET
/workspaces/{workspaceId}/areas?bbox=... Create Area POST
/workspaces/{workspaceId}/areas Update Area PUT
/workspaces/{workspaceId}/areas/{areaId} Delete Area DELETE
/workspaces/{workspaceId}/areas/{areaId}?expectedVersion=N Get Area
History GET /workspaces/{workspaceId}/areas/{areaId}/versions Restore
Area Version POST
/workspaces/{workspaceId}/areas/{areaId}/versions/{version}/restore

Request / Response Models --- OpenAPI

### Error Contract

HTTP Error Code When 400 INVALID_REQUEST Invalid parameters/request body
400 INVALID_GEOMETRY Invalid Polygon, coordinates, SRID, etc. 401
UNAUTHENTICATED Missing/invalid authentication token 403 ACCESS_DENIED
No access to the Workspace or insufficient permissions 404
WORKSPACE_NOT_FOUND Workspace does not exist 404 AREA_NOT_FOUND Area
does not exist 404 AREA_VERSION_NOT_FOUND Requested AreaVersion does not
exist 409 VERSION_CONFLICT expectedVersion != Area.current_version 409
AREA_DELETED The operation requires an ACTIVE Area, but the Area is
already DELETED 429 RATE_LIMIT_EXCEEDED Rate limit exceeded 500
INTERNAL_ERROR Unexpected server-side error 503 SERVICE_UNAVAILABLE
Required infrastructure is temporarily unavailable

{ "code": "VERSION_CONFLICT", "message": "Area was modified by another
user.", "details": { "areaId": "42", "expectedVersion": 5,
"currentVersion": 6 }, "traceId": "01J..." }

code --- stable machine-readable error code message --- human-readable
description details --- additional data for the specific error traceId
--- correlation with backend logs/tracing WebSocket Events

AREA_CREATED AREA_UPDATED AREA_DELETED AREA_RESTORED

USER_JOINED USER_LEFT

{ "type": "AREA_UPDATED", "workspaceId": "...", "timestamp": "...",
"payload": { ... } }

## Concurrency, Versioning & Consistency

Optimistic Concurrency Control

Area modifications use Optimistic Concurrency Control based on
Area.current_version.

For Update, Delete, and Restore operations, Frontend sends
expectedVersion representing the Area version known to the user.

GIS API Service applies the mutation only when:

Area.current_version = expectedVersion

If the versions do not match, the mutation is rejected with:

409 Conflict / VERSION_CONFLICT

A stale client must never silently overwrite a newer Area state.

Versioning

Each successful Area Mutation creates a new immutable AreaVersion.

Version numbers increase monotonically for each Area:

1 -\> 2 -\> 3 -\> ... -\> N

Area.current_version identifies the current AreaVersion.

Restore does not make an old AreaVersion current directly. The selected
historical state is copied into a new AreaVersion.

Example:

v1 ACTIVE CREATED v2 ACTIVE UPDATED v3 ACTIVE UPDATED v4 DELETED DELETED

Restore v2:

v5 ACTIVE RESTORED

AreaVersion records are append-only for retained Areas. Explicit permanent deletion by a user physically removes both the Area row and all its associated AreaVersion rows within the same database transaction. The audit trail is therefore scoped to Areas that have not been permanently deleted; it does not constitute a globally immutable ledger.

**Permanent Delete** is a separate operation available only for Areas with `status = DELETED`. It physically removes the Area row and all associated AreaVersion rows. Permanent delete uses optimistic concurrency: the caller supplies the `expectedVersion` of the DELETED Area and the backend atomically verifies `status = 'DELETED' AND current_version = expectedVersion` before performing the physical deletion. If the version is stale, `409 VERSION_CONFLICT` is returned and no data is removed. An `AREA_PERMANENTLY_DELETED` WebSocket event is broadcast to connected clients after the transaction commits.

**Area lifecycle:**

```
ACTIVE  ---(soft delete)--->  DELETED  ---(restore)--->  ACTIVE
DELETED ---(permanent delete)--->  (row removed; not recoverable)
```

No `PURGED` status or `PURGED` ChangeType exists. The permanent delete operation leaves no AreaVersion record because the record would be removed immediately as part of the same operation.

Atomic Area Mutation

Changes to Area and the corresponding AreaVersion are persisted
atomically within the same database transaction.

For an existing Area:

BEGIN validate current state and expectedVersion create AreaVersion N +
1 update Area current state set Area.current_version = N + 1 COMMIT

If any operation fails, the transaction is rolled back.

After a successful commit:

Area.current_version = N

must correspond to:

AreaVersion(area_id = Area.id, version = N)

Concurrent Editing

Users may edit the same Area concurrently in the Frontend.

Intermediate drawing and editing actions are local to each Frontend and
are not synchronized between users.

Concurrency conflicts are detected when persistent changes are saved.

If two users modify the same Area version, the first successful save
creates a new AreaVersion. A subsequent save based on the stale version
is rejected with VERSION_CONFLICT.

The system does not require real-time synchronization of intermediate
geometry changes.

Event Consistency

Real-time events represent committed persistent state.

Events are published only after the corresponding database transaction
has been successfully committed.

Baseline event publication:

DB Transaction ↓ COMMIT ↓ GIS API Service ↓ Redis Pub/Sub ↓ Realtime
Gateway

With direct event publication, a failure between DB commit and event
publication may cause a real-time notification to be lost. PostgreSQL +
PostGIS remains the Source of Truth.

Transactional Outbox may be used when stronger event publication
reliability is required.

With Transactional Outbox, Area state, AreaVersion, and Outbox Event are
persisted within the same database transaction.

Outbox Publisher subsequently publishes the event using Redis Pub/Sub.

## Scalability & Reliability

Stateless Service Scaling The GIS API service must be stateless.

                API Gateway
                      │
          ┌───────────┼───────────┐
          ▼           ▼           ▼
       GIS API     GIS API     GIS API
       Instance 1  Instance 2  Instance 3
          │           │           │
          └───────────┼───────────┘
                      ▼
              PostgreSQL + PostGIS

### Realtime Gateway Scaling

                  API Gateway
                       │
            ┌──────────┴──────────┐
            ▼                     ▼
       Realtime #1           Realtime #2
          │                       │
       User A                   User B
          │                       │
          └──────────┬────────────┘
                     ▼
                   Redis
                  Pub/Sub

WebSocket connection physically belongs to a specific Realtime Gateway
instance. But the Area Event is published via Redis Pub/Sub, so all
Realtime Gateway instances can receive the event and send it to their
locally connected users.

PostgreSQL + PostGIS Scaling Database sharding is not required for the
initial system scale.

If PostgreSQL + PostGIS horizontal partitioning becomes necessary,
workspace_id is the preferred shard key.

Workspace is the unit of data locality. Area and AreaVersion records
belonging to the same Workspace are colocated on the same shard.

This allows the primary spatial access pattern:

Workspace + Viewport

to be routed to a single database shard without scatter-gather queries.

It also keeps Area Mutations and AreaVersion creation within a local
database transaction.

The primary limitation is a very large or hot Workspace, because
workspace-based sharding cannot distribute a single Workspace across
multiple shards.

Geographic sharding is not used in the baseline design because Areas may
cross geographic partition boundaries, which would require cross-shard
queries, duplication, or additional spatial routing.

Failure Handling

Failure Impact GIS API instance failure Other instances continue to
serve REST requests. Realtime Gateway instance failure WebSocket clients
of this instance lose their connection and reconnect. Redis unavailable
Presence and real-time propagation are temporarily unavailable.
PostgreSQL unavailable Persistent reads/writes are unavailable.
WebSocket unavailable The REST API continues to operate in degraded
mode. Tile Provider unavailable The base map/satellite layer is
unavailable; GIS data remains in the Source of Truth.

Recovery & Resynchronization

After the WebSocket connection is lost:

connection lost ↓ Frontend continues with REST ↓ WebSocket reconnect ↓
authentication + authorization ↓ Workspace Subscription ↓ Presence
restored ↓ reload Areas for current Viewport ↓ PostgreSQL Source of
Truth

Redis Pub/Sub is used for real-time notification delivery and is not
treated as a durable event log.

Frontend must not assume that all Area Events were received while its
WebSocket connection was unavailable.

After WebSocket reconnection:

1.  API Gateway authenticates the WebSocket connection.
2.  Realtime Gateway authorizes User access to the Workspace.
3.  Workspace Subscription is restored.
4.  Presence Store is updated.
5.  Frontend reloads Areas for the current Viewport using the REST API.
6.  PostgreSQL + PostGIS provides the authoritative current state.

This resynchronization restores the Frontend state even when real-time
events were missed during disconnection.

## Security & Observability

### Authentication

API Gateway is responsible for authentication of REST and WebSocket
requests.

Authentication is based on JWT access tokens issued by Auth / IdP.

API Gateway validates the token before forwarding the request and
propagates the authenticated User identity to downstream services.

GIS API Service and Realtime Gateway do not perform primary
authentication.

### Authorization

Authorization is performed by the service responsible for the requested
operation. GIS API Service authorizes REST operations against Workspace
membership and the required permission. Realtime Gateway authorizes
access to a Workspace before creating a Workspace Subscription.
WorkspaceMember is the persistent source for Workspace membership and
role information.

Authentication does not imply access to every Workspace.

### Rate Limiting

API Gateway applies Rate Limiting before requests reach application
services.

Area Mutations are limited per authenticated User according to the
configured system policy.

Current limit: Area Mutations \<= 50 requests/min/user

When the limit is exceeded:

HTTP 429 Too Many Requests RATE_LIMIT_EXCEEDED

Input & Geometry Validation

All external input is validated before persistence.

GIS API Service validates Area input including:

-   required Area fields;
-   supported geometry type;
-   coordinate ranges;
-   geometry validity.

Invalid geometry is rejected and is not automatically corrected.

Database constraints provide an additional data integrity boundary.

area_km2 received from Frontend is not treated as authoritative. The
persisted value is calculated by GIS API Service from geometry.

WebSocket Security

WebSocket connections require authentication.

Before associating a WebSocket connection with a Workspace Subscription,
Realtime Gateway verifies that the authenticated User has access to the
requested Workspace.

A client cannot subscribe to Workspace Events solely by knowing a
Workspace identifier.

WebSocket connections use heartbeat/timeout handling to detect
disconnected clients and remove stale Presence state.

### Audit Trail

AreaVersion is the persistent audit trail for Area Mutations. It combines
two responsibilities in a single append-only record:

1. **Version history** — the immutable state of an Area at a specific version,
   used for point-in-time inspection and restore operations.

2. **Area mutation audit** — a durable, transactionally consistent record of
   every committed change to an Area.

For each committed Area Mutation, AreaVersion answers:

| Audit question | Column |
|---|---|
| who | `created_by` (FK → users) — the authenticated user who performed the action |
| what | `change_type` (CREATED / UPDATED / DELETED / RESTORED) and `area_id` |
| when | `created_at` (TIMESTAMPTZ) |
| what resulted | `version`, `status`, `name`, `geometry`, `area_km2` — the complete Area state after the mutation |
| which workspace | `area_id → area.workspace_id` (one JOIN) |

The workspace context for an AreaVersion is always derivable via
`area_version.area_id → area.workspace_id`.

**No dedicated `audit_log` table exists.** Introducing one would duplicate
every Area mutation fact already committed atomically in AreaVersion — it
would add one extra write per mutation without capturing any new information.

**Audit trail is scoped to retained Areas.** AreaVersion records are append-only while the Area exists. Explicit permanent deletion by the user physically removes both the Area row and all its AreaVersion rows. After permanent deletion, no trace of the Area remains in the database. The audit guarantee is: *for any Area that has not been permanently deleted, its complete mutation history is preserved and append-only.*

**Failed and rejected operations produce no AreaVersion record.** Requests
rejected by rate limiting (429), OCC conflict (409), geometry validation (400),
or any other error result in either no transaction or a rolled-back transaction.
Because AreaVersion is written within the same database transaction as the Area
mutation, a rollback removes both. The audit trail therefore contains only
committed successful mutations.

**Non-Area operations are not persisted in an audit trail.** Authentication
events (registration, login) and read operations (viewport queries, history
retrieval) do not create AreaVersion records and have no dedicated persistent
audit table. User registration is implicitly recorded by the `users` row
(`users.created_at`). Login has no persistent trace in the current prototype.

**Three concerns are deliberately separated:**

| Concern | Mechanism |
|---|---|
| Version history + Area mutation audit | AreaVersion (PostgreSQL, transactional) |
| Request-level tracing and error recording | Application logs (SLF4J, structured, includes `traceId`) |
| Operational counters, latency, health | Metrics — see Metrics section |

### Data Retention

| Data | Retention |
|---|---|
| ACTIVE Area | Retained normally; visible in viewport queries |
| DELETED Area | Retained indefinitely; excluded from viewport queries; restorable via Deleted Areas API |
| Permanently deleted Area | Area row and all AreaVersion rows physically removed; not recoverable |
| AreaVersion (for retained Area) | Retained while the Area exists; append-only |
| Automatic purge | None — no scheduled cleanup job |

**Prototype policy:** DELETED Areas are retained indefinitely until the user explicitly restores or permanently deletes them. There is no configurable retention period and no scheduled cleanup.

**Permanent delete precondition:** The Area must have `status = DELETED`. Permanent deletion of an ACTIVE Area is rejected with `409 AREA_NOT_DELETED`. This enforces the two-step lifecycle: soft delete first, permanent delete second.

**Concurrency:** Permanent delete uses optimistic concurrency control (`current_version = expectedVersion`). An ABA race (DELETED → RESTORED → DELETED → stale permanent-delete attempt) is detected and rejected with `409 VERSION_CONFLICT`.

**Production evolution:** A production system may introduce configurable retention periods for DELETED Areas (e.g., auto-purge after 90 days), a scheduled cleanup job, archival to cold storage, and compliance-driven retention rules. None of these are implemented in the prototype.

### Application Logging

Services produce structured application logs.

Logs include:

-   timestamp;
-   log level;
-   service;
-   trace/correlation identifier;
-   operation;
-   Workspace identifier where applicable;
-   User identifier where applicable;
-   error code where applicable.

Authentication tokens, credentials, and other sensitive information must
not be written to application logs.

### Metrics

The system exposes metrics for REST, GIS, Realtime, and infrastructure
operations.

REST metrics: request rate; request latency; error rate.

GIS metrics: Viewport Query latency; Area Mutation latency;
VERSION_CONFLICT rate.

Realtime metrics: active WebSocket connections; Area Event propagation
latency; WebSocket reconnect rate.

Infrastructure metrics: database connection pool utilization; PostgreSQL
errors; Redis errors.

Metrics are used to validate defined SLOs and detect system degradation.

Relevant SLOs include:

Viewport Query: p95 \< 300 ms

Area Event propagation: p95 \< 200 ms

#### Prototype Implementation

The following custom Micrometer metrics are implemented and exposed at
`GET /actuator/prometheus` in the Prometheus text format:

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `area.mutations.committed.total` | Counter | `change_type` (CREATED / UPDATED / DELETED / RESTORED) | Incremented after each successful DB commit for an Area mutation. Uses `@TransactionalEventListener(AFTER_COMMIT)` so only durable mutations are counted. Pre-registered at startup so all `change_type` variants appear in Prometheus output at zero. |
| `area.version.conflict.total` | Counter | — | Incremented each time a `409 VERSION_CONFLICT` response is returned. |
| `drawing.rate.limit.rejected.total` | Counter | — | Incremented each time a mutation is rejected by the drawing action rate limiter (`429 Too Many Requests`). |
| `websocket.connections.active` | Gauge | — | Number of active authenticated STOMP WebSocket sessions. Backed by a `ConcurrentHashMap.newKeySet()`. Incremented on `SessionConnectedEvent` (fires after the STOMP CONNECTED frame is sent — rejected JWT attempts do not fire this event). Decremented on `SessionDisconnectEvent`. The Set makes removal idempotent so the gauge cannot go negative from duplicate disconnect events. |

Spring Boot auto-configuration also provides JVM, process, system, Logback,
HikariCP, and Tomcat metrics, available at the same endpoint.

**Prometheus endpoint:** `GET /actuator/prometheus` returns Prometheus text
format (dots converted to underscores in metric names, e.g.,
`area_mutations_committed_total`). The endpoint is permit-all in the prototype
as a simplification. In production it should be restricted to an internal
network, a separate management port, or an infrastructure ACL.

**Metric cardinality:** No metric uses dynamic tags derived from user identity,
workspace ID, area ID, geometry, or request payload. All cardinality is fixed
at startup or bounded by the small set of `ChangeType` enum values.

**No database writes for metrics.** All metric state is held in-process in
Micrometer's `MeterRegistry`. Metrics state is not durable across restarts.

#### WebSocket Reconnect Lifecycle

The frontend `RealtimeService` uses `@stomp/stompjs` with automatic reconnect (3 s delay, no exponential backoff).

**Token refresh on reconnect:** The `beforeConnect` hook reads the current JWT before every connection attempt via `AuthService.getToken()`. This ensures reconnects always use the live token — not the one captured at initial connection time.

**Infinite reconnect loop protection:** If `getToken()` returns `null` (cleared by the REST 401 interceptor after JWT expiry, or by logout), `beforeConnect` calls `client.deactivate()` via `setTimeout` to stop further reconnect attempts without synchronous reentrancy.

**Status indicator:** `RealtimeService.connect()` accepts an `onStatusChange` callback. `MapComponent` sets `wsStatus: 'connected' | 'reconnecting'`. A "Reconnecting realtime updates…" banner is shown at the bottom-centre of the map while disconnected; it clears on reconnect.

**Reconciliation on reconnect:** The `onReconnect` callback calls `loadViewport()`, which issues a REST `GET` to reload all active Areas for the current viewport. Full `persistedItems` layer replacement (clear + reload) handles any missed `AREA_CREATED`, `AREA_UPDATED`, `AREA_DELETED`, or `AREA_RESTORED` events without event replay. `drawnItems`, `editItems`, and `previewItems` are not touched during reconciliation.

**Intentional-disconnect guard:** `disconnect()` sets `this.client = null` synchronously before calling `deactivate()`. The `onWebSocketClose` callback checks `this.client !== null` before calling `onStatusChange('reconnecting')`, preventing a spurious "Reconnecting" banner after deliberate logout or component destroy.

#### Active Users / Presence

**Semantics:** A user is "active" while they have at least one live STOMP session. Multiple browser tabs or devices for the same user collapse to a single presence entry (userId → `Set<sessionId>`).

**Lifecycle events:**
- `PRESENCE_JOINED` — broadcast when the first session for a user connects.
- `PRESENCE_LEFT` — broadcast when the last session for a user disconnects.

Both events are delivered on the per-workspace presence topic: `/topic/workspace/{workspaceId}/presence`.

**Session/user mapping:** `PresenceService` maintains three `ConcurrentHashMap` structures: `userId → Set<sessionId>` (multi-tab tracking), `sessionId → userId` (reverse lookup for disconnect), and `userId → displayName` (resolved once via `UserRepository` at first connect, released at last disconnect). Atomic first/last detection uses `ConcurrentHashMap.compute()` / `computeIfPresent()` (returning `null` atomically removes the entry).

**Snapshot REST endpoint:** `GET /workspaces/{workspaceId}/presence` returns the current active-user list as `[{id, name}]`. Returns `404 WORKSPACE_NOT_FOUND` for unknown workspace IDs (same contract as area endpoints). Requires authentication.

**Race-safe snapshot on the frontend:** On (re)connect, the frontend calls `loadPresence()`, setting `presenceLoading = true` and buffering incoming presence events. When the snapshot response arrives, state is replaced by the snapshot, the buffer is replayed in order, and `presenceLoading = false`. JavaScript's single-threaded event loop ensures the buffer is consistent with respect to event ordering.

**Single-instance limitation:** Presence state is held in-process in `PresenceService`. Users connected to different backend instances will not see each other. Multi-instance presence requires a shared store (e.g., Redis sorted sets or hash maps keyed by userId) and cross-instance event delivery via Redis Pub/Sub. This evolution is described in the design but deferred.

**Persistence:** Presence state is ephemeral (not persisted to PostgreSQL). It is derived from live session state and is cleared on application restart. PostgreSQL remains the source of truth for all persistent application state.

### Geographic Data Caching Decision

A geographic data cache was evaluated for viewport-based Area queries but is intentionally not implemented at the current prototype stage.

**Evaluated strategies:**

*Exact BBOX cache (Candidate A):* Cache the result of each `findInViewport` call keyed by the exact bounding-box coordinates. Rejected because the viewport key is derived from floating-point Leaflet map bounds that change with every pan or zoom event. Two visually identical map positions produce different keys, so the hit rate is near zero. Invalidation on Area mutation is also complex because the updated geometry's old bounding box is not available during an `UPDATE` operation (the entity is overwritten before the event fires), making it impossible to evict only the affected keys.

*Whole-workspace cache (Candidate B):* Cache the complete Area list for a workspace and perform the viewport intersection in Java. Rejected because it bypasses PostGIS/GiST — the designated spatial query engine — and replaces indexed spatial lookups with O(N) Java-side iteration. This violates the design principle that PostGIS is the source of truth for spatial queries and is explicitly excluded from the requirements ("Do not perform viewport filtering in application code").

*Spatial-cell / Web Mercator tile cache (Candidate C):* Cache query results keyed by Web Mercator tile coordinates (z/x/y). This produces stable, discrete cache keys whose geometry is fixed by the tile grid specification. Cache keys align with how the frontend will eventually request tiles, PostGIS/GiST remains the authoritative execution engine (queries are executed on cache miss), and invalidation can be implemented as a workspace-wide eviction on any Area mutation. This strategy is technically sound and avoids the problems of Candidates A and B.

**Decision:** Caching is deferred for the prototype. The PostGIS partial GiST index (`idx_area_active_geometry`) provides efficient viewport queries for the current data volume. Introducing a tile cache before a measured performance bottleneck exists adds complexity without demonstrated benefit. If viewport query latency becomes a production concern, Candidate C (tile cache with workspace-wide invalidation, backed by Caffeine for single-instance or Redis for multi-instance deployments) is the recommended starting point.

### Health Checks

Services expose Liveness and Readiness health checks.

Liveness indicates that the service process is operational.

Readiness indicates that the service is able to accept and process
requests.

Readiness may depend on required infrastructure.

For GIS API Service, PostgreSQL + PostGIS availability is required for
normal operation.

Redis unavailability does not necessarily make GIS API Service
unavailable because persistent REST operations may continue in degraded
mode without real-time event propagation.

Distributed Tracing

A Trace ID is propagated across service boundaries.

Tracing allows an Area Mutation or other request to be followed through
the relevant system components: Frontend ↓ API Gateway ↓ GIS API Service
↓ PostgreSQL + PostGIS ↓ Event Publication ↓ Redis Pub/Sub ↓ Realtime
Gateway Trace ID is included in application logs and error responses
where appropriate.

Distributed tracing is used to identify latency, failures, and
dependencies across system components.
