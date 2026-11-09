package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.events.model.DataSpaceEventVO;
import org.fiware.iam.exception.InvalidEventException;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Acts on participant.removed: the subject (or data.participantId) is the removed participant, the already authorized
 * source the publisher whose policy applies.
 */
@Singleton
@Requires(condition = DataSpaceEventsProperties.ParticipantRemovedCondition.class)
@RequiredArgsConstructor
public class ParticipantRemovedHandler implements DataSpaceEventHandler {

    private final ParticipantRemoval participantRemoval;

    @Override
    public boolean isEventTypeSupported(String type) {
        return DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE.equals(type);
    }

    @Override
    public Mono<Void> handleEvent(DataSpaceEventVO event) {
        return Mono.defer(() -> {
            String participant = participantOf(event);
            if (participant == null) {
                return Mono.error(new InvalidEventException(event.getId(), event.getSource(),
                        "a participant.removed event needs a subject or data.%s".formatted(TirWatcher.PARTICIPANT_ID_KEY)));
            }
            return participantRemoval.apply(event.getSource(), participant, RemovalTrigger.EVENT).then();
        });
    }

    @Nullable
    private static String participantOf(DataSpaceEventVO event) {
        if (!isBlank(event.getSubject())) {
            return event.getSubject();
        }
        if (event.getData() instanceof Map<?, ?> data && data.get(TirWatcher.PARTICIPANT_ID_KEY) instanceof String id
                && !isBlank(id)) {
            return id;
        }
        return null;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
