# GovMap XYZ Orthophoto — Investigation Findings

## 1. Purpose

This document records the findings from the investigation of GovMap satellite / orthophoto integration with the project's Leaflet-based map.

The original concern was that GovMap imagery might only be available through a non-standard tile cache and/or EPSG:2039, which would make direct integration with the existing Leaflet + OpenStreetMap setup difficult.

Further investigation of the current GovMap production application showed that this assumption is not valid for the current orthophoto service.

GovMap currently exposes orthophoto imagery through a public XYZ tile service using Web Mercator (EPSG:3857).

---

## 2. Original Problem

The application requirement is to support switching between:

1. OpenStreetMap
2. GovMap satellite / aerial imagery

The application currently uses Leaflet.

OpenStreetMap uses the standard Web Mercator tile model:

- CRS: EPSG:3857
- Tile schema: XYZ

Earlier GovMap investigation found references to:

- EPSG:2039
- ArcGIS compact cache
- non-standard tile addressing
- WMS endpoints
- possible CRS incompatibility

This raised a potential architectural problem:

> If GovMap imagery were only available in EPSG:2039, switching between OSM and GovMap inside the same Leaflet map would require reprojection or a different integration approach.

The current GovMap production application was therefore inspected directly.

---

## 3. GovMap Production Application Investigation

The current GovMap web application was opened in a browser and inspected using Chrome DevTools → Network.

The application loads its available base layers dynamically.

The returned base-layer configuration contains a raster service with the following relevant properties:

```json
{
  "name": "background",
  "rasterCapabilities": {
    "wms": "/geoserver/ows?service=WMS&version=1.3.0",
    "wmts": "/geoserver/gwc/service/wmts?service=WMTS&version=1.1.1",
    "serverless": "https://basemaps.govmap.gov.il",
    "tileSchema": "XYZ",
    "imageFormat": "image/png"
  },
  "minZoom": 1,
  "maxZoom": 28,
  "projection": "EPSG:3857",
  "matrixSet": "WebMercatorQuad"
}
```

### Important findings

The production configuration explicitly declares:

```text
tileSchema = XYZ
projection = EPSG:3857
matrixSet = WebMercatorQuad
serverless = https://basemaps.govmap.gov.il
```

This is directly compatible with the default Leaflet / OpenStreetMap coordinate system.

No EPSG:2039 reprojection is required for this service.

---

## 4. GovMap Orthophoto 2025 Layer

The returned base-layer configuration contains the following orthophoto layer:

```text
id = ortho_2025
caption = אורתופוטו 2025
serviceLayerId = tms/orto2025me_update
minZoom = 4
maxZoom = 20
tileSchema = XYZ
rasterImageFormat = image/jpg
group = תצלום אויר
captureDate = 2025-01-01
isPublicLayer = true
```

The layer credits identify:

```text
המרכז למיפוי לישראל
```

The layer metadata also contains keywords including:

```text
XYZ
Ortho
orthophoto
אורתו
אורתופוטו
לוויין
```

This identifies the layer as the current GovMap nationwide orthophoto background.

---

## 5. Actual Tile Request Observed

Chrome DevTools showed that the GovMap production application requests tiles directly from:

```text
https://basemaps.govmap.gov.il
```

An actual request observed while viewing the orthophoto layer was:

```text
https://basemaps.govmap.gov.il/tms/orto2025me_update/13/4888/3325.jpg
```

The request returned:

```text
HTTP 200 OK
Content-Type: image/jpeg
```

Therefore the effective tile URL template is:

```text
https://basemaps.govmap.gov.il/tms/orto2025me_update/{z}/{x}/{y}.jpg
```

Despite the path containing `/tms/`, the GovMap configuration explicitly declares:

```text
tileSchema = XYZ
```

Therefore the path name must not be interpreted as proof of a traditional TMS Y-axis convention.

The service should initially be integrated as standard XYZ.

---

## 6. CORS

The observed tile response contains:

```text
Access-Control-Allow-Origin: *
```

This is important because the browser can request the tiles directly from:

```text
basemaps.govmap.gov.il
```

without requiring the application's backend to proxy tile requests solely to work around CORS.

Other observed response characteristics included:

```text
Server: AmazonS3
Via: CloudFront
X-Cache: Hit from cloudfront
```

This indicates that the tiles are currently distributed through CDN infrastructure.

---

## 7. Coordinate Reference System

The most important result of this investigation is the CRS.

GovMap's current raster background service explicitly reports:

```text
projection = EPSG:3857
matrixSet = WebMercatorQuad
```

OpenStreetMap also uses Web Mercator.

Therefore:

```text
OpenStreetMap
      |
      +-- EPSG:3857
      +-- XYZ
             |
             v
        Leaflet Map
             ^
             |
      +------+------+
      |             |
GovMap Orthophoto
EPSG:3857
XYZ
```

Both layers can use the same Leaflet map CRS.

### Consequence

No coordinate conversion between EPSG:4326 / EPSG:3857 / EPSG:2039 needs to be implemented specifically for switching the base layer.

Leaflet can continue operating normally using its default CRS.

---

## 8. Proposed Leaflet Integration

The GovMap layer can initially be represented as a standard Leaflet `TileLayer`.

Example:

```typescript
const govMapOrtho = L.tileLayer(
  'https://basemaps.govmap.gov.il/tms/orto2025me_update/{z}/{x}/{y}.jpg',
  {
    minZoom: 4,
    maxZoom: 20,
    attribution: 'המרכז למיפוי לישראל'
  }
);
```

The following should NOT initially be configured:

```typescript
tms: true
```

because the GovMap production metadata explicitly identifies the tile schema as:

```text
XYZ
```

The implementation should be verified visually against the current GovMap application.

---

## 9. Layer Switching

The application can maintain two Leaflet base layers:

```typescript
const osmLayer = L.tileLayer(/* OSM configuration */);

const govMapOrthoLayer = L.tileLayer(
  'https://basemaps.govmap.gov.il/tms/orto2025me_update/{z}/{x}/{y}.jpg',
  {
    minZoom: 4,
    maxZoom: 20,
    attribution: 'המרכז למיפוי לישראל'
  }
);
```

Switching should replace only the base tile layer.

The following application state must remain unchanged:

- current map center
- current zoom
- persisted Area polygons
- temporary drawing layers
- Area selection
- WebSocket updates
- editing functionality
- history preview layers

Conceptually:

```text
                     Leaflet Map
                         |
             +-----------+-----------+
             |                       |
       Base Map Layer          Application Layers
             |                       |
      +------+------+
      |             |
     OSM       GovMap Ortho
```

Base-map selection should remain independent from application GIS data.

---

## 10. Previous EPSG:2039 Findings

Earlier investigation identified GovMap-related resources using EPSG:2039 and non-standard cache structures.

Those findings should not simply be deleted because they may describe:

- older GovMap infrastructure
- other GovMap services
- other datasets
- legacy tile caches
- ITM-specific services

However, they are no longer relevant to the implementation decision for the current orthophoto base layer.

The important distinction is:

```text
GovMap as a platform
        |
        +-- Various GIS services
        |      +-- EPSG:2039
        |      +-- WMS
        |      +-- other services
        |
        +-- Current raster basemap service
               +-- EPSG:3857
               +-- WebMercatorQuad
               +-- XYZ
```

Therefore the earlier conclusion:

> "GovMap satellite imagery requires EPSG:2039 reprojection"

is not valid for the currently discovered `ortho_2025` base layer.

---

## 11. WMS Investigation

The official GovMap GeoServer WMS endpoint was also tested:

```text
https://open.govmap.gov.il/geoserver/opendata/wms
```

`GetCapabilities` successfully returned a valid WMS 1.3.0 document.

However, the exposed layers found during the investigation were:

```text
Nikuz
PARCEL_ALL
Parcels_ITM
SUB_GUSH_ALL
SUB_GUSH_ALL_ITM
muni_il
nechalim1
```

No orthophoto layer was found in that WMS capabilities response.

Therefore this WMS endpoint is not currently required for the orthophoto implementation.

---

## 12. WMTS Investigation

The attempted GeoWebCache WMTS endpoint:

```text
https://open.govmap.gov.il/geoserver/gwc/service/wmts
```

did not return WMTS capabilities during the investigation.

It returned a GovMap error HTML page instead.

This is also no longer a blocker because the production application exposes the required orthophoto through the separate XYZ service.

---

## 13. Recommended Architecture Decision

### Decision

Use the current GovMap XYZ orthophoto service directly from Leaflet.

### Provider

GovMap

### Layer

`ortho_2025`

### Tile endpoint

```text
https://basemaps.govmap.gov.il/tms/orto2025me_update/{z}/{x}/{y}.jpg
```

### CRS

`EPSG:3857`

### Tile schema

`XYZ`

### Zoom range

`4-20`

### Backend proxy

Not required for the initial implementation based on the observed CORS behavior.

### Coordinate transformation

Not required.

### Leaflet CRS modification

Not required.

---

## 14. Implementation Scope for Task 08

Task 08 can now proceed with a narrow implementation.

Required functionality:

1. Keep the existing OpenStreetMap layer.
2. Add GovMap Orthophoto 2025 as a second base layer.
3. Add UI control for switching between:
   - Map
   - Satellite / Orthophoto
4. Preserve current center and zoom when switching.
5. Preserve all Area layers when switching.
6. Preserve drawing/editing functionality.
7. Preserve WebSocket functionality.
8. Display appropriate GovMap attribution.
9. Do not introduce EPSG:2039 or coordinate reprojection.
10. Do not introduce a backend tile proxy unless a concrete requirement appears.

---

## 15. Manual Verification

After implementation, verify at minimum:

### GovMap loading

Switch from OSM to GovMap.

Expected:

- orthophoto tiles appear;
- no broken tiles;
- browser Network shows requests to `basemaps.govmap.gov.il`;
- tile requests return HTTP 200.

### Geographic alignment

Navigate to a clearly identifiable location.

Switch repeatedly:

```text
OSM -> GovMap -> OSM
```

Expected:

- roads/buildings remain geographically aligned;
- map center does not move;
- zoom remains approximately equivalent.

### Existing Area alignment

Create an Area while using OSM.

Switch to GovMap.

Expected:

- polygon remains at exactly the same geographic location.

Then switch back to OSM.

Expected:

- polygon remains correctly aligned.

### Drawing on GovMap

Switch to GovMap and create a new polygon.

Expected:

- drawing works normally;
- Area can be saved;
- calculated area is unaffected by base-map selection.

### Editing

Edit an existing polygon while GovMap is active.

Expected:

- normal editing behavior;
- updated geometry persists;
- switching back to OSM shows the same geometry.

### Real-time updates

Open two browser tabs.

Use GovMap in one and OSM in another.

Create/update an Area.

Expected:

- WebSocket update appears in both clients;
- base-map selection has no effect on application data synchronization.

---

## 16. Remaining Questions

The technical feasibility question is resolved, but several non-technical or operational questions remain.

### Licensing / terms of use

The investigation confirms that the layer is marked by the GovMap application as public and that it can currently be requested directly.

This does **not**, by itself, establish the complete licensing or redistribution terms for third-party applications.

The official usage terms should be checked before production deployment.

### Endpoint stability

The endpoint:

```text
https://basemaps.govmap.gov.il/tms/orto2025me_update/...
```

is used by the current GovMap production application.

However, no guarantee of long-term URL stability has been established during this investigation.

The application should therefore keep the GovMap URL isolated in configuration rather than spreading it throughout UI code.

### Future orthophoto versions

GovMap exposes multiple orthophoto generations.

The current implementation should use 2025 only.

Supporting historical imagery selection is a potential future feature and is outside Task 08.

---

## 17. Evidence and Confidence

### Directly observed

- Current GovMap production application loads the orthophoto from `basemaps.govmap.gov.il`.
- An observed tile URL used `tms/orto2025me_update`.
- The observed tile request returned HTTP 200.
- The observed response included `Access-Control-Allow-Origin: *`.
- The GovMap base-layer configuration identified the service as XYZ / EPSG:3857 / WebMercatorQuad.
- The configuration identified `ortho_2025`, zoom range 4-20, JPEG raster format, and the Survey of Israel credit.

### Independently tested during investigation

- `open.govmap.gov.il/geoserver/opendata/wms` returned valid WMS 1.3.0 capabilities.
- The public WMS exposed the investigated vector layers but no orthophoto layer.
- The attempted standard GeoWebCache WMTS capabilities URL returned an HTML error page.

### Not established

- Long-term stability guarantee for the current XYZ endpoint.
- Complete third-party licensing / redistribution terms.
- Whether GovMap provides separate formal documentation for this exact XYZ endpoint.

---

## 18. Final Conclusion

The original GovMap integration blocker is resolved.

The current GovMap production application exposes the 2025 nationwide orthophoto as a Web Mercator XYZ tile layer.

Observed configuration:

```text
Provider:      GovMap
Layer:         ortho_2025
CRS:           EPSG:3857
Matrix Set:    WebMercatorQuad
Tile Schema:   XYZ
Image Format:  JPEG
Min Zoom:      4
Max Zoom:      20
Public Layer:  true

Tile URL:
https://basemaps.govmap.gov.il/tms/orto2025me_update/{z}/{x}/{y}.jpg
```

The preferred implementation is:

> Integrate GovMap Orthophoto 2025 directly as a standard Leaflet XYZ `TileLayer` and switch it with the existing OpenStreetMap base layer.

No custom CRS, EPSG:2039 reprojection, WMS integration, WMTS integration, or backend tile proxy is required for the initial implementation.
