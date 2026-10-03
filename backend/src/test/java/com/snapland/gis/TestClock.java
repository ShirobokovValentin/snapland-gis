package com.snapland.gis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Controllable Clock for integration tests. Time can be set or advanced
 * deterministically without real waiting.
 */
class TestClock extends Clock {

    private volatile Instant instant;

    TestClock(Instant initial) {
        this.instant = initial;
    }

    void set(Instant newInstant) {
        this.instant = newInstant;
    }

    void advance(Duration amount) {
        this.instant = instant.plus(amount);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
