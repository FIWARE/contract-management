package org.fiware.iam.dataspace;

import org.fiware.iam.configuration.DataSpaceEventsProperties.SubscriberSettings;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the sink and protocol client id validation that also guards the settings after a refresh.
 */
class SubscriberSettingsValidatorTest {

    private static final String SINK = "https://cm.consumer.org/events";
    private static final String PROTOCOL_CLIENT_ID = "contract-management";
    private static final String OWN_ID = "did:web:consumer.org";

    static Stream<Arguments> settings() {
        return Stream.of(
                Arguments.of("valid", SINK, PROTOCOL_CLIENT_ID, OWN_ID, null),
                Arguments.of("http sink", "http://cm.consumer.org/events", PROTOCOL_CLIENT_ID, OWN_ID, null),
                Arguments.of("upper-case scheme", "HTTPS://cm.consumer.org/events", PROTOCOL_CLIENT_ID, OWN_ID, null),
                Arguments.of("no sink", null, PROTOCOL_CLIENT_ID, OWN_ID, SubscriberSettingsValidator.SINK_KEY),
                Arguments.of("empty sink", "", PROTOCOL_CLIENT_ID, OWN_ID, SubscriberSettingsValidator.SINK_KEY),
                Arguments.of("relative sink", "/events", PROTOCOL_CLIENT_ID, OWN_ID, SubscriberSettingsValidator.SINK_KEY),
                Arguments.of("sink without host", "https:/events", PROTOCOL_CLIENT_ID, OWN_ID, SubscriberSettingsValidator.SINK_KEY),
                Arguments.of("underscore host", "http://contract_management:8080/events", PROTOCOL_CLIENT_ID, OWN_ID,
                        SubscriberSettingsValidator.SINK_KEY),
                Arguments.of("non-http sink", "ftp://cm.consumer.org/events", PROTOCOL_CLIENT_ID, OWN_ID, SubscriberSettingsValidator.SINK_KEY),
                Arguments.of("malformed sink", "https://cm consumer/events", PROTOCOL_CLIENT_ID, OWN_ID, SubscriberSettingsValidator.SINK_KEY),
                Arguments.of("no protocol client id", SINK, null, OWN_ID, SubscriberSettingsValidator.PROTOCOL_CLIENT_ID_KEY),
                Arguments.of("blank protocol client id", SINK, " ", OWN_ID, SubscriberSettingsValidator.PROTOCOL_CLIENT_ID_KEY),
                Arguments.of("no own id", SINK, PROTOCOL_CLIENT_ID, null, SubscriberSettingsValidator.OWN_ID_KEY),
                Arguments.of("blank own id", SINK, PROTOCOL_CLIENT_ID, " ", SubscriberSettingsValidator.OWN_ID_KEY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settings")
    void reportsTheOffendingKey(String description, String sink, String protocolClientId, String ownId,
                              String offendingKey) {
        SubscriberSettings subscriber = new SubscriberSettings();
        subscriber.setSink(sink);
        subscriber.getProtocolSettings().setClientId(protocolClientId);

        Optional<String> problem = SubscriberSettingsValidator.problem(subscriber, ownId);

        if (offendingKey == null) {
            assertEquals(Optional.empty(), problem);
        } else {
            assertTrue(problem.orElseThrow().contains(offendingKey), problem.get());
        }
    }
}
