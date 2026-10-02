package com.snapland.gis.common.exception;

import java.util.UUID;

public class VersionConflictException extends RuntimeException {

    private final UUID areaId;
    private final int expectedVersion;
    private final int currentVersion;

    public VersionConflictException(UUID areaId, int expectedVersion, int currentVersion) {
        super("Version conflict for area " + areaId
                + ": expected " + expectedVersion + ", current " + currentVersion);
        this.areaId = areaId;
        this.expectedVersion = expectedVersion;
        this.currentVersion = currentVersion;
    }

    public UUID getAreaId() {
        return areaId;
    }

    public int getExpectedVersion() {
        return expectedVersion;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }
}
