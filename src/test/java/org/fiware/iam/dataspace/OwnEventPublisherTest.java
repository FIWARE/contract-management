package org.fiware.iam.dataspace;

import io.micronaut.http.MediaType;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.dataspace.PublishEndpoint.PublishedEvent;
import org.fiware.iam.events.model.DataSpaceEventVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checks the envelope of own events: the source fallback, id generation and the refusal without a source.
 */
class OwnEventPublisherTest {

    private static final String OWN_PARTICIPANT_ID = "did:web:participant.org";
    private static final String GENERAL_DID = "did:web:general.org";
    private static final String DATASPACE_ID = "test-dataspace";
    private static final String TYPE = "org.fiware.dataspace.example";
    private static final String SUBJECT = "did:web:consumer.org";
    private static final Object DATA = Map.of("participantId", SUBJECT);
    private static final String EXPLICIT_ID = "urn:event:4711";
    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final int MATCHING_SUBSCRIPTIONS = 2;

    private EventDelivery eventDelivery;
    private DataSpaceEventsProperties properties;
    private GeneralProperties generalProperties;
    private OwnEventPublisher publisher;

    @BeforeEach
    void setUp() {
        eventDelivery = mock(EventDelivery.class);
        when(eventDelivery.publish(any())).thenReturn(MATCHING_SUBSCRIPTIONS);
        properties = new DataSpaceEventsProperties();
        properties.setDataspaceId(DATASPACE_ID);
        generalProperties = new GeneralProperties();
        publisher = new OwnEventPublisher(eventDelivery, properties, generalProperties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static Stream<Arguments> sources() {
        return Stream.of(
                Arguments.of("own participant id wins", OWN_PARTICIPANT_ID, GENERAL_DID, OWN_PARTICIPANT_ID),
                Arguments.of("own participant id only", OWN_PARTICIPANT_ID, null, OWN_PARTICIPANT_ID),
                Arguments.of("falls back to general.did", null, GENERAL_DID, GENERAL_DID),
                Arguments.of("blank own participant id falls back", " ", GENERAL_DID, GENERAL_DID));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void fillsTheEnvelope(String description, String ownParticipantId, String did, String expectedSource) {
        properties.setOwnParticipantId(ownParticipantId);
        generalProperties.setDid(did);

        Optional<PublishedEvent> result = publisher.publish(TYPE, SUBJECT, DATA, EXPLICIT_ID);

        DataSpaceEventVO event = published();
        assertEquals(DataSpaceEventVO.Specversion._1_0, event.getSpecversion());
        assertEquals(expectedSource, event.getSource());
        assertEquals(TYPE, event.getType());
        assertEquals(SUBJECT, event.getSubject());
        assertEquals(NOW, event.getTime());
        assertEquals(DATASPACE_ID, event.getDataspaceid());
        assertEquals(MediaType.APPLICATION_JSON, event.getDatacontenttype());
        assertEquals(DATA, event.getData());
        assertEquals(Optional.of(new PublishedEvent(EXPLICIT_ID, MATCHING_SUBSCRIPTIONS)), result);
    }

    static Stream<Arguments> missingSources() {
        return Stream.of(
                Arguments.of("neither set", null, null),
                Arguments.of("both blank", " ", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingSources")
    void publishesNothingWithoutASource(String description, String ownParticipantId, String did) {
        properties.setOwnParticipantId(ownParticipantId);
        generalProperties.setDid(did);

        assertTrue(publisher.publish(TYPE, SUBJECT, DATA, EXPLICIT_ID).isEmpty());
        verify(eventDelivery, never()).publish(any());
    }

    static Stream<Arguments> missingIds() {
        return Stream.of(
                Arguments.of("null id", null),
                Arguments.of("empty id", ""),
                Arguments.of("blank id", " "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingIds")
    void generatesAMissingId(String description, String id) {
        properties.setOwnParticipantId(OWN_PARTICIPANT_ID);

        PublishedEvent result = publisher.publish(TYPE, SUBJECT, DATA, id).orElseThrow();

        String generated = published().getId();
        assertDoesNotThrow(() -> UUID.fromString(generated), "A missing id is generated.");
        assertEquals(generated, result.id());
    }

    @Test
    void generatesADistinctIdPerEvent() {
        properties.setOwnParticipantId(OWN_PARTICIPANT_ID);

        String first = publisher.publish(TYPE, SUBJECT, DATA, null).orElseThrow().id();
        String second = publisher.publish(TYPE, SUBJECT, DATA, null).orElseThrow().id();

        assertNotEquals(first, second, "Each event gets its own id.");
    }

    private DataSpaceEventVO published() {
        ArgumentCaptor<DataSpaceEventVO> captor = ArgumentCaptor.forClass(DataSpaceEventVO.class);
        verify(eventDelivery).publish(captor.capture());
        return captor.getValue();
    }
}
