package com.snapland.gis.common.exception;

import java.util.UUID;

public class AreaNotDeletedException extends RuntimeException {

    public AreaNotDeletedException(UUID areaId) {
        super("Area is not in DELETED state and cannot be permanently deleted: " + areaId);
    }
}
