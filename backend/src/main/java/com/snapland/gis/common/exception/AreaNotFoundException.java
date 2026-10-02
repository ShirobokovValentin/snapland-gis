package com.snapland.gis.common.exception;

import java.util.UUID;

public class AreaNotFoundException extends RuntimeException {

    public AreaNotFoundException(UUID areaId) {
        super("Area not found: " + areaId);
    }
}
