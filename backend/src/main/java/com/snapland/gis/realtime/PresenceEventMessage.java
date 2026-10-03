package com.snapland.gis.realtime;

public record PresenceEventMessage(String type, UserPresenceResponse user) {}
