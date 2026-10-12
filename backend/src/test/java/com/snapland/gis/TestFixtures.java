package com.snapland.gis;

import com.snapland.gis.area.dto.GeoJsonGeometry;

import java.util.List;

class TestFixtures {

    static GeoJsonGeometry squarePolygon() {
        List<List<Double>> ring = List.of(
                List.of(35.50, 32.70),
                List.of(35.55, 32.70),
                List.of(35.55, 32.75),
                List.of(35.50, 32.75),
                List.of(35.50, 32.70));
        return new GeoJsonGeometry("Polygon", List.of(ring));
    }

    static GeoJsonGeometry altPolygon() {
        List<List<Double>> ring = List.of(
                List.of(35.60, 32.80),
                List.of(35.65, 32.80),
                List.of(35.65, 32.85),
                List.of(35.60, 32.85),
                List.of(35.60, 32.80));
        return new GeoJsonGeometry("Polygon", List.of(ring));
    }

    // Bowtie shape — geometrically invalid; rejected with 400 INVALID_GEOMETRY
    static GeoJsonGeometry selfIntersectingPolygon() {
        List<List<Double>> ring = List.of(
                List.of(0.0, 0.0), List.of(2.0, 2.0), List.of(2.0, 0.0),
                List.of(0.0, 2.0), List.of(0.0, 0.0));
        return new GeoJsonGeometry("Polygon", List.of(ring));
    }
}
