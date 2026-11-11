package org.fiware.iam.dataspace;

import io.micronaut.core.annotation.Nullable;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.events.model.DataSpaceEventVO;
import org.fiware.iam.exception.InvalidEventException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

class ParticipantRemovedHandlerTest {

    private static final String PUBLISHER = "did:web:p1.org";
    private static final String SUBJECT = "did:web:subject.org";
    private static final String DATA_ID = "did:web:data.org";

    private final ParticipantRemoval removal = mock(ParticipantRemoval.class);
    private final ParticipantRemovedHandler handler = new ParticipantRemovedHandler(removal);

    @Test
    void supportsParticipantRemoved() {
        assertTrue(handler.isEventTypeSupported(DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("participants")
    void removesTheSubjectOrDataParticipant(String description, @Nullable String subject, @Nullable Object data, String expected) {
        when(removal.apply(any(), any(), any())).thenReturn(Mono.empty());

        handler.handleEvent(event(subject, data)).block();

        verify(removal).apply(PUBLISHER, expected, RemovalTrigger.EVENT);
    }

    static Stream<Arguments> participants() {
        return Stream.of(
                Arguments.of("subject", SUBJECT, Map.of(TirWatcher.PARTICIPANT_ID_KEY, DATA_ID), SUBJECT),
                Arguments.of("data fallback", null, Map.of(TirWatcher.PARTICIPANT_ID_KEY, DATA_ID), DATA_ID),
                Arguments.of("blank subject", " ", Map.of(TirWatcher.PARTICIPANT_ID_KEY, DATA_ID), DATA_ID));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("withoutParticipant")
    void eventWithoutParticipantIsInvalid(String description, @Nullable Object data) {
        assertThrows(InvalidEventException.class, () -> handler.handleEvent(event(null, data)).block());
        verifyZeroInteractions(removal);
    }

    static Stream<Arguments> withoutParticipant() {
        return Stream.of(
                Arguments.of("no data", null),
                Arguments.of("data without participantId", Map.of("other", DATA_ID)),
                Arguments.of("non-string participantId", Map.of(TirWatcher.PARTICIPANT_ID_KEY, 1)));
    }

    private static DataSpaceEventVO event(@Nullable String subject, @Nullable Object data) {
        DataSpaceEventVO event = new DataSpaceEventVO();
        event.setId("event-1");
        event.setSource(PUBLISHER);
        event.setType(DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE);
        event.setSubject(subject);
        event.setData(data);
        return event;
    }
}
