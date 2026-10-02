package com.snapland.gis.common.exception;

import java.util.UUID;

public class AreaVersionNotFoundException extends RuntimeException {

    public AreaVersionNotFoundException(UUID areaId, int version) {
        super("AreaVersion not found: area=" + areaId + ", version=" + version);
    }
}
