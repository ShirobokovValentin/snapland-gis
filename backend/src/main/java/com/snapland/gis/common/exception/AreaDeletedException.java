package com.snapland.gis.common.exception;

import java.util.UUID;

public class AreaDeletedException extends RuntimeException {

    public AreaDeletedException(UUID areaId) {
        super("Area has been deleted: " + areaId);
    }
}
