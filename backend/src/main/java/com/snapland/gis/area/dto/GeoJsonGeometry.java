package com.snapland.gis.area.dto;

import java.util.List;

public record GeoJsonGeometry(String type, List<List<List<Double>>> coordinates) {
}
