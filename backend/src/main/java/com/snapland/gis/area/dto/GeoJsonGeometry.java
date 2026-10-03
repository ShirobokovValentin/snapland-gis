package com.snapland.gis.area.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "GeoJSON geometry. Only Polygon type is supported.")
public record GeoJsonGeometry(
        @Schema(description = "Geometry type. Must be \"Polygon\".", example = "Polygon")
        String type,
        @Schema(description = "GeoJSON Polygon coordinates: an array containing one ring (outer boundary). Each point is [longitude, latitude] in WGS 84 decimal degrees. The ring must close (first and last point identical).",
                example = "[[[34.78,31.75],[34.82,31.75],[34.82,31.78],[34.78,31.78],[34.78,31.75]]]")
        List<List<List<Double>>> coordinates
) {
}
