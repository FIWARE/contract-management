package org.fiware.iam.dataspace;

import io.micronaut.http.HttpStatus;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.events.model.DataSpaceEventVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventAuthorizerTest {

    private static final String PROVIDER_ID = "did:web:provider.org";
    private static final String OTHER_PUBLISHER_ID = "did:web:other-publisher.org";
    private static final String UNCONFIGURED_ID = "did:web:stranger.org";
    private static final String OWN_ID = "did:web:own.org";
    private static final String GENERAL_DID = "did:web:general.org";
    private static final String CONSUMER_ID = "did:web:consumer.org";
    private static final String TYPE_A = "org.fiware.dataspace.a";
    private static final String TYPE_B = "org.fiware.dataspace.b";
    private static final String TYPE_UNKNOWN = "org.fiware.dataspace.unknown";
    private static final String DATASPACE = "dataspace-1";
    private static final String OTHER_DATASPACE = "dataspace-2";
    private static final String EVENT_ID = "event-1";
    private static final String BLANK = " ";
    private static final Predicate<String> TYPE_KNOWN = Set.of(TYPE_A, TYPE_B)::contains;

    private DataSpaceEventsProperties properties;
    private GeneralProperties generalProperties;
    private EventAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        properties = new DataSpaceEventsProperties();
        properties.setPublishers(List.of(
                publisher(PROVIDER_ID, TYPE_A),
                publisher(OTHER_PUBLISHER_ID, TYPE_B)));
        properties.setOwnParticipantId(OWN_ID);
        generalProperties = new GeneralProperties();
        generalProperties.setDid(GENERAL_DID);
        authorizer = new EventAuthorizer(properties, generalProperties);
    }

    private static DataSpaceEventsProperties.Publisher publisher(String id, String type) {
        DataSpaceEventsProperties.Publisher publisher = new DataSpaceEventsProperties.Publisher();
        publisher.setId(id);
        publisher.setTypes(List.of(type));
        return publisher;
    }

    private static DataSpaceEventVO event(String source, String type, String subject, String dataspaceId) {
        return new DataSpaceEventVO()
                .specversion(DataSpaceEventVO.Specversion._1_0)
                .id(EVENT_ID)
                .source(source)
                .type(type)
                .subject(subject)
                .dataspaceid(dataspaceId);
    }

    private static Arguments matrixCase(String description, Optional<String> participantId, DataSpaceEventVO event,
                                        String configuredDataspace, EventDecision expected) {
        return Arguments.of(description, participantId, event, configuredDataspace, expected);
    }

    static Stream<Arguments> authorizationMatrix() {
        Optional<String> provider = Optional.of(PROVIDER_ID);
        return Stream.of(
                // participant id
                matrixCase("configured publisher, known type", provider,
                        event(PROVIDER_ID, TYPE_A, CONSUMER_ID, null), null, EventDecision.ACCEPT),
                matrixCase("no participant id", Optional.empty(),
                        event(PROVIDER_ID, TYPE_A, CONSUMER_ID, null), null, EventDecision.UNAUTHENTICATED),
                matrixCase("blank participant id", Optional.of(BLANK),
                        event(PROVIDER_ID, TYPE_A, CONSUMER_ID, null), null, EventDecision.UNAUTHENTICATED),
                matrixCase("no participant id, unknown type", Optional.empty(),
                        event(PROVIDER_ID, TYPE_UNKNOWN, CONSUMER_ID, null), null, EventDecision.UNAUTHENTICATED),
                // source
                matrixCase("source differs from participant id", provider,
                        event(OTHER_PUBLISHER_ID, TYPE_B, CONSUMER_ID, null), null, EventDecision.SOURCE_MISMATCH),
                matrixCase("source differs, unknown type", provider,
                        event(UNCONFIGURED_ID, TYPE_UNKNOWN, CONSUMER_ID, null), null, EventDecision.SOURCE_MISMATCH),
                matrixCase("source absent", provider,
                        event(null, TYPE_A, CONSUMER_ID, null), null, EventDecision.SOURCE_MISMATCH),
                matrixCase("source differs, data space differs", provider,
                        event(OTHER_PUBLISHER_ID, TYPE_A, CONSUMER_ID, OTHER_DATASPACE), DATASPACE, EventDecision.SOURCE_MISMATCH),
                // type and publisher
                matrixCase("unknown type", provider,
                        event(PROVIDER_ID, TYPE_UNKNOWN, CONSUMER_ID, null), null, EventDecision.UNKNOWN_TYPE),
                matrixCase("unknown type, unconfigured publisher", Optional.of(UNCONFIGURED_ID),
                        event(UNCONFIGURED_ID, TYPE_UNKNOWN, CONSUMER_ID, null), null, EventDecision.UNKNOWN_TYPE),
                matrixCase("known type, unconfigured publisher", Optional.of(UNCONFIGURED_ID),
                        event(UNCONFIGURED_ID, TYPE_A, CONSUMER_ID, null), null, EventDecision.PUBLISHER_NOT_AUTHORIZED),
                matrixCase("known type, publisher configured for another type", provider,
                        event(PROVIDER_ID, TYPE_B, CONSUMER_ID, null), null, EventDecision.PUBLISHER_NOT_AUTHORIZED),
                // subject
                matrixCase("subject is this participant", provider,
                        event(PROVIDER_ID, TYPE_A, OWN_ID, null), null, EventDecision.SELF),
                matrixCase("subject absent", provider,
                        event(PROVIDER_ID, TYPE_A, null, null), null, EventDecision.ACCEPT),
                matrixCase("subject is this participant, unknown type", provider,
                        event(PROVIDER_ID, TYPE_UNKNOWN, OWN_ID, null), null, EventDecision.UNKNOWN_TYPE),
                matrixCase("subject is this participant, unconfigured publisher", Optional.of(UNCONFIGURED_ID),
                        event(UNCONFIGURED_ID, TYPE_A, OWN_ID, null), null, EventDecision.PUBLISHER_NOT_AUTHORIZED),
                // data space
                matrixCase("data space equal", provider,
                        event(PROVIDER_ID, TYPE_A, CONSUMER_ID, DATASPACE), DATASPACE, EventDecision.ACCEPT),
                matrixCase("data space differs", provider,
                        event(PROVIDER_ID, TYPE_A, CONSUMER_ID, OTHER_DATASPACE), DATASPACE, EventDecision.DATASPACE_MISMATCH),
                matrixCase("data space differs, unknown type", provider,
                        event(PROVIDER_ID, TYPE_UNKNOWN, CONSUMER_ID, OTHER_DATASPACE), DATASPACE, EventDecision.DATASPACE_MISMATCH),
                matrixCase("data space absent in the event", provider,
                        event(PROVIDER_ID, TYPE_A, CONSUMER_ID, null), DATASPACE, EventDecision.ACCEPT),
                matrixCase("data space not configured", provider,
                        event(PROVIDER_ID, TYPE_A, CONSUMER_ID, OTHER_DATASPACE), null, EventDecision.ACCEPT),
                matrixCase("data space configured empty", provider,
                        event(PROVIDER_ID, TYPE_A, CONSUMER_ID, OTHER_DATASPACE), "", EventDecision.ACCEPT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authorizationMatrix")
    void authorize(String description, Optional<String> participantId, DataSpaceEventVO event,
                   String configuredDataspace, EventDecision expected) {
        properties.setDataspaceId(configuredDataspace);

        assertEquals(expected, authorizer.authorize(participantId, event, TYPE_KNOWN), description);
    }

    static Stream<Arguments> ownIds() {
        return Stream.of(
                Arguments.of("own-participant-id is used", OWN_ID, GENERAL_DID, OWN_ID, EventDecision.SELF),
                Arguments.of("own-participant-id replaces general.did", OWN_ID, GENERAL_DID, GENERAL_DID, EventDecision.ACCEPT),
                Arguments.of("falls back to general.did", null, GENERAL_DID, GENERAL_DID, EventDecision.SELF),
                Arguments.of("falls back to general.did if blank", BLANK, GENERAL_DID, GENERAL_DID, EventDecision.SELF),
                Arguments.of("no own id, subject absent", null, null, null, EventDecision.ACCEPT),
                Arguments.of("no own id, subject set", null, null, CONSUMER_ID, EventDecision.ACCEPT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownIds")
    void recognizesOwnId(String description, String ownParticipantId, String generalDid, String subject,
                         EventDecision expected) {
        properties.setOwnParticipantId(ownParticipantId);
        generalProperties.setDid(generalDid);

        assertEquals(expected,
                authorizer.authorize(Optional.of(PROVIDER_ID), event(PROVIDER_ID, TYPE_A, subject, null), TYPE_KNOWN),
                description);
    }

    @Test
    void readsPropertiesOnEveryCall() {
        DataSpaceEventVO event = event(UNCONFIGURED_ID, TYPE_A, CONSUMER_ID, null);
        assertEquals(EventDecision.PUBLISHER_NOT_AUTHORIZED,
                authorizer.authorize(Optional.of(UNCONFIGURED_ID), event, TYPE_KNOWN));

        properties.setPublishers(List.of(publisher(UNCONFIGURED_ID, TYPE_A)));

        assertEquals(EventDecision.ACCEPT, authorizer.authorize(Optional.of(UNCONFIGURED_ID), event, TYPE_KNOWN));
    }

    static Stream<Arguments> decisions() {
        return Stream.of(
                Arguments.of(EventDecision.ACCEPT, HttpStatus.ACCEPTED, "accepted", false),
                Arguments.of(EventDecision.UNKNOWN_TYPE, HttpStatus.ACCEPTED, "unknown_type", false),
                Arguments.of(EventDecision.SELF, HttpStatus.ACCEPTED, "self", false),
                Arguments.of(EventDecision.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, "unauthenticated", true),
                Arguments.of(EventDecision.SOURCE_MISMATCH, HttpStatus.FORBIDDEN, "source_mismatch", true),
                Arguments.of(EventDecision.PUBLISHER_NOT_AUTHORIZED, HttpStatus.FORBIDDEN, "publisher_not_authorized", true),
                Arguments.of(EventDecision.DATASPACE_MISMATCH, HttpStatus.FORBIDDEN, "dataspace_mismatch", true));
    }

    @ParameterizedTest
    @MethodSource("decisions")
    void decisionCarriesStatusAndOutcome(EventDecision decision, HttpStatus status, String outcome, boolean rejected) {
        assertEquals(status, decision.getStatus());
        assertEquals(outcome, decision.getOutcome());
        assertEquals(rejected, decision.isRejected());
    }
}
