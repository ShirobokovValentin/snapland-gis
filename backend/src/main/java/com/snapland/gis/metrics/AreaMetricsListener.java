package com.snapland.gis.metrics;

import com.snapland.gis.area.ChangeType;
import com.snapland.gis.realtime.AreaEventMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Increments the committed Area mutation counter after each successful DB commit.
 *
 * Uses AFTER_COMMIT semantics so the counter reflects only mutations that were
 * durably persisted. Requests rejected before or during the transaction
 * (rate limit, OCC conflict, invalid geometry) produce no AreaEventMessage and
 * therefore never reach this listener.
 *
 * The four counters are pre-registered at construction time so they appear in
 * Prometheus output even before any mutation has been committed.
 */
@Component
public class AreaMetricsListener {

    private static final String METRIC_NAME = "area.mutations.committed.total";
    private static final String TAG_CHANGE_TYPE = "change_type";
    private static final String PERMANENT_DELETIONS_METRIC = "area.permanent.deletions.total";

    private final MeterRegistry meterRegistry;

    public AreaMetricsListener(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (ChangeType type : ChangeType.values()) {
            Counter.builder(METRIC_NAME)
                    .tag(TAG_CHANGE_TYPE, type.name())
                    .description("Committed Area mutations by change type")
                    .register(meterRegistry);
        }
        Counter.builder(PERMANENT_DELETIONS_METRIC)
                .description("Areas physically deleted via permanent delete")
                .register(meterRegistry);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAreaMutation(AreaEventMessage event) {
        String changeType = mapToChangeType(event.type());
        if (changeType != null) {
            meterRegistry.counter(METRIC_NAME, TAG_CHANGE_TYPE, changeType).increment();
        }
        if ("AREA_PERMANENTLY_DELETED".equals(event.type())) {
            meterRegistry.counter(PERMANENT_DELETIONS_METRIC).increment();
        }
    }

    private String mapToChangeType(String eventType) {
        return switch (eventType) {
            case "AREA_CREATED"  -> ChangeType.CREATED.name();
            case "AREA_UPDATED"  -> ChangeType.UPDATED.name();
            case "AREA_DELETED"  -> ChangeType.DELETED.name();
            case "AREA_RESTORED" -> ChangeType.RESTORED.name();
            default -> null;
        };
    }
}
