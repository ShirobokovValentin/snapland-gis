package com.snapland.gis.realtime;

import com.snapland.gis.area.dto.AreaResponse;

public record AreaEventMessage(
        String type,
        String workspaceId,
        String timestamp,
        AreaResponse payload
) {
}
