package org.fiware.iam.dataspace;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.events.model.DataSpaceEventVO;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Decides whether a received data space event may be dispatched. Stateless; deduplication is up to the caller.
 * Reads the refreshable properties on every call, so a refresh applies to the next event.
 */
@Singleton
@RequiredArgsConstructor
public class EventAuthorizer {

    private final DataSpaceEventsProperties properties;
    private final GeneralProperties generalProperties;

    /**
     * Authorizes the event sent by the participant the verified bearer belongs to (empty if no participant id was found).
     * typeKnown tells whether a handler supports an event type; an unknown type is accepted and dropped before the publisher check.
     */
    public EventDecision authorize(Optional<String> participantId, DataSpaceEventVO event, Predicate<String> typeKnown) {
        Optional<String> participant = participantId.filter(id -> !isBlank(id));
        if (participant.isEmpty()) {
            return EventDecision.UNAUTHENTICATED;
        }
        if (!participant.get().equals(event.getSource())) {
            return EventDecision.SOURCE_MISMATCH;
        }
        if (isOtherDataspace(event.getDataspaceid())) {
            return EventDecision.DATASPACE_MISMATCH;
        }
        if (!typeKnown.test(event.getType())) {
            return EventDecision.UNKNOWN_TYPE;
        }
        if (!properties.isAuthorized(event.getSource(), event.getType())) {
            return EventDecision.PUBLISHER_NOT_AUTHORIZED;
        }
        if (isOwnId(event.getSubject())) {
            return EventDecision.SELF;
        }
        return EventDecision.ACCEPT;
    }

    // only checked if both sides name a data space
    private boolean isOtherDataspace(@Nullable String eventDataspace) {
        String ownDataspace = properties.getDataspaceId();
        return !isBlank(eventDataspace) && !isBlank(ownDataspace) && !ownDataspace.equals(eventDataspace);
    }

    private boolean isOwnId(@Nullable String subject) {
        String ownId = properties.ownId(generalProperties);
        return !isBlank(subject) && !isBlank(ownId) && ownId.equals(subject);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
