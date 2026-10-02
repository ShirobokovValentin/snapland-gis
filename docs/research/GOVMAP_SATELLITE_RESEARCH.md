# GovMap Satellite / Orthophoto Integration Research

**Document type:** Technical research memo  
**Scope:** Task 08 — Map / Satellite Layer Switching  
**Date of research:** 2026-10-02  
**Status:** Pending architectural decision — Task 08 implementation blocked on satellite-provider selection

---

## 1. Context

### Why this investigation was performed

Task 08 requires adding satellite imagery as a second base layer to the
application map. The project's REQUIREMENTS.md and DESIGN.md both name
`govmap.gov.il` as the satellite imagery source. Before implementing the
feature, the tile service needed to be evaluated for technical compatibility
with the existing Leaflet-based frontend.

### Original project requirement

**REQUIREMENTS.md §4 — Frontend Map Integration:**

> Implement the map using Leaflet.js with two base layers:
> - OpenStreetMap for the default view.
> - Satellite imagery from govmap.gov.il.
> Create a smooth layer transition when switching between views.
> Ensure drawn elements remain properly positioned when switching layers.

**DESIGN.md — Glossary entries:**

> **Tile Provider:** An external service that provides map/satellite tiles
> to the Frontend.
>
> **OpenStreetMap:** The provider/source of the standard map layer in the
> current design.
>
> **govmap.gov.il:** The satellite imagery source in the current design.

Neither document specifies a tile URL, endpoint path, coordinate system,
attribution string, API key requirement, CORS policy, or zoom range for
govmap.gov.il. The provider is named but not technically specified.

### Why Task 08 could not immediately implement this requirement

Before any code could be written, the following needed to be known:

- Where exactly to fetch satellite tiles from
- Whether Leaflet can consume that service using its standard `L.tileLayer()`
- Whether the browser is permitted to make requests to that service (CORS)
- Whether authentication or registration is required
- Whether the service's coordinate system is compatible with the current map

None of these were documented in the repository. This investigation was
performed to answer those questions.

### Current application context

The frontend is an Angular 22 / TypeScript 6 application using Leaflet.js
with `leaflet-draw`. The map is initialised with a standard OSM tile layer:

```javascript
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19,
  attribution: '© OpenStreetMap contributors',
}).addTo(this.map);
```

This uses **EPSG:3857 (Web Mercator)**, which is Leaflet's default CRS and
the standard for all XYZ tile services (OSM, Google Maps, Mapbox, etc.).
All persisted Area overlays (`persistedItems`, `editItems`, `drawnItems`,
`previewItems`) are `L.FeatureGroup` instances on top of this map. Their
coordinates are stored in WGS84 (EPSG:4326) LatLng pairs.

---

## 2. Research Goal

The investigation attempted to answer the following questions:

1. Does GovMap expose satellite / orthophoto imagery that Leaflet can
   consume?
2. What is the endpoint, if any?
3. What protocol or format is used (XYZ, TMS, WMTS, WMS, ArcGIS REST)?
4. What coordinate reference system (CRS) does the service use?
5. Is direct browser access supported without a server-side proxy?
6. Is authentication or API key registration required?
7. What are the CORS constraints for browser-to-tile-server requests?
8. What are the licensing and attribution requirements?
9. Can OSM (EPSG:3857) and GovMap imagery be switched as two
   interchangeable `L.TileLayer` instances on the same Leaflet map?
10. What implementation complexity would a GovMap satellite integration
    require?

---

## 3. Executive Summary

The following conclusions are drawn from the research. Each is labelled with
an evidence-quality marker:

- **CONFIRMED** — Directly verified or from official documentation
- **PARTIALLY CONFIRMED** — Official source exists but content is
  incomplete
- **THIRD-PARTY REPORTED** — Reported by community sources, not officially
  verified
- **INFERRED** — Logical inference from confirmed technical facts
- **UNCONFIRMED** — Investigated but could not be verified

---

**GovMap serves satellite / orthophoto imagery.**  
CONFIRMED (official portal; third-party technical catalogue)

**The primary tile CDN is `cdn.govmap.gov.il`.**  
THIRD-PARTY REPORTED (derived from community technical discussions; not
published in official GovMap documentation)

**Tiles use ArcGIS compact tile cache format with hexadecimal row/column
encoding.**  
THIRD-PARTY REPORTED (Esri community, OruxMaps forum, AllMapSoft catalogue)

**The primary tile CRS is EPSG:2039 (Israel Transverse Mercator / ITM),
not Web Mercator.**  
THIRD-PARTY REPORTED with strong technical consistency across multiple
independent sources

**Standard Leaflet `L.tileLayer('{z}/{x}/{y}')` cannot consume the
GovMap tile cache without custom coordinate conversion.**  
INFERRED from confirmed EPSG:2039 CRS and ArcGIS hex tile format

**OSM (EPSG:3857) and GovMap legacy tiles (EPSG:2039) cannot be used as
interchangeable base layers on the same Leaflet map without reprojecting
one of them.**  
INFERRED from how Leaflet applies a single map-level CRS

**An open WMS endpoint (`open.govmap.gov.il/geoserver/opendata`) exists.**  
PARTIALLY CONFIRMED (URL present in search index; whether it exposes
orthophoto layers and whether CORS is permitted is UNCONFIRMED)

**Newer Web Mercator orthophoto layer variants reportedly exist on GovMap.**  
THIRD-PARTY REPORTED (third-party tile catalogue only; no official URL
found)

**No official tile URL, cache ID, or developer documentation for the tile
CDN was found.**  
CONFIRMED absence of official documentation

**CORS support for direct browser access is not documented.**  
UNCONFIRMED. Community sources describe the service as "technically
inaccessible by standard tile clients" (OpenStreetMap forum, 2018). This
is a community observation, not a verified HTTP header test.

**Authentication or API key for raw tile access is not clearly defined.**  
UNCONFIRMED. `api.govmap.gov.il` requires email registration for its
JavaScript embed API, but whether this extends to raw CDN tile access is
not documented.

**A clear public license for third-party use of GovMap tiles in web
applications was not found.**  
CONFIRMED absence of public license documentation. Survey of Israel holds
copyright. Community requests for usage rights have not resulted in a
publicly available license.

---

## 4. Existing GovMap Services Investigated

### 4.1 `cdn.govmap.gov.il` — Primary Tile CDN

| Property | Value |
|----------|-------|
| Host | `cdn.govmap.gov.il` |
| Purpose | Serves map tile images for the GovMap web portal |
| Protocol/Format | ArcGIS compact tile cache over HTTPS; `.jpg` (orthophoto), `.png` (vector layers) |
| Source | Esri Community forum, OruxMaps forum, AllMapSoft catalogue |
| Official source? | No — observed by third parties; not documented on govmap.gov.il or api.govmap.gov.il |
| Successfully verified? | No — direct HTTP access was not tested during this investigation |
| Limitations | Non-standard CRS (EPSG:2039); hexadecimal tile addressing; cache IDs are opaque and undocumented; CORS unknown; licensing unclear |

The CDN is used internally by the GovMap portal (`govmap.gov.il`). Tiles
are loaded by GovMap's own JavaScript application. Whether the CDN allows
cross-origin requests from third-party applications is not documented.

### 4.2 `open.govmap.gov.il/geoserver/opendata` — GeoServer WMS

| Property | Value |
|----------|-------|
| Host | `open.govmap.gov.il` |
| Purpose | Appears to be an open-data OGC WMS endpoint operated by the Survey of Israel |
| Protocol/Format | WMS (GeoServer) |
| GetCapabilities URL | `https://open.govmap.gov.il/geoserver/opendata/wms?request=getcapabilities&service=wms` |
| Source | URL appeared in search index results |
| Official source? | Likely official (govmap.gov.il subdomain, Survey of Israel GeoServer) |
| Successfully verified? | No — an attempt to retrieve GetCapabilities during this investigation returned a socket-closed error |
| Limitations | Layers available are unknown; whether orthophoto/satellite imagery is included is unknown; CORS status unknown; whether the URL is stable and publicly maintained is unknown |

The existence of this endpoint is promising. If it includes orthophoto
layers and permits cross-origin access, a `L.tileLayer.wms()` integration
would be substantially simpler than the CDN approach. However, none of
these properties could be confirmed.

### 4.3 `api.govmap.gov.il` — JavaScript Embed API

| Property | Value |
|----------|-------|
| Host | `api.govmap.gov.il` |
| Purpose | Provides JavaScript functions for embedding GovMap's viewer into third-party pages; supports URL parameter navigation and JavaScript callbacks |
| Protocol/Format | JavaScript API (not a tile service) |
| Source | Official GovMap documentation site |
| Official source? | Yes |
| Successfully verified? | Partially — the API homepage and introduction were retrieved; category pages returned HTTP 403 |
| Limitations | This is an embed API, not a tile API. It controls GovMap's own viewer iframe/widget. It does not expose raw tiles for use in a third-party Leaflet map. Registration with an email address is mentioned. |

The `api.govmap.gov.il` documentation describes three integration methods:
URL parameters, HTML embedding, and JavaScript functions. None of these
methods expose raw tile URLs consumable by `L.tileLayer()`. The API is
designed to embed GovMap's own portal inside another page, not to serve
tiles to a separate mapping library.

### 4.4 Web Mercator Orthophoto Layers (Reported)

| Property | Value |
|----------|-------|
| Source | AllMapSoft tile catalogue (`allmapsoft.com/providers/193.htm`) |
| Claim | GovMap exposes 16 "Web Mercator variant" layers, including "Web Mercator Ortophoto 2025" at zoom levels 6–20 |
| Official source? | No — third-party commercial tile catalogue |
| Successfully verified? | No — no URL was provided; no endpoint was found in official documentation |
| Limitations | No URL, no cache ID, no attribution, no CORS or licensing information available |

If these layers exist with a documented endpoint and CORS support, they
would be significantly easier to integrate (see Section 10).

---

## 5. GovMap Tile Cache Format

### URL structure

Based on third-party technical community reports, the primary GovMap tile
CDN uses the following URL template:

```
https://cdn.govmap.gov.il/{cache_id}/L{level}/R{row_hex8}/C{col_hex8}.jpg
```

#### Field descriptions

| Field | Description |
|-------|-------------|
| `{cache_id}` | An opaque alphanumeric identifier specific to each imagery layer and version. Example values observed: `LPD0BBK2022`, `020522B0B20R`. The mapping between layer names and cache IDs is not published in any official documentation found during this investigation. |
| `L{level}` | Zoom / detail level, formatted as the letter `L` followed by the integer level number (e.g., `L06`, `L10`). This corresponds to the ArcGIS tile cache level, not a Web Mercator zoom level. |
| `R{row_hex8}` | The tile row index, prefixed with `R`, expressed as an 8-character zero-padded lowercase hexadecimal string (e.g., `R00012d82`). The value is derived from the ITM (EPSG:2039) tile grid, not from a Web Mercator tile grid. |
| `C{col_hex8}` | The tile column index, prefixed with `C`, expressed as an 8-character zero-padded lowercase hexadecimal string. Same ITM-derived origin as the row. |

#### Example URL

Reported in Esri community and OruxMaps forum discussions:

```
https://cdn.govmap.gov.il/LPD0BBK2022/L10/R00012d82/C00000xxx.jpg
```

An older URL format was reported in the OruxMaps forum (2011), using a
different CDN hostname and layer path:

```
http://cache.govmap.gov.il/arcgiscache/MapCacheNational/Layers/_alllayers/L06/R00000ede/C00000ce1.png
```

This older format reveals the underlying ArcGIS tile cache structure
(`arcgiscache`, `_alllayers`), confirming the ArcGIS compact cache origin.

#### Why this differs from standard XYZ tiles

Standard XYZ tile URLs follow the pattern `/{z}/{x}/{y}.png` where:
- `z` is a Web Mercator zoom level (0–19 or higher)
- `x` and `y` are integer tile indices derived from Web Mercator coordinates
- No hexadecimal conversion is required

GovMap differs in two fundamental ways:
1. **Hexadecimal encoding:** Row and column values are zero-padded 8-digit
   hex strings, not plain integers.
2. **Different coordinate origin:** Row and column values are derived from
   the EPSG:2039 (ITM) tile grid, not from EPSG:3857 (Web Mercator).
   Converting between them requires a coordinate reprojection, not just a
   numeric reformat.

**Official documentation status:** None of the above tile format details
are officially documented by the Survey of Israel or GovMap. All
information was derived from Esri community forum posts, OruxMaps forum
discussions, and third-party tile catalogues. These sources are consistent
with each other, but they have not been confirmed against any official
GovMap technical specification.

---

## 6. Coordinate Reference System

### GovMap: EPSG:2039 — Israel 1993 / Israeli TM Grid

GovMap uses **Israeli Transverse Mercator (ITM)**, a national projected
coordinate reference system for Israel and the Palestinian territories.

**EPSG code:** 2039  
**Full name:** Israel 1993 / Israeli TM Grid  
**Projection type:** Transverse Mercator  
**Units:** Metres (easting / northing)  
**Geographic datum:** Israel 1993 (based on GRS 1980 ellipsoid)  
**Reference:** [epsg.io/2039](https://epsg.io/2039)

ITM defines coordinates as metre-based easting/northing values referenced
to a national origin. Its tile grid origin and resolution ladder are
specific to this coordinate system.

### Standard Leaflet / OSM: EPSG:3857 — Web Mercator (Pseudo Mercator)

The current application and OSM use **Web Mercator**, the de facto
standard for web mapping tile services.

**EPSG code:** 3857  
**Units:** Metres (projected, nominally)  
**Geographic datum:** WGS84  
**Used by:** OpenStreetMap, Google Maps, Bing Maps, Mapbox, Esri basemaps,
and all standard XYZ tile providers

### Why this matters for tile addressing

Tile addressing in any tile cache is derived from the map's coordinate
system. Two tile caches in different projections have completely different:

- **Tile grid origins** (the `(0, 0)` anchor point of the tile grid in
  projected units)
- **Resolution ladders** (the metres-per-pixel at each zoom level)
- **Row and column numbering** (which tile covers which geographic area)

A tile at row 300, column 450, zoom 10 in an ITM tile cache covers a
completely different geographic area than row 300, column 450, zoom 10 in
a Web Mercator tile cache. To load the correct GovMap tile for a given
screen location in a Web Mercator map, the screen coordinates must first
be reprojected from EPSG:3857 to EPSG:2039, then converted to the ITM
tile grid.

### Conceptual comparison

```
WGS84 LatLng (31.78°N, 35.22°E)   ← same geographic point

         ↓ project to EPSG:3857               ↓ project to EPSG:2039
   (3,921,140 m, 3,756,100 m)         (220,130 m, 632,450 m)
   Web Mercator easting/northing       ITM easting/northing

         ↓ compute tile index                ↓ compute ITM tile index
       z=14, x=9581, y=6360               Level=14, Row=0x00012d8e,
                                               Col=0x00000d78
         ↓
  OSM tile URL:                       GovMap tile URL:
  /14/9581/6360.png                   /LPD0BBK2022/L14/R00012d8e/C00000d78.jpg
```

The two URLs address the same geographic area but require entirely separate
computation paths.

---

## 7. Leaflet Compatibility Analysis

### Why `L.tileLayer("https://cdn.govmap.gov.il/...")` does not work

Leaflet's `L.tileLayer()` accepts a URL template with three placeholders:
`{z}`, `{x}`, `{y}`. These are populated automatically from the map's
current view using the map's CRS (EPSG:3857 by default). It provides no
mechanism to:

1. Reproject tile coordinates from Web Mercator to ITM before computing the
   tile index
2. Convert integer tile indices to hexadecimal strings
3. Format row and column values with 8-digit zero padding
4. Look up an opaque cache ID per imagery layer

### Specific incompatibilities

**XYZ incompatibility:**  
GovMap uses `R{hex8}/C{hex8}` addressing, not `{x}/{y}` integer addressing.
Leaflet's URL template system cannot produce hexadecimal-formatted
coordinates.

**CRS incompatibility:**  
Leaflet computes tile coordinates from the map's CRS. The default map CRS
is EPSG:3857. GovMap tile coordinates are defined in EPSG:2039. Using
EPSG:3857-derived tile indices against an EPSG:2039 cache retrieves the
wrong tiles for a given screen location.

**Tile coordinate conversion:**  
To correctly address a GovMap tile, the Web Mercator screen coordinates
must be reprojected to ITM coordinates, then converted to the ArcGIS tile
grid's row/column indices at a given detail level. This requires:
- A coordinate reprojection library (e.g., proj4.js)
- Knowledge of the ITM tile grid origin in ITM metres
- Knowledge of the ITM resolution at each detail level

**Origin / resolution requirements:**  
ArcGIS tile caches define their grid relative to a specific projected
origin (not necessarily `(0, 0)`) and a fixed resolution ladder (not
necessarily matching OSM zoom levels). These values are normally read from
the tile cache's `conf.cdi` / `conf.xml` metadata files. GovMap does not
publish these files.

**Cache ID discovery:**  
Each imagery layer or vintage year has a distinct `cache_id`. No official
index or API to discover available cache IDs was found.

**Implications of Leaflet's map-level CRS:**  
Leaflet applies a single CRS to the entire map. All layers on the map —
including all tile layers and all vector/feature layers — use the same CRS
for coordinate calculations and rendering. It is not possible to assign
one CRS to the OSM tile layer and a different CRS to the GovMap tile layer
within the same `L.Map` instance. Changing the map CRS affects every
layer.

### What EPSG:2039 integration would theoretically require

The following would be needed — this is a description of the technical
path, not a prescription for implementation:

1. A coordinate reprojection library (e.g., `proj4.js` and
   `proj4leaflet`) to define EPSG:2039 as the map's CRS
2. ITM tile grid parameters (origin, resolution ladder) obtained from
   GovMap's tile cache metadata — currently undocumented
3. A custom tile URL function that converts ITM tile indices to
   8-digit zero-padded hexadecimal row/column strings
4. A resolution of the CORS issue (see Section 11)
5. A resolution of the licensing issue (see Section 13)
6. A known, stable cache ID for the desired orthophoto layer
7. Changing the map's CRS from EPSG:3857 to EPSG:2039, which requires
   that the OSM base layer also be available in EPSG:2039 — OSM tile
   servers do not serve tiles in ITM

Point 7 is architecturally significant: it means that integrating GovMap
in its native CRS is not additive — it requires restructuring the entire
map.

`proj4leaflet` is mentioned only as an investigated technical option. It
is not an approved dependency for this project.

---

## 8. OSM + GovMap Layer Switching Problem

### The core problem

The feature request in Task 08 is logically simple: "switch between two
tile layers." In a Web Mercator world, this is straightforward:

```javascript
map.removeLayer(osmLayer);
map.addLayer(satelliteLayer);
```

This works when both layers use EPSG:3857 because the map's coordinate
system, tile addressing, and all overlays are defined in the same CRS.

For GovMap's legacy EPSG:2039 tile cache, this is not the case:

```
OSM (EPSG:3857)   ←→   GovMap legacy tiles (EPSG:2039)
```

These two layers cannot be treated as interchangeable `L.TileLayer`
instances on the current Leaflet map.

### Why

Leaflet's `L.Map` has a `crs` option that is set at construction time
(default: `L.CRS.EPSG3857`). This CRS governs how:

- Latitude/longitude coordinates are projected to pixel coordinates
- Pixel coordinates are converted to tile grid indices
- All vector overlays (polygons, markers, popups) are positioned on the
  map canvas

There is one CRS for the entire map. It is not per-layer.

To render GovMap EPSG:2039 tiles at their correct geographic positions, the
map's CRS would need to be EPSG:2039. But then:

- OSM tiles (which are in EPSG:3857) would either need to be reprojected
  client-side (OSM tile servers do not serve ITM tiles) or would render at
  incorrect positions
- All existing `L.LatLng` coordinates stored as WGS84 pairs would need
  to be re-projected before rendering — though Leaflet handles this
  internally when a custom CRS is set, the CRS definition must include
  accurate origin and resolution parameters for the tiles to align

### Implication

The original REQUIREMENTS.md entry:

> Switch between the standard and satellite map layers.
> Drawn objects retain their correct position when switching layers.

is trivially satisfied when both layers share EPSG:3857. It becomes a
substantially more complex engineering problem when one layer uses a
national projected CRS (EPSG:2039) and the other uses Web Mercator.

This section explains why the original govmap.gov.il requirement is not
a simple frontend toggle, but potentially a significant architectural
constraint on the entire map configuration.

---

## 9. WMS Investigation

### Discovered endpoint

During the investigation, the following URL appeared in search engine
results:

```
https://open.govmap.gov.il/geoserver/opendata/wms?request=getcapabilities&service=wms
```

This URL pattern (`geoserver/opendata/wms`) follows the standard GeoServer
WMS endpoint convention. The `open.govmap.gov.il` subdomain suggests an
intention to provide open-data access.

### Retrieval attempt

An attempt to retrieve the WMS GetCapabilities document during this
investigation resulted in a socket-closed error. The response body was not
obtained. It is unknown whether the service was temporarily unavailable,
requires specific network access, or was permanently removed.

### What could be confirmed

- The URL exists in search engine indices, suggesting it was publicly
  accessible at some point
- The `open.govmap.gov.il` subdomain is associated with the Survey of
  Israel / GovMap project
- GeoServer is the platform used (confirmed by URL pattern)

### What could not be confirmed

- Whether the service is currently operational
- Which layers are available (no GetCapabilities response retrieved)
- Whether orthophoto or satellite imagery is included
- Whether the service supports EPSG:3857 requests (many GeoServer
  deployments support multiple CRSes per layer)
- Whether CORS headers permit cross-origin browser requests

### Why this matters

If `open.govmap.gov.il/geoserver/opendata/wms` is operational and includes
orthophoto layers with EPSG:3857 support, it could be consumed by Leaflet
using `L.tileLayer.wms()` — a built-in Leaflet mechanism requiring no
custom coordinate math. This would be a significantly simpler integration
than the CDN tile cache approach.

This endpoint warrants direct verification before a final architectural
decision is made.

---

## 10. Web Mercator Orthophoto Investigation

### Reported existence

A third-party tile catalogue (AllMapSoft, `allmapsoft.com/providers/193.htm`)
describes GovMap's tile offerings. It lists a category of "Web Mercator
Variants" containing 16 layers, including:

- "Web Mercator Ortophoto 2025" — zoom levels 6–20
- "Web Mercator Ortophoto 2003" through "Web Mercator Ortophoto 2024"
- "Web Mercator Historical" layers (Jerusalem, Haifa, Tel Aviv)

### Source assessment

AllMapSoft is a **third-party commercial catalogue**, not an official
GovMap or Survey of Israel publication. It catalogues tile sources for use
with proprietary mapping software. The catalogue entry provides internal
"MapsType IDs" (numerical identifiers like 2473, 1568) used within the
AllMapSoft system, but does not provide the actual CDN URLs or cache IDs.

### What could not be verified

- No official CDN URL for any Web Mercator GovMap layer was found
- No cache ID for any Web Mercator layer was found
- Whether these layers are accessible via a public, undocumented URL is
  unknown
- CORS and licensing status for these layers is unknown

### Why this matters

If GovMap's Web Mercator layers are accessible at a documented public
endpoint, they would:

- Use EPSG:3857, compatible with the existing Leaflet map
- Require only a `cache_id` and hex-row/col URL adapter (no CRS change)
- Remove the OSM + GovMap CRS incompatibility problem entirely

Verifying whether Web Mercator tile URLs exist and are accessible remains
an open question (see Section 17).

---

## 11. Browser / CORS Analysis

### What was tested

Direct CORS testing against `cdn.govmap.gov.il` was not performed during
this investigation. Response headers were not inspected.

### Confirmed absence of documentation

No CORS policy for `cdn.govmap.gov.il` or `open.govmap.gov.il` was found
in any official GovMap documentation, API documentation, or developer
portal.

### Community reports

A post in the OpenStreetMap community forum (Israel section, 2018) stated:

> "all the contents at govmap.gov.il is copyrighted and technically
> inaccessible by standard tile clients"

This is a **community observation**, not an official statement. It may
reflect a specific test at a specific point in time. It should not be
interpreted as a permanent or official policy of inaccessibility.

A related discussion noted: "govmap is using Israeli projections while
OSM...are using WGS84" as a practical barrier to integration, independent
of any explicit CORS block.

### Inference

Without `Access-Control-Allow-Origin` headers on the CDN responses,
browsers will block cross-origin tile requests from a web application
running at a different origin (e.g., `localhost:4201`). Whether govmap's
CDN sends these headers is unknown.

**Important distinction:**  
"No official CORS documentation was found and browser compatibility was
not successfully verified" is not equivalent to "GovMap does not support
CORS." The absence of documentation does not confirm that CORS is blocked.

---

## 12. Authentication / API Access

### `api.govmap.gov.il`

The GovMap API portal at `api.govmap.gov.il` describes an integration
API with three mechanisms:

1. **URL parameters** — navigate GovMap's portal to a predefined state
2. **HTML embedding** — embed GovMap's viewer in an iframe or page element
3. **JavaScript functions** — interact programmatically with GovMap's
   embedded viewer

Registration with an email address is required to use this API. The API
documentation was partially accessible (introduction pages); category
pages returned HTTP 403 during this investigation, preventing detailed
review.

### Raw tile access vs. embed API

The `api.govmap.gov.il` API is an **embed/control API**, not a raw tile
delivery API. Registering for the embed API provides access to GovMap's
JavaScript viewer and interaction functions. It does not appear to
provide authentication credentials for direct tile requests to
`cdn.govmap.gov.il`.

Whether separate credentials, registration, or an API key are required for
raw tile requests to the CDN is not documented. It is not safe to assume
that registration for the embed API automatically authorises raw CDN tile
access.

### Historical access change

One OpenStreetMap forum discussion (Israel section) reported that a
related MAPI (Survey of Israel) imagery TMS service that was previously
accessible became "no longer available without login" in January 2018.
The URL affected was hosted on `arcgis.com`, not on `cdn.govmap.gov.il`,
but it illustrates that Israeli government imagery services have
transitioned to authenticated access in the past.

---

## 13. Licensing and Attribution

### Copyright ownership

Multiple sources confirm that the Survey of Israel (המרכז למיפוי ישראל /
מפ"י) holds copyright over the map and imagery data accessible through
govmap.gov.il. This includes the orthophoto/aerial photography content.

A user in the OpenStreetMap Israel forum stated:

> "the Israel Survey (המרכז למיפוי ישראל) holds all the copyright to the
> data" and requests for usage must be directed to the Israel Survey director.

The portal's privacy terms (govmap.gov.il/Privacy.htm, items 4–8) were
referenced but not directly retrieved during this investigation.

### Community attempts to obtain usage rights

An OpenStreetMap contributor (SafwatHalaby) directly contacted GovMap
to request permission to use the aerial imagery for OpenStreetMap
contributions. The response requested a phone discussion, and no
conclusive agreement was publicly reported. The OSM community thread
concluded without a license being granted.

A separate note mentioned that the orthophoto imagery "was purchased by
the mapping institute" (Survey of Israel) from a third party, suggesting
that even the Survey of Israel's ability to sublicense it to third parties
may be constrained by upstream licensing.

### Open data portal

The `open.govmap.gov.il` subdomain suggests an open-data initiative.
However, it is unknown whether aerial imagery is part of the open-data
offering or whether its terms differ from the main portal's copyright.

Israel's data.gov.il portal provides some raw orthophoto data under open
data terms permitting redistribution. However, one forum participant noted
that "raw images from data.gov.il exhibit noticeably lower resolution
than govmap.gov.il sources."

### Attribution

No specific attribution string (credit line, provider name format) for
GovMap orthophoto imagery was found in official documentation during this
investigation.

### Conclusion (calibrated)

No public, explicit, third-party web-application license for consuming
GovMap satellite tiles was found during this investigation. This means:

- **"No public license was found during this investigation"** — CONFIRMED

This is not equivalent to:

- "Use is prohibited"

An explicit prohibition was not found. The absence of a public license is
a practical obstacle, not necessarily a legal conclusion. Obtaining
explicit written permission from the Survey of Israel remains the
appropriate path if GovMap is to be used as the satellite provider.

---

## 14. Implementation Options Considered

### Option A — Integrate GovMap legacy EPSG:2039 tiles

**Approach:** Use `cdn.govmap.gov.il` tiles with their native EPSG:2039
CRS, implementing the full custom integration.

**What would be required:**

- Change the entire Leaflet map CRS from EPSG:3857 to EPSG:2039
- Add a coordinate reprojection library (e.g., `proj4.js` and
  `proj4leaflet`) — currently not in the project
- Obtain ITM tile grid origin and resolution ladder from GovMap's
  undocumented tile cache metadata
- Implement a custom tile URL function converting Leaflet tile coordinates
  to ITM hex row/col format
- Source OSM tiles in EPSG:2039 — OSM tile servers do not provide ITM
  tiles; this would require a TMS reprojection proxy or a different
  regular-map source
- Discover and maintain cache IDs for orthophoto layers (undocumented,
  may change with imagery updates)
- Resolve the CORS situation (unconfirmed)
- Obtain a usage license (not currently publicly available)

**Complexity:** High  
**Technical risk:** High  
**Licensing risk:** High  
**Additional dependencies:** At least 2 npm packages (`proj4`, `proj4leaflet`)  
**Breaking change to existing map:** Yes — changing the map CRS affects
all existing overlays and the OSM layer

### Option B — Use official GovMap WMS or Web Mercator service

**Approach:** Use the `open.govmap.gov.il/geoserver/opendata/wms` WMS
endpoint (if orthophoto layers are available and CORS is permitted), or
use Web Mercator CDN tiles if an official URL is confirmed.

**Why this would be preferable:**

- WMS with EPSG:3857 is consumable by Leaflet's built-in `L.tileLayer.wms()` with no CRS change
- Web Mercator CDN tiles would require only a hex URL adapter (no CRS change)
- Both approaches keep the map in EPSG:3857, preserving all existing overlays
- This remains "GovMap" imagery, satisfying the original requirement

**What information is currently missing:**

- Whether `open.govmap.gov.il/geoserver/opendata/wms` is currently accessible
- Whether it includes orthophoto/satellite imagery
- Whether it supports EPSG:3857 requests
- Whether CORS is permitted
- Whether Web Mercator CDN tile URLs exist and are publicly accessible
- Licensing terms for the open data portal

**Next step if choosing this option:** Direct verification of the WMS
endpoint and/or search for official Web Mercator CDN documentation from
the Survey of Israel before writing any code.

**Complexity:** Low to medium (once endpoint is confirmed)  
**Technical risk:** Low (if endpoint is confirmed)  
**Licensing risk:** Medium (depends on open.govmap.gov.il terms)  
**Additional dependencies:** None for WMS; minimal for Web Mercator hex adapter  
**Breaking change to existing map:** No

### Option C — Replace GovMap with a Leaflet-compatible satellite provider

**Approach:** Update REQUIREMENTS.md and DESIGN.md to name a satellite
provider that serves EPSG:3857 XYZ tiles with confirmed CORS and a clear
developer license.

**Architectural simplicity:**

The layer switch becomes a standard two-line Leaflet operation:

```javascript
map.removeLayer(osmLayer);
map.addLayer(satelliteLayer);  // standard L.tileLayer('{z}/{x}/{y}')
```

No CRS change, no additional dependencies, no custom coordinate math.

**What would change:**

- REQUIREMENTS.md §4 provider name
- DESIGN.md Glossary "govmap.gov.il" entry
- One `L.tileLayer()` call in `map.ts`

**Trade-off:** The original requirement specifically names govmap.gov.il.
Replacing it changes the documented scope. A decision from the project
owner is required.

**Complexity:** Minimal  
**Technical risk:** Minimal  
**Licensing risk:** Low (depends on chosen provider's terms)  
**Additional dependencies:** None  
**Breaking change to existing map:** No

---

## 15. Decision Matrix

| Criterion | Option A (Legacy EPSG:2039) | Option B (WMS / Web Mercator) | Option C (Replace provider) |
|-----------|----------------------------|-------------------------------|------------------------------|
| Leaflet `L.tileLayer()` compatible | No — requires custom adapter | Yes (WMS via `L.tileLayer.wms()`; Web Mercator with minimal adapter) | Yes |
| CRS compatible with OSM | No — requires map CRS change | Yes (if EPSG:3857 supported) | Yes |
| Official documented endpoint | No | Partially (WMS URL known; capabilities unverified) | Depends on provider |
| Browser CORS confirmed | UNKNOWN | UNKNOWN | Depends on provider |
| Licensing clarity | UNKNOWN (no public license) | UNKNOWN (open data terms unclear) | Depends on provider |
| Implementation complexity | High | Low (once confirmed) | Minimal |
| Additional npm dependencies | At least 2 (proj4, proj4leaflet) | None | None |
| Technical risk | High | Low | Minimal |
| Existing map architecture impacted | Yes — CRS change affects everything | No | No |
| Satisfies original GovMap requirement | Yes (technically) | Yes | No — requires doc change |

---

## 16. Current Recommendation

Based on the available evidence, the following is an engineering
recommendation — it is not a legal opinion or an absolute directive.

**Do not implement the legacy GovMap EPSG:2039 tile cache for the current
prototype.** The tile URL structure, cache IDs, tile grid parameters, CORS
policy, and licensing terms are undocumented or unconfirmed. Implementing
it by reverse-engineering community observations introduces high technical
risk and unresolved licensing exposure.

**The preferred path is Option B**, subject to verification:

Before writing any implementation code, directly verify the
`open.govmap.gov.il/geoserver/opendata/wms` WMS endpoint:

1. Retrieve the GetCapabilities document
2. Confirm whether orthophoto/satellite imagery layers are present
3. Confirm whether the service responds to EPSG:3857 requests
4. Confirm whether CORS headers permit cross-origin browser access
5. Determine the applicable usage terms

If verification succeeds, the WMS integration requires minimal code and
no architecture changes.

**If Option B cannot be verified, proceed with Option C.** Replace the
provider name in REQUIREMENTS.md and DESIGN.md with an explicitly
approved satellite tile provider that has a documented Leaflet-compatible
API, confirmed CORS, and clear usage terms. The specific replacement
provider should not be selected without separately verifying its license
terms; this research document intentionally does not name one.

---

## 17. Open Questions

The following questions were not resolved during this investigation. Each
represents a prerequisite for the satellite layer implementation:

1. **Is `open.govmap.gov.il/geoserver/opendata/wms` currently accessible?**  
   The GetCapabilities request failed during this investigation.

2. **Does the WMS endpoint include orthophoto / satellite imagery layers?**  
   Not confirmed.

3. **Does the WMS support EPSG:3857 requests?**  
   Not confirmed.

4. **Are there accessible Web Mercator CDN tile URLs for GovMap orthophoto?**  
   Third-party catalogue suggests they exist; no official URL found.

5. **Does `cdn.govmap.gov.il` send CORS headers permitting cross-origin
   browser requests?**  
   Not tested.

6. **Does `open.govmap.gov.il/geoserver/opendata/wms` send CORS headers?**  
   Not tested.

7. **Is an API key or registration required for raw CDN tile access?**  
   Not documented.

8. **What are the terms of use for `open.govmap.gov.il` open data,
   specifically for aerial imagery?**  
   Not retrieved.

9. **What attribution string does GovMap require?**  
   No official attribution format found.

10. **What are the ITM tile grid origin coordinates and resolution ladder
    for the orthophoto cache?**  
    Not documented.

11. **What are the current cache IDs for orthophoto layers?**  
    Not officially published. Third-party reports suggest they are
    opaque and may change with imagery updates.

12. **What is the current imagery year / vintage in the default orthophoto
    layer?**  
    Not confirmed via official source (third-party catalogue reports
    2025 as the most recent year).

13. **What is the supported zoom range for the current orthophoto layer?**  
    Third-party report: zoom 9–19 for standard ITM layers, 6–20 for
    Web Mercator variants. Not confirmed from official source.

---

## 18. Impact on Task 08

### Current status

Task 08 is blocked only on satellite-provider selection. Every other
aspect of the proposed implementation plan is independent of this choice
and can proceed once a provider is approved.

### What remains unblocked

The proposed Task 08 implementation plan includes:

- **Base layer abstraction** — Two `L.TileLayer` instances (one OSM, one
  satellite) managed as fields on the `MapComponent`. One active at a
  time. This architecture is valid regardless of satellite provider.

- **Map / Satellite UI** — Custom Angular buttons `[ Map ] [ Satellite ]`
  in the template, positioned to avoid overlap with existing panels.
  This is purely presentational and provider-agnostic.

- **Persisted Area overlays** — `persistedItems`, `editItems`,
  `drawnItems`, and `previewItems` FeatureGroups are independent of the
  base layer. No Area data is reloaded on layer switch. This is confirmed
  correct for any EPSG:3857 satellite provider (Options B or C).

- **Area editing** — `editingArea` state, `editHandler`, and `editLayer`
  are unaffected by base-layer switching.

- **History preview** — `previewItems` FeatureGroup and all history panel
  state are unaffected by base-layer switching.

- **WebSocket / realtime behavior** — Task 07 event handling calls
  `loadViewport()` independently of the selected base layer.

- **No backend changes** — Base-layer selection is pure client-side
  presentation state. No REST mutations, no database changes, no Flyway
  migrations.

### What is blocked

- Writing the `satelliteLayer` `L.TileLayer` (or `L.tileLayer.wms()`)
  configuration — requires a confirmed tile URL or WMS endpoint.

---

## 19. Sources

| # | Title | URL | Type | Information Used | Notes |
|---|-------|-----|------|-----------------|-------|
| 1 | REQUIREMENTS.md | (project repository) | OFFICIAL | Original govmap.gov.il satellite requirement | Authoritative for project scope |
| 2 | DESIGN.md | (project repository) | OFFICIAL | govmap.gov.il named as satellite source in glossary | Authoritative for technical design |
| 3 | GovMap API — Introduction | https://api.govmap.gov.il/docs/intro | OFFICIAL (partial) | API is JavaScript embed, not tile API; registration required | Category pages returned HTTP 403 |
| 4 | GovMap API — Homepage | https://api.govmap.gov.il/ | OFFICIAL (partial) | API portal exists; docs linked; tile API not mentioned | Minimal content retrieved |
| 5 | open.govmap.gov.il WMS GetCapabilities | https://open.govmap.gov.il/geoserver/opendata/wms?request=getcapabilities&service=wms | OFFICIAL (unverified) | URL exists in search index; content not retrieved | Socket closed during fetch |
| 6 | Converting XYZ tile request to govmap.gov.il request (EPSG:2039) | https://community.esri.com/t5/arcgis-enterprise-questions/converting-xyz-tile-request-to-govmap-gov-il/td-p/1339220 | COMMUNITY (Esri forum) | EPSG:2039 confirmed; hex tile coordinate scheme confirmed; tile URL pattern `cdn.govmap.gov.il/{id}/L{n}/R{hex}/C{hex}` | Key technical source; not official |
| 7 | WebTileLayer won't work with this URL — Esri Community | https://community.esri.com/t5/arcgis-javascript-maps-sdk-questions/webtilelayer-won-t-work-with-this-url/td-p/1251220 | COMMUNITY (Esri forum) | Compatibility issues with govmap tile URLs in Esri JS SDK | Content not retrieved; referenced as confirming compatibility problem |
| 8 | Tiles samples of GovMap Israel Maps | https://allmapsoft.com/providers/193.htm | THIRD-PARTY (commercial catalogue) | Layer types, zoom ranges, JPG/PNG formats, Web Mercator variant layer existence, MapsType IDs | Not official; useful for layer inventory |
| 9 | GovMap — Bellingcat Toolkit | https://bellingcat.gitbook.io/toolkit/more/all-tools/govmap | THIRD-PARTY (OSINT toolkit) | General GovMap capabilities; registration requirement for API | Not a technical tile reference |
| 10 | Permission to use Govmap.org.il Aerial Footage | https://community-cdn.openstreetmap.org/t/permission-to-use-govmap-org-il-aerial-footage/79184 | COMMUNITY (OSM forum) | Copyright held by Survey of Israel; community license request; imagery purchased from third party; service described as "technically inaccessible by standard tile clients" | Community observation; not official |
| 11 | Using publicly available government maps (Israel) | https://community.openstreetmap.org/t/using-publicly-available-government-maps/50310 | COMMUNITY (OSM forum) | ITM projection vs WGS84 as barrier; copyright discussion | Community observation; not official |
| 12 | Mapi imagery layer (OSM forum) | https://community.openstreetmap.org/viewtopic.php?id=60369 | COMMUNITY (OSM forum) | MAPI TMS service became login-required in 2018; open data license context | Historical reference; different service than cdn.govmap.gov.il |
| 13 | New versions online maps management: OruxMaps forum | https://oruxmaps.forumotion.com/t1113p25-new-versions-online-maps-management-onlinemapsources-xml | COMMUNITY (app forum) | Older CDN URL format: `cache.govmap.gov.il/arcgiscache/MapCacheNational/Layers/_alllayers/L06/R{hex}/C{hex}.png`; hex coordinate scheme; ArcGIS cache structure | 2011 vintage; URL pattern may have changed |
| 14 | EPSG:2039 — Israel 1993 / Israeli TM Grid | https://epsg.io/2039 | TECHNICAL REFERENCE | CRS definition, proj4 parameters, datum | Authoritative for CRS specification |
| 15 | Proj4Leaflet — API | https://kartena.github.io/Proj4Leaflet/ | TECHNICAL REFERENCE | Package requirements (proj4, proj4leaflet); custom CRS approach in Leaflet | Investigated option; not approved dependency |
| 16 | Israeli Transverse Mercator — Wikipedia | https://en.wikipedia.org/wiki/Israeli_Transverse_Mercator | TECHNICAL REFERENCE | ITM projection background | General background |

---

## 20. Research Limitations

### What could not be accessed

- The full content of `api.govmap.gov.il` developer documentation
  (HTTP 403 on category pages)
- The GetCapabilities response from
  `open.govmap.gov.il/geoserver/opendata/wms` (socket closed)
- Any official technical specification for the `cdn.govmap.gov.il`
  tile cache format
- The GovMap portal's privacy and terms pages
  (`govmap.gov.il/Privacy.htm`)

### What could not be verified

- CORS headers on `cdn.govmap.gov.il` or `open.govmap.gov.il`
- Current accessibility of any govmap tile endpoint from a browser
  running at a third-party origin
- Whether email registration for `api.govmap.gov.il` also authorises
  raw tile access
- Whether Web Mercator orthophoto tile URLs exist and are accessible
- The exact cache IDs for current orthophoto layers
- The ITM tile grid origin and resolution ladder parameters

### Conclusions dependent on third-party / community information

The following conclusions rely primarily on third-party sources and
should be treated with appropriate caution:

- The `cdn.govmap.gov.il/{cache_id}/L{n}/R{hex}/C{hex}.jpg` URL pattern
  (Esri community forum, OruxMaps forum — not official documentation)
- The assertion that EPSG:2039 is used for the tile cache (consistent
  across multiple community sources, not confirmed by official docs)
- The existence of Web Mercator orthophoto variants (AllMapSoft catalogue
  only)
- The characterisation of govmap tiles as "technically inaccessible by
  standard tile clients" (single OSM forum post, 2018)

### Temporal validity

This document records findings as of **2026-10-02**. GovMap services,
tile formats, cache IDs, CORS policies, and licensing terms may change
at any time. The conclusions in this document should be re-verified
before implementation if significant time passes between this research
and the implementation decision.

### Scope of legal conclusions

This document does not constitute legal advice. Statements about
licensing and copyright reflect the absence or presence of publicly
available documentation, not a legal analysis. Compliance with Israeli
copyright law and Survey of Israel terms of service should be assessed
by a qualified person if GovMap is selected as the satellite provider.

---

## 21. Follow-up Investigation — WMS and Official API Verification

**Date of investigation:** 2026-10-02  
**Status:** Definitive findings — architectural decision required

This section records the results of a targeted follow-up investigation
performed after the initial research document was completed. The goal was
to verify whether GovMap has an official, Leaflet-compatible integration
path for satellite/orthophoto imagery.

---

### 21.1 WMS GetCapabilities — Verification

**Request:**
```
GET https://open.govmap.gov.il/geoserver/opendata/wms?SERVICE=WMS&REQUEST=GetCapabilities
```

**HTTP result:** 200 OK  
**Response Content-Type:** `text/xml; charset=utf-8`  
**WMS version:** 1.3.0 (from `version` attribute of `WMS_Capabilities` root)  
**Reproducible:** Yes (tested twice, same result)  
**Evidence source:** Direct HTTP request via `curl`  
**Confidence:** CONFIRMED

**Service metadata (from GetCapabilities):**

| Field | Value |
|-------|-------|
| Service name | GeoServer Web Map Service |
| Contact person | Survey of Israel |
| Contact email | govmap@mapi.gov.il |
| Fees | NONE |
| Access Constraints | NONE |
| GetCapabilities endpoint | `https://open.govmap.gov.il/geoserver/opendata/ows?SERVICE=WMS&` |

**CORS header confirmed:**
```
Access-Control-Allow-Origin: *
```
CORS is explicitly permitted for all origins. This is present on every
response from `open.govmap.gov.il`.

**GetMap image formats supported:**
`image/png`, `image/jpeg`, `image/gif`, `image/tiff`, `image/geotiff`,
`image/svg+xml`, `application/pdf`, `application/vnd.google-earth.kml+xml`,
and others (20 formats total).

**CRS support at service level:**
The root `Layer` element lists 7,963 CRS entries. Verified present:
- `CRS:84`
- `EPSG:4326`
- `EPSG:2039` (Israel Transverse Mercator)
- `EPSG:3857` (Web Mercator / standard Leaflet/OSM CRS) ← **CONFIRMED**

**EPSG:3857 is explicitly listed as a supported CRS.** GeoServer can
reproject layer content to Web Mercator for any layer that it serves.

---

### 21.2 WMS Layer Inventory — All Layers in opendata Workspace

The GetCapabilities document lists exactly **7 named layers** in the
`opendata` workspace. All are vector/cadastral datasets:

| Layer name | Title (Hebrew) | English description | EPSG:3857 | EPSG:2039 |
|------------|---------------|---------------------|-----------|-----------|
| `Nikuz` | אגני ניקוז | Drainage basins | No | Yes |
| `PARCEL_ALL` | חלקות | Land parcels | **Yes** | No |
| `Parcels_ITM` | חלקות ברשת ישראל | Parcels (ITM) | No | Yes |
| `SUB_GUSH_ALL` | גושים | Land registration blocks | **Yes** | No |
| `SUB_GUSH_ALL_ITM` | גושים ברשת ישראל | Blocks (ITM) | No | Yes |
| `muni_il` | רשויות מוניציפאליות | Municipal authorities | No | Yes |
| `nechalim1` | נחלים | Rivers / streams | No | Yes |

**No aerial photography, orthophoto, satellite imagery, or raster
background layer is present in the opendata workspace.**

All 7 layers are vector-geometry overlays. The WMS is a vector data
service for cadastral and administrative boundaries, not an imagery
service.

---

### 21.3 WMS GetMap — Live Test

**Request (PARCEL_ALL, EPSG:3857):**
```
GET https://open.govmap.gov.il/geoserver/opendata/wms?SERVICE=WMS
  &VERSION=1.3.0&REQUEST=GetMap&FORMAT=image/png
  &TRANSPARENT=true&LAYERS=PARCEL_ALL&CRS=EPSG:3857
  &STYLES=&WIDTH=256&HEIGHT=256
  &BBOX=3904280,3680706,3960680,3736106
```

**HTTP result:** 200 OK  
**Content-Type:** `image/png`  
**Access-Control-Allow-Origin:** `*`  
**Reproducible:** Yes  
**Evidence source:** Direct HTTP request via `curl`  
**Confidence:** CONFIRMED

This confirms the WMS GetMap operation works, CORS is unrestricted,
and image/png tiles can be fetched from a third-party origin. The
`PARCEL_ALL` layer (cadastral parcels) renders correctly in EPSG:3857.
A non-3857 layer (`Nikuz`) was also tested with `CRS:84` and returned
HTTP 200 `image/png` with the same CORS header.

**Implication:** `L.tileLayer.wms()` would work correctly against this
WMS endpoint for the vector layers that are present. EPSG:3857 is
confirmed to function.

---

### 21.4 Other GeoServer Workspaces — Probe Results

The global GeoServer endpoint (`/geoserver/ows`) returns HTTP 404,
indicating only named workspaces are routable.

Fourteen candidate workspace names were probed:

`ortho`, `aerial`, `satellite`, `imagery`, `photo`, `raster`,
`background`, `govmap`, `mapi`, `survey`, `tiles`, `basemap`,
`maps`, `layers`

**All 14 returned HTTP 404.** No additional workspace is publicly
accessible on `open.govmap.gov.il`.

**Evidence source:** Direct HTTP requests  
**Confidence:** CONFIRMED — only `opendata` is publicly exposed

---

### 21.5 WMTS Endpoint — Probe Results

**Request:**
```
GET https://open.govmap.gov.il/geoserver/opendata/gwc/service/wmts?REQUEST=GetCapabilities
```

**HTTP result:** 200 OK  
**Response body:** Empty (0 bytes)  
**Alternate path** (`/geoserver/opendata/ows?service=WMTS`): Returns
`ExceptionReport` with `No service: ( WMTS )`.

**Conclusion:** GeoWebCache is present but the WMTS endpoint returns no
capability document. WMTS is not operationally available.

**Confidence:** CONFIRMED — WMTS is not usable

---

### 21.6 api.govmap.gov.il Documentation — Background Types

**Source:** `https://api.govmap.gov.il/docs/javascript-functions/set-background`  
**HTTP result:** 200 OK  
**Evidence type:** OFFICIAL (api.govmap.gov.il is the official developer
documentation portal)

The `setBackground(backgroundTypeID)` function documentation provides
the official enumeration of background map types:

**From `setBackground()` documentation:**

| ID | Title (Hebrew) | English description |
|----|---------------|---------------------|
| 0 | רחובות ומבנים | Streets and buildings |
| **1** | **תצלום אוויר** | **Aerial photograph (current)** |
| 2 | משולב | Combined / hybrid |
| 3 | CIR | Color Infrared |
| 9 | מפה טופוגרפית | Topographic map |
| 11 | ללא רקע | No background |
| 15 | רחובות ומבנים באנגלית | Streets and buildings (English) |
| 8 | מפה היסטורית חיפה 1919 | Historical map — Haifa 1919 |
| 7 | מפה היסטורית ירושלים 1926 | Historical map — Jerusalem 1926 |
| 5 | מפה היסטורית תל אביב 1930 | Historical map — Tel Aviv 1930 |
| 6 | מפה היסטורית 1935 | Historical map — 1935 |
| 33 | תצלום אוויר 2003 | Aerial photo — 2003 |
| 34 | תצלום אוויר 2004 | Aerial photo — 2004 |
| 37 | תצלום אוויר 2005 | Aerial photo — 2005 |
| 35 | תצלום אוויר 2006 | Aerial photo — 2006 |
| 38 | תצלום אוויר 2008 | Aerial photo — 2008 |
| 36 | תצלום אוויר 2013 | Aerial photo — 2013 |
| 39 | תצלום אוויר 2019 | Aerial photo — 2019 |
| 41 | תצלום אוויר 2021 | Aerial photo — 2021 |
| 43 | תצלום אוויר 2022 | Aerial photo — 2022 |
| 42 | תצלום אוויר 2023 | Aerial photo — 2023 |

Background ID **1 (current aerial photograph)** and background ID **2
(hybrid)** confirm that GovMap's embedded map does show aerial
orthophoto imagery. Historical archive photographs dating back to 2003
are also available.

The URL function parameter `b` independently confirms the same short
list (IDs 0, 1, 2, 3, 11, 15) in the URL navigation documentation:
```
https://www.govmap.gov.il/?c=179449,663927&z=8&b=1
```

---

### 21.7 Nature of the GovMap JS API — Critical Distinction

**Source:** `https://api.govmap.gov.il/docs/javascript-functions/create-map`  
**Evidence type:** OFFICIAL

The GovMap JS API is documented as:

> "This function creates the map in an HTML page."
> `govmap.createMap(mapDivID, MapSetting);`
> "The MapSetting object must contain a **token**."

Key constraints confirmed from official documentation:

1. **Domain-specific API token is required.** The `createMap()` call
   must include `token: 'YOUR_API_TOKEN'`. Tokens are issued per domain
   by the GovMap operator.

2. **The API creates an embedded map widget.** GovMap renders its own
   map (using its own tile engine) inside the provided `<div>`. It is
   NOT an add-on layer for an existing Leaflet map.

3. **Background switching is internal to the GovMap widget.** The call
   `govmap.setBackground(1)` switches the background _inside_ the GovMap
   widget. There is no method that returns a tile URL for external use.

4. **The `aggSrid` enum lists supported CRS modes for the embedded
   widget:** `Itm: 2039`, `Wgs84: 4326`, `WebMercator: 3857`. These
   control the coordinate format used when the widget returns query
   results — they do not expose a tile URL in EPSG:3857.

5. **No `tilesURL`, `getTileURL`, or equivalent function exists** in
   the API documentation sitemap (72 pages reviewed). The API is
   exclusively a higher-level map widget API.

**Implication:** The GovMap aerial background tiles are not exposed as
accessible URLs by the official API. There is no documented path from
`govmap.setBackground(1)` to a URL that `L.tileLayer()` or
`L.tileLayer.wms()` could consume.

---

### 21.8 govmap.gov.il Main Portal — Access

Direct access to `https://www.govmap.gov.il/robots.txt` returns HTTP
403 from CloudFront. The main portal appears to be behind a WAF and
cannot be directly inspected for tile URL patterns.

---

### 21.9 Conclusion

Based on all evidence gathered across the initial and follow-up
investigations:

---

**Conclusion: C — No usable official integration path could be
verified with the available documentation/access.**

---

**What was verified:**

- `open.govmap.gov.il/geoserver/opendata/wms` is a live, CORS-enabled,
  EPSG:3857-capable WMS endpoint operated by Survey of Israel.
- That WMS contains only 7 vector/cadastral layers. It is a vector
  data overlay service. It does not contain aerial or orthophoto imagery.
- `L.tileLayer.wms()` would work against this endpoint for the vector
  layers present.
- GovMap aerial imagery (background ID 1, תצלום אוויר) does exist and
  is served by the govmap.gov.il platform.
- The official JS API (`govmap.api.js`) provides programmatic access to
  background switching within its embedded map widget.

**What could not be verified:**

- Any WMS, WMTS, or tile URL endpoint that exposes the aerial orthophoto
  background tiles to third-party use.
- An API token for `govmap.api.js` (domain registration required).
- Whether the aerial background tiles are accessible from outside the
  embedded widget at all.

**Why conclusion C applies:**

The only official interfaces are:
1. The `opendata` WMS — has no imagery
2. The `govmap.api.js` embedded widget — serves aerial imagery but is
   not a tile service; requires a token; cannot be mixed with a
   standalone Leaflet map

No documented tile URL, WMS layer, or WMTS layer for aerial/satellite
imagery was found in any official GovMap source.

---

### 21.10 Refined Assessment of Option B

The initial research document described Option B as "Use official GovMap
WMS/Web Mercator service" and marked it as the preferred path pending
verification. This follow-up verifies the WMS endpoint but finds that:

- The WMS endpoint exists and is technically sound (CORS, EPSG:3857,
  image/png, HTTP 200)
- The WMS endpoint does NOT contain aerial/satellite imagery
- It contains only vector cadastral overlay data

Option B as originally framed is therefore not viable for the satellite
base layer that Task 08 requires. The WMS could be used to overlay
cadastral data (parcels, blocks) on top of another base layer, but it
cannot serve as a satellite imagery source.

---

### 21.11 Additional Sources for Follow-up Investigation

| # | Title | URL | Type | Information used | Confidence |
|---|-------|-----|------|-----------------|------------|
| 17 | GovMap WMS GetCapabilities | `https://open.govmap.gov.il/geoserver/opendata/wms?SERVICE=WMS&REQUEST=GetCapabilities` | OFFICIAL | Full layer inventory, CRS list, service metadata, CORS confirmation | CONFIRMED — directly retrieved |
| 18 | GovMap API — setBackground() | `https://api.govmap.gov.il/docs/javascript-functions/set-background` | OFFICIAL | Background type IDs 0–43, aerial background ID 1 confirmed | CONFIRMED — HTTP 200 |
| 19 | GovMap API — createMap() | `https://api.govmap.gov.il/docs/javascript-functions/create-map` | OFFICIAL | Token requirement, embedded widget architecture, no tile URL exposure | CONFIRMED — HTTP 200 |
| 20 | GovMap API — URL functions (zoom-by-coordinates) | `https://api.govmap.gov.il/docs/intro/url-functions` | OFFICIAL | `b` parameter background type list, ITM coordinate requirement | CONFIRMED — HTTP 200 |
| 21 | GovMap API — Appendix B (enum values) | `https://api.govmap.gov.il/docs/intro/attache-b` | OFFICIAL | `aggSrid` enum: Itm=2039, Wgs84=4326, WebMercator=3857 | CONFIRMED — HTTP 200 |
| 22 | GovMap API sitemap | `https://api.govmap.gov.il/sitemap.xml` | OFFICIAL | Complete list of 72 documentation pages; no tile URL API present | CONFIRMED — HTTP 200 |

---

## 22. Second Follow-up Investigation — XYZ Endpoint Independent Verification

**Date of investigation:** 2026-10-02  
**Source document under review:** `docs/research/GOVMAP_XYZ_ORTHOPHOTO_FINDINGS.md`  
**Status:** Findings verified with material qualification — architectural decision required

This section records independent verification of the claims in
`GOVMAP_XYZ_ORTHOPHOTO_FINDINGS.md`, which was produced by inspecting
the current GovMap production application in browser DevTools.

---

### 22.1 Claims Under Review

The findings document asserts:

1. GovMap exposes current orthophoto via `https://basemaps.govmap.gov.il/tms/orto2025me_update/{z}/{x}/{y}.jpg`
2. An observed tile returned HTTP 200, `Content-Type: image/jpeg`, `Access-Control-Allow-Origin: *`
3. The production configuration declares `tileSchema: XYZ`, `projection: EPSG:3857`, `matrixSet: WebMercatorQuad`
4. The browser can request tiles directly without a backend proxy

---

### 22.2 Tile Endpoint Tests — Referer Mapping

The specific tile referenced in the findings document was tested directly via HTTP.
Every Referer variant in the table below was tested independently.

| Referer sent | HTTP result | Source |
|---|---|---|
| None (curl default) | **403** — CloudFront WAF block | Direct HTTP |
| `http://localhost:4200/` | **401** — CloudFront Function | Direct HTTP |
| `https://example.com/` | **401** — CloudFront Function | Direct HTTP |
| `https://open.govmap.gov.il/` | **401** — CloudFront Function | Direct HTTP |
| `https://api.govmap.gov.il/` | **401** — CloudFront Function | Direct HTTP |
| `https://basemaps.govmap.gov.il/` | **401** — CloudFront Function | Direct HTTP |
| `https://govmap.gov.il/` (no www) | **401** — CloudFront Function | Direct HTTP |
| `https://viewer.govmap.gov.il/` | **401** — CloudFront Function | Direct HTTP |
| `https://evil.com/govmap.gov.il` (substring) | **401** — CloudFront Function | Direct HTTP |
| `https://www.govmap.gov.il/` | **200** — valid JPEG | Direct HTTP |
| `https://www.govmap.gov.il/some/path?q=1` | **200** — valid JPEG | Direct HTTP |

The pattern is unambiguous: a CloudFront Function enforces that the
`Referer` header must be from `https://www.govmap.gov.il/`. No other
origin passes, including all other govmap.gov.il subdomains.

---

### 22.3 Confirmed Response Metadata (HTTP 200 case)

**Request:**
```
GET /tms/orto2025me_update/13/4888/3325.jpg HTTP/2
Host: basemaps.govmap.gov.il
Referer: https://www.govmap.gov.il/
User-Agent: Mozilla/5.0 ...
```

**Response headers:**
```
HTTP/2 200
content-type: image/jpeg
content-length: 25397
last-modified: Thu, 18 Jun 2026 05:48:46 GMT
server: AmazonS3
x-cache: Hit from cloudfront
via: 1.1 ...cloudfront.net (CloudFront)
x-amz-cf-pop: TLV55-P2
access-control-allow-origin: *
```

Key observations:

- **`server: AmazonS3`** — tiles are stored in Amazon S3
- **`x-amz-cf-pop: TLV55-P2`** — serving from the Tel Aviv CloudFront
  point of presence
- **`last-modified: Thu, 18 Jun 2026`** — current-year tile data
- **JPEG magic bytes `ff d8 ff e0`** — body is a valid JPEG image
- **`access-control-allow-origin: *`** — CORS header present (see
  Section 22.4 for correct interpretation)

**Reproducibility:** HTTP 200 obtained on multiple requests across the
same session. Tiles at five adjacent coordinates all returned HTTP 200
with valid JPEG bodies.

---

### 22.4 CORS Header — Correct Interpretation

The findings document states:

> "The observed tile response contains `Access-Control-Allow-Origin: *`.
> This is important because the browser can request the tiles directly
> from `basemaps.govmap.gov.il` without requiring the application's
> backend to proxy tile requests solely to work around CORS."

This conclusion is **not supported** by the evidence once the Referer
restriction is accounted for.

**What was actually observed in the DevTools investigation:**
The investigation was conducted inside the govmap.gov.il application,
where every tile request automatically carries `Referer: https://www.govmap.gov.il/`.
In that context, the tile returns HTTP 200 with `Access-Control-Allow-Origin: *`.

**What happens from a third-party application:**
When a browser loads our Angular application (from any origin other
than `www.govmap.gov.il`), tile requests made by Leaflet carry that
application's URL as the `Referer`. These requests return HTTP 401.

**Additional finding:** The `Access-Control-Allow-Origin: *` header
is present even on the HTTP 401 response. Its presence therefore does
not indicate that tiles are accessible; it only indicates that if a
response were served, cross-origin scripts could read it. The 401 body
is empty.

The CORS header is not the blocking factor. The Referer restriction is.

---

### 22.5 Access Restriction Mechanism

The HTTP 401 responses carry:
```
x-cache: FunctionGeneratedResponse from cloudfront
```

This indicates a **CloudFront Function** — a lightweight JavaScript
function running at the CloudFront edge — is generating the 401 before
the request reaches S3. The function inspects the `Referer` header and
rejects any request not originating from `https://www.govmap.gov.il/`.

The 403 response (no Referer) comes from a separate CloudFront WAF
rule or distribution configuration that blocks requests with no Referer
before the CloudFront Function is evaluated.

---

### 22.6 What a Browser Sends When Loading Leaflet Tiles

When a browser renders our Angular application and Leaflet calls
`L.tileLayer()`:

- Tiles are loaded as `<img>` elements (no CORS mode by default)
- The browser sets `Referer` to the current page's URL per the active
  Referrer-Policy (typically `strict-origin-when-cross-origin` in
  modern Chrome, which sends the origin of the current page on
  cross-origin requests)
- The Referer for our application will be `http://localhost:4200` in
  development, or the production deployment origin
- Neither of these satisfies the `www.govmap.gov.il` Referer requirement
- Every tile request from our application returns **HTTP 401**

Setting `crossOrigin: 'anonymous'` on the Leaflet TileLayer would add
an `Origin` header but does not change the Referer value. The 401
response would still be returned.

There is no browser mechanism that allows an application at one origin
to set the `Referer` header to a different origin on image requests.

---

### 22.7 CRS and Tile Schema — Verification

**CRS: EPSG:3857 / WebMercatorQuad**
CONFIRMED from production configuration metadata as documented in the
findings document. The previous research concern about EPSG:2039 does
**not** apply to this endpoint. This is the most important correction
to the original research conclusion.

**Tile schema: XYZ**
CONFIRMED from production configuration (`tileSchema: XYZ`). Also
consistent with geographic tile coordinates: z=13/x=4888/y=3325 maps
to approximately 34.63–34.67°E, 31.97–32.0°N — central Israel —
under XYZ addressing (Y increases southward). Under TMS Y-flipping the
same y=3325 would map to ~23°N (East Africa), which is not where
Israel is. Geographic consistency confirms XYZ orientation.

**Consequence:** No `tms: true` in Leaflet, no EPSG:2039 reprojection,
no proj4 dependency, no custom CRS. The Leaflet map CRS does not need
to change. OSM and GovMap orthophoto tiles are both EPSG:3857 and
would coexist on the existing Leaflet map without any coordinate
conversion. Drawn Area geometries (stored as EPSG:4326) remain
correctly positioned regardless of base layer selection.

---

### 22.8 Service Reality

The tile service is real:

- 5 adjacent tiles at zoom 13, covering consecutive geographic cells in
  central Israel, all return HTTP 200 with valid JPEG bodies and
  consistent content lengths (24–28 KB per tile)
- A zoom-12 parent tile at x=2444/y=1662 also returns HTTP 200
- S3 + CloudFront with a Tel Aviv PoP confirms this is purpose-built
  geographic tile infrastructure
- `last-modified: Jun 2026` confirms current-year imagery

This is not a single cached object. It is a live tile service.

---

### 22.9 Revised Conclusion

**Previous conclusion (Section 21.9):**

> C — No usable official integration path could be verified.

**What the new evidence changes:**

The original Conclusion C was based on:
1. WMS having no aerial imagery (unchanged — still true)
2. The `open.govmap.gov.il` endpoint having no orthophoto layer
   (unchanged — still true)
3. The assumption that GovMap aerial tiles required EPSG:2039
   (**resolved** — the current XYZ service is EPSG:3857)
4. No directly usable endpoint being found
   (**partially resolved** — an endpoint exists and is real)

**What the new evidence does not change:**

The endpoint is not officially documented for third-party use.
Direct tile consumption from a third-party application is blocked
by a CloudFront Function enforcing a `Referer: www.govmap.gov.il`
restriction. The endpoint is used by the GovMap production application
but its accessibility from any other origin is not supported and not
documented.

**Revised characterization:**

> No officially documented and unrestricted integration path has been
> verified. The GovMap production application uses a GovMap-operated
> EPSG:3857 XYZ orthophoto tile service (`basemaps.govmap.gov.il`)
> that is architecturally compatible with standard Leaflet integration.
> However, that service enforces a CloudFront Function Referer
> restriction (`Referer: https://www.govmap.gov.il/` required) that
> blocks tile requests from third-party applications. The `Access-Control-Allow-Origin: *`
> header observed in DevTools was recorded from within the govmap.gov.il
> domain and does not apply to requests from other origins.
>
> A backend proxy could technically bypass the Referer restriction but
> would constitute undocumented use of a service not offered for
> third-party consumption, and would introduce architectural complexity.

**Conclusion C therefore remains applicable** with this qualification:

The CRS barrier is resolved. The tile format is compatible. The
blocking factor has changed from "CRS incompatibility and missing
endpoint" to "access restriction on a real, CRS-compatible endpoint."

---

### 22.10 Additional Sources

| # | Title | URL | Type | Information used | Confidence |
|---|-------|-----|------|-----------------|------------|
| 23 | GovMap XYZ tile — direct HTTP test (200) | `https://basemaps.govmap.gov.il/tms/orto2025me_update/13/4888/3325.jpg` | Direct HTTP | Referer restriction confirmed; JPEG body confirmed; S3+CloudFront TLV infrastructure | CONFIRMED — directly tested |
| 24 | GovMap XYZ tile — Referer variants (401) | Same URL, 9 Referer variants tested | Direct HTTP | Only `www.govmap.gov.il` Referer passes; all other origins including subdomains return 401 | CONFIRMED — directly tested |
| 25 | GOVMAP_XYZ_ORTHOPHOTO_FINDINGS.md | `docs/research/GOVMAP_XYZ_ORTHOPHOTO_FINDINGS.md` | DevTools investigation | Production config: XYZ, EPSG:3857, WebMercatorQuad; layer metadata: ortho_2025, zoom 4–20, isPublicLayer=true | CONFIRMED (config) / INFERRED (third-party accessibility claim not verified) |

---

## Section 23 — GovMap Official JavaScript API Investigation

### 23.1 Purpose

This section investigates whether the official GovMap JavaScript API
(`api.govmap.gov.il`) provides a compliant integration path for
displaying GovMap satellite/aerial imagery in the application as a
replacement or complement to the blocked XYZ tile endpoint.

The investigation directly fetched and parsed the official GovMap API
documentation site (`api.govmap.gov.il`), which is a publicly accessible
Docusaurus-based documentation site.

Twelve specific questions were identified prior to the investigation:

1. What DOM structure does `createMap()` produce?
2. Can the GovMap JS widget be embedded in an Angular component?
3. Does the API expose aerial imagery as a background mode?
4. Is background `1` the current 2025 orthophoto?
5. Can polygons be displayed programmatically?
6. In what coordinate system does the API accept and return coordinates?
7. What events does the API expose, and what CRS do their payloads use?
8. Is a domain-specific token required, and how is it obtained?
9. Does the API expose raw tile URLs usable as Leaflet layers?
10. Is a dual-engine architecture (Leaflet for normal, GovMap widget for
    satellite) feasible?
11. What coordinate transformation would be required?
12. How does the GovMap zoom scale map to Leaflet zoom?

---

### 23.2 API Architecture

The GovMap JavaScript API is a **host-page widget API**, not a tile
service. Its integration model is:

```
Host page
  │
  ├── <script src="https://www.govmap.gov.il/govmap/api/govmap.api.js">
  │       Loads a global `govmap` object into the host page's JS context.
  │       Script is fetched from govmap.gov.il servers on every page load.
  │       Documentation explicitly warns: do NOT download locally.
  │
  └── <div id="map" style="width:600px;height:600px;"></div>
          govmap.createMap('map', { token: '...', ... })
          GovMap renders its full map widget into this div.
```

The map widget is rendered **directly into the host page's DOM** — it is
not sandboxed inside an iframe. The `govmap.api.js` script runs in the
same JavaScript context as the host application.

The HTML embed method (described separately in the docs) uses an iframe.
The **JavaScript API method does not use an iframe**.

---

### 23.3 `createMap()` Function

**Signature:**

```javascript
govmap.createMap(mapDivId, MapSettings);
```

**First parameter:** string — the `id` of the div element that will
contain the map.

**Second parameter:** settings object. The `token` field is mandatory.
All other fields are optional.

**Selected settings parameters relevant to this investigation:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `token` | string | Domain-specific API key. **Required.** |
| `background` | string | Background map ID at initialization. `"1"` = aerial photo. |
| `center` | object | Initial center: `{ x: number, y: number }` in EPSG:2039. |
| `level` | number | Initial zoom level (0–10 GovMap scale). |
| `layers` | string[] | Named overlay layers to show. |
| `onLoad` | function | Callback after map initialization completes. |
| `onClick` | function | Click event callback. |
| `onPan` | function | Pan/move event callback. |
| `isEmbeddedToggle` | boolean | If `true`, background toggle switches only between streets/aerial. |
| `bgButton` | boolean | Whether to show the background-change button. |
| `zoomButtons` | boolean | Whether to show zoom buttons. |
| `extent` | object | Lock the map to a geographic extent. |

**Example from documentation:**

```javascript
govmap.createMap('map', {
  token: 'YOUR_API_TOKEN',
  layers: ["GASSTATIONS", "bus_stops", "PARCEL_ALL"],
  showXY: true,
  identifyOnClick: true,
  isEmbeddedToggle: false,
  background: "1",
  layersMode: 1,
  zoomButtons: false,
  onLoad: () => listener()
});
```

Multiple maps can be created simultaneously with different `mapDivId`
values, but each map must finish initialization before the next is
created.

---

### 23.4 Aerial Imagery and Background Control

**Background ID `1`** is documented as **תצלום אוויר** (aerial photo),
which is the current aerial background.

The `setBackground()` function changes the background at runtime:

```javascript
govmap.setBackground(backgroundTypeID);
```

**Complete documented background type table:**

| ID | Name (Hebrew) | Description |
|----|---------------|-------------|
| `0` | רחובות ומבנים | Streets and buildings (default map) |
| `1` | תצלום אוויר | Aerial photo (current) |
| `2` | משולב | Combined |
| `3` | CIR | CIR (false-color infrared) |
| `9` | מפה טופוגרפית | Topographic map |
| `11` | ללא רקע | No background |
| `15` | רחובות ומבנים באנגלית | Streets (English labels) |
| `8` | מפה היסטורית חיפה 1919 | Historic Haifa 1919 |
| `7` | מפה היסטורית ירושלים 1926 | Historic Jerusalem 1926 |
| `5` | מפה היסטורית תל אביב 1930 | Historic Tel Aviv 1930 |
| `6` | מפה היסטורית 1935 | Historic 1935 |
| `33` | תצלום אוויר 2003 | Aerial photo 2003 |
| `34` | תצלום אוויר 2004 | Aerial photo 2004 |
| `37` | תצלום אוויר 2005 | Aerial photo 2005 |
| `35` | תצלום אוויר 2006 | Aerial photo 2006 |
| `38` | תצלום אוויר 2008 | Aerial photo 2008 |
| `36` | תצלום אוויר 2013 | Aerial photo 2013 |
| `39` | תצלום אוויר 2019 | Aerial photo 2019 |
| `41` | תצלום אוויר 2021 | Aerial photo 2021 |
| `43` | תצלום אוויר 2022 | Aerial photo 2022 |
| `42` | תצלום אוויר 2023 | Aerial photo 2023 |

**Observation:** The documented historical list extends only to 2023
(ID 42). There is no ID explicitly labeled "2024" or "2025" in the
published documentation. Background ID `1` ("current aerial") is
consistent with it serving the most recently published nationwide
orthophoto — which the production DevTools investigation (Section 22)
identified as the `ortho_2025` layer. The JS API hides this version
detail behind the stable ID `1`.

---

### 23.5 Token Requirement and Acquisition

The documentation states:

> "Only a registered user may use JavaScript functions. A registered
> user is one who has received a Token. The Token must be entered when
> creating the map. **The issued Token is associated with the domain
> on which the page sits and is NOT valid for use on another domain.**
> If the hosting page's domain does not have permission to activate the
> API (wrong domain, wrong user code, permission not granted or revoked
> by מפ"י) — an appropriate error message will be displayed and the
> map will ignore the function call."

Key implications:

- A token is **required for all JS API functionality**.
- The token is **domain-bound** — a token issued for
  `snapland.example.com` will not work on `localhost:4200` or any
  other domain.
- Separate tokens are needed for each deployment domain and for
  local development.
- The documentation does not describe the registration process. Token
  acquisition requires contacting מפ"י (Survey of Israel / Center for
  Mapping Israel) directly.
- The public documentation does not indicate whether tokens are issued
  to any applicant or only to government entities, or whether there is
  a cost.

**This is an unresolved prerequisite.** Token availability is not
confirmed for a private/commercial third-party application.

---

### 23.6 Geometry Display API

The `displayGeometries()` function adds geometry overlays to the GovMap
widget:

```javascript
govmap.displayGeometries(data).then(function(response) {
  console.log(response.data); // array of geometry IDs
});
```

**Input parameter object fields:**

| Field | Type | Description |
|-------|------|-------------|
| `wkts` | string[] | WKT geometry strings. |
| `names` | string[] | Names per geometry. |
| `geometryType` | enum | `govmap.geometryType.POLYGON` etc. |
| `defaultSymbol` | object | Style (outlineColor, fillColor, outlineWidth). |
| `symbols` | object[] | Per-geometry styles. |
| `clearExistings` | boolean | Remove existing geometries before drawing. |
| `showBubble` | boolean | Show info popup on click (default `true`). |
| `data` | object | Tooltip, header, and bubble content. |

**CRITICAL — Coordinate system:**

The WKT coordinates in the documentation examples are in **EPSG:2039
(Israeli TM Grid / New Israeli Grid)**:

```
POINT(179714.32 663772.17)   ← EPSG:2039, not EPSG:4326
POINT(179621.05 663704.57)
```

The GovMap widget does not document EPSG:4326 input for `displayGeometries`.
All documented examples use EPSG:2039 metric coordinates.

The application currently stores polygon geometry in **EPSG:4326** (WGS84)
as required by the database design. Displaying application polygons in the
GovMap widget would require **EPSG:4326 → EPSG:2039 coordinate
transformation** for every polygon, for every viewport reload.

Additional geometry management functions available:

```javascript
govmap.clearGeometriesByName(names);
govmap.clearGeometriesById(ids);
govmap.clearDrawings();
```

---

### 23.7 Event System

The GovMap API provides `onEvent()` for subscribing to map events:

```javascript
govmap.onEvent(govmap.events.PAN).progress(callback);
govmap.onEvent(govmap.events.EXTENT_CHANGE).progress(callback);
govmap.onEvent(govmap.events.CLICK).progress(callback);
```

**Available events:**

```javascript
govmap.events = {
  PAN: 0,
  EXTENT_CHANGE: 1,
  CLICK: 3,
  DOUBLE_CLICK: 4,
  MOUSE_MOVE: 5,
  MOUSE_OVER: 8
}
```

**CRITICAL — All event coordinates are returned in EPSG:2039:**

PAN event response:
```json
{
  "extent": {
    "type": "extent",
    "xmin": -146290.96,
    "ymin": 532321.24,
    "xmax": 409335.15,
    "ymax": 929197.03,
    "spatialReference": { "wkid": 2039 }
  }
}
```

CLICK event response:
```json
{
  "mapPoint": {
    "type": "point",
    "x": 225431.29,
    "y": 575156.21,
    "spatialReference": { "wkid": 2039 }
  }
}
```

There is no documented option to receive coordinates in EPSG:4326 or
EPSG:3857 from the event callbacks. Any coordinate exchange between the
GovMap widget and the rest of the application requires EPSG:2039 ↔
EPSG:4326 conversion.

---

### 23.8 Zoom Level Differences

The GovMap `zoomToXY()` function uses a `level` parameter on a **0–10
scale**, where:

- `0` = small scale, low detail
- `10` = large scale, high detail (street level)

Zoom to a location example from documentation:

```javascript
govmap.zoomToXY({ x: 176085, y: 657600, level: 10, marker: true });
```

`getCenter()` returns coordinates as a plain `[x, y]` array in EPSG:2039:

```javascript
govmap.getCenter().then(function(response) {
  console.log(response); // [184710.39, 665906.35]
});
```

Leaflet uses a **0–19/20 zoom scale** (or higher depending on the layer's
`maxZoom`). There is no documented conversion table between GovMap levels
and Leaflet zoom levels. The correspondence is not linear and depends on
display scale, not on tile zoom integers. Synchronizing zoom between a
GovMap widget and a Leaflet map would require empirical calibration.

---

### 23.9 Tile Layer Exposure

The GovMap JavaScript API does **not** expose a tile URL accessor.

There is no documented function of the form `getTileUrl()`,
`getBackgroundLayer()`, or equivalent that returns the underlying tile
endpoint as a URL template.

The API is designed as a black-box map widget — it renders tiles
internally without exposing the tile infrastructure to the host page.

This is consistent with the findings in Section 22: the production
application's tile endpoint (`basemaps.govmap.gov.il`) is enforced with
a Referer restriction that the API widget satisfies internally (since the
widget's HTTP requests use its own Referer mechanism), but which
third-party code cannot satisfy.

**Conclusion:** There is no supported way to extract a GovMap tile URL
from the JS API and inject it into Leaflet.

---

### 23.10 Angular Integration Assessment

Embedding the GovMap JS widget inside an Angular component is technically
possible but involves non-trivial integration work:

**Script loading:**
The `govmap.api.js` script must be loaded before `createMap()` is called.
In Angular, this requires either:
- Adding the script tag to `src/index.html` (always loaded, even on pages
  that don't show the map), or
- Dynamically creating a `<script>` element in a service or component and
  waiting for its `onload` event before calling `createMap()`.

**TypeScript types:**
No npm package is available. The `govmap` global object must be declared:

```typescript
declare var govmap: any;
```

**Angular zone:**
The `govmap.api.js` callbacks (event handlers, promise resolutions) run
outside Angular's zone. Any state update triggered by a GovMap callback
must be wrapped in `NgZone.run()` for Angular change detection to function
correctly.

**Component lifecycle:**
`createMap()` must be called after the Angular component's `@ViewChild`
div element is present in the DOM (i.e., in `ngAfterViewInit()`). This
is the same lifecycle constraint that Leaflet already uses in the existing
`MapComponent`.

**External dependency:**
The `govmap.api.js` script is loaded from `govmap.gov.il` servers at
runtime. The application has a hard runtime dependency on the external
server's availability. This is different from `basemaps.govmap.gov.il`
tile requests, which are stateless cache hits — the JS script load is
a blocking dependency.

---

### 23.11 Dual-Engine Architecture Assessment

The dual-engine approach would maintain two map instances simultaneously:

```
Angular MapComponent
  │
  ├── #leafletDiv (Leaflet + OSM)          ← shown when mode = MAP
  └── #govmapDiv (GovMap JS widget)        ← shown when mode = SATELLITE
```

Visibility is toggled with CSS (`display: none` / `display: block`).
Both instances remain mounted in the DOM to preserve state when switching.

**Required work:**

| Concern | Description |
|---------|-------------|
| Token acquisition | Must contact Survey of Israel before development can begin. Token may not be issued to a private application. |
| Script loading | Dynamic or static loading of `govmap.api.js` with lifecycle management. |
| EPSG:4326 → EPSG:2039 | All application polygons must be transformed for `displayGeometries()`. A coordinate transformation library (proj4js or equivalent) must be added. |
| EPSG:2039 → EPSG:4326 | Event coordinates returned in EPSG:2039 must be transformed back to EPSG:4326 for application use (e.g., viewport-based area queries). |
| Zoom synchronization | GovMap 0–10 scale ≠ Leaflet 0–19 scale. Empirical calibration required. |
| Angular zone | All GovMap callbacks must be wrapped in `NgZone.run()`. |
| Polygon sync | Application polygons must be re-rendered in the GovMap widget via `displayGeometries()` on every viewport change or WebSocket update. |
| Draw/edit | Drawing and editing currently uses `leaflet-draw`. Drawing mode is not available on the GovMap widget side; drawing must remain Leaflet-only, requiring the user to switch to map mode for editing. |
| External script | Hard runtime dependency on govmap.gov.il script availability. |

**Total new scope introduced by the dual-engine approach:**

- New external dependency (proj4js or equivalent)
- New TypeScript declarations
- Significant additions to `MapComponent`
- Coordinate transformation logic for every polygon on every viewport load
- Viewport-sync bridge between two independent map engines
- Empirical zoom-level calibration
- Token acquisition process with an external government entity

This exceeds the scope of a single feature task.

---

### 23.12 Summary: Answers to the 12 Investigation Questions

| # | Question | Answer |
|---|----------|--------|
| 1 | What DOM structure does `createMap()` produce? | Renders GovMap widget directly into the specified `<div>` in the host page's DOM. Not an iframe. |
| 2 | Can it be embedded in an Angular component? | Yes, with caveats: external script load, `declare var govmap: any`, `NgZone.run()` for callbacks, `ngAfterViewInit()` lifecycle constraint. |
| 3 | Does it expose aerial imagery as a background? | Yes. Background ID `1` = current aerial photo (תצלום אוויר). |
| 4 | Is background `1` the current 2025 orthophoto? | Documented as "current aerial." The listed historical aerials end at 2023 (ID 42). ID `1` is consistent with the most recent ortho (which production DevTools identified as `orto2025me_update`), but the version is not explicitly stated in the API docs. |
| 5 | Can polygons be displayed programmatically? | Yes, via `displayGeometries()`. Returns geometry IDs. Can clear by name or ID. |
| 6 | In what CRS does the API accept geometry coordinates? | **EPSG:2039 (Israeli TM Grid)**. WKT examples in documentation use ITM metric coordinates. EPSG:4326 input is not documented. |
| 7 | What events are available, and what CRS do payloads use? | PAN, EXTENT_CHANGE, CLICK, DOUBLE_CLICK, MOUSE_MOVE, MOUSE_OVER. **All event coordinates are returned in EPSG:2039** (`wkid: 2039` in all documented response examples). |
| 8 | Is a domain-specific token required? | Yes. Token is mandatory, domain-bound, and must be obtained from מפ"י/Survey of Israel. Registration process is not self-service. Token for `localhost` would need separate issuance. |
| 9 | Does the API expose raw tile URLs for Leaflet? | No. The API is a black-box widget. No tile URL accessor is documented. |
| 10 | Is a dual-engine architecture feasible? | Technically feasible. Requires: token, proj4js, NgZone integration, viewport sync, zoom calibration. Significant scope beyond a single feature task. |
| 11 | What coordinate transformation is required? | EPSG:4326 ↔ EPSG:2039 for all geometry display and all event coordinates. Requires a coordinate transformation library. |
| 12 | How does GovMap zoom map to Leaflet zoom? | GovMap uses a 0–10 level scale; Leaflet uses 0–19/20. No documented conversion. Empirical calibration required. |

---

### 23.13 Recommendation

**Recommendation: B**

> Keep Leaflet and document that no compliant, architecturally compatible
> integration path for GovMap satellite imagery has been verified within
> the scope of the current Task 08 design.

**Rationale:**

Two integration paths have been fully investigated:

**Path 1 — Direct XYZ tile integration** (Sections 21–22):
- Tile endpoint exists: `basemaps.govmap.gov.il/tms/orto2025me_update`
- CRS compatible: EPSG:3857/WebMercatorQuad
- **Blocked**: CloudFront Function Referer restriction returns HTTP 401
  for all origins other than `https://www.govmap.gov.il/`
- No official documentation for this endpoint
- Bypassing the restriction through a backend proxy would be undocumented
  use, which was explicitly excluded from consideration

**Path 2 — GovMap JavaScript API** (this section):
- Official documented API
- Can display aerial imagery (background `1`)
- **Unresolved prerequisite**: Domain-specific token from Survey of
  Israel — not yet obtained and not self-service
- **Architectural incompatibility**: The API renders a self-contained
  GovMap widget; it does not expose tiles for use in Leaflet
- **Coordinate system mismatch**: API uses EPSG:2039 throughout; the
  application uses EPSG:4326 throughout; a transformation library is needed
- **Scope**: The dual-engine architecture, coordinate transformation,
  Angular integration, and zoom calibration together constitute a
  significant expansion beyond the originally scoped "add a base layer
  toggle"

**What Recommendation B means in practice:**

The application can implement the layer-switching UI using:

- OpenStreetMap (existing)
- A placeholder satellite option with a user-visible message (e.g.,
  "Satellite imagery requires a GovMap API token") until a token is
  obtained, OR
- A deferred Task 08 implementation pending token acquisition and
  architectural planning

The Leaflet-based architecture, polygon rendering, drawing, editing,
and WebSocket synchronization remain unchanged.

**What would change Recommendation B to Recommendation A:**

1. Confirmation that a GovMap API token can be obtained for this domain
2. Confirmation of the token for a local development domain
3. Explicit approval to add `proj4js` (or equivalent) as a dependency
4. Explicit scope expansion to include the dual-engine architecture,
   viewport synchronization, and EPSG:2039 ↔ EPSG:4326 conversion layer

---

### 23.14 Additional Sources

| # | Title | URL | Type | Information used | Confidence |
|---|-------|-----|------|-----------------|------------|
| 26 | GovMap API documentation — Introduction | `https://api.govmap.gov.il/docs/intro` | Official public docs | Three integration methods; embed model overview | CONFIRMED — directly fetched |
| 27 | GovMap API documentation — JavaScript functions overview | `https://api.govmap.gov.il/docs/intro/javascript-functions` | Official public docs | Script URL; `createMap()` usage; token warning; example code | CONFIRMED — directly fetched |
| 28 | GovMap API documentation — `createMap()` | `https://api.govmap.gov.il/docs/javascript-functions/create-map` | Official public docs | Full parameter table; background/token/layers/center parameters | CONFIRMED — directly fetched |
| 29 | GovMap API documentation — `setBackground()` | `https://api.govmap.gov.il/docs/javascript-functions/set-background` | Official public docs | Complete background type ID table; setBackground usage | CONFIRMED — directly fetched |
| 30 | GovMap API documentation — `displayGeometries()` | `https://api.govmap.gov.il/docs/javascript-functions/display-geometries` | Official public docs | WKT input format; EPSG:2039 coordinates in examples; geometry types | CONFIRMED — directly fetched |
| 31 | GovMap API documentation — `onEvent()` | `https://api.govmap.gov.il/docs/javascript-functions/on-event` | Official public docs | Event types; EPSG:2039 coordinates in all response examples | CONFIRMED — directly fetched |
| 32 | GovMap API documentation — `zoomToXY()` | `https://api.govmap.gov.il/docs/javascript-functions/zoom-to-xy` | Official public docs | EPSG:2039 input; 0–10 level scale | CONFIRMED — directly fetched |
| 33 | GovMap API documentation — `getCenter()` | `https://api.govmap.gov.il/docs/javascript-functions/get-center` | Official public docs | Returns `[x, y]` in EPSG:2039 | CONFIRMED — directly fetched |
| 34 | GovMap API documentation — Appendix B (enum definitions) | `https://api.govmap.gov.il/docs/intro/attache-b` | Official public docs | `govmap.events`, `govmap.geometryType`, `govmap.aggSrid` (confirms Itm=2039, Wgs84=4326, WebMercator=3857) | CONFIRMED — directly fetched |
