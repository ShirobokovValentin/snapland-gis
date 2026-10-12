package com.snapland.gis.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class WorkspaceTopicEventRelay {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceTopicEventRelay.class);

    private final SimpMessagingTemplate messagingTemplate;

    public WorkspaceTopicEventRelay(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAreaEvent(AreaEventMessage event) {
        try {
            messagingTemplate.convertAndSend("/topic/workspace/" + event.workspaceId(), event);
        } catch (Exception e) {
            log.error("Failed to deliver area event {} for workspace {}: {}",
                    event.type(), event.workspaceId(), e.getMessage(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkspaceEvent(WorkspaceEventMessage event) {
        try {
            messagingTemplate.convertAndSend("/topic/workspace/" + event.workspaceId(), event);
        } catch (Exception e) {
            log.error("Failed to deliver workspace event {} for workspace {}: {}",
                    event.type(), event.workspaceId(), e.getMessage(), e);
        }
    }
}
