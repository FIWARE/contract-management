package org.fiware.iam.dataspace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.exceptions.ConfigurationException;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The caller identity is taken from the gateway header without any verification of the token behind it.
 */
class ParticipantResolverTest {

    private static final String PARTICIPANT_ID = "did:web:consumer.org";
    private static final String CUSTOM_HEADER = "X-Participant";
    private static final String CUSTOM_CLAIM = "sub";

    private DataSpaceEventsProperties properties;
    private ParticipantResolver resolver;
    private final Logger logger = (Logger) LoggerFactory.getLogger(ParticipantResolver.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void setUp() {
        properties = new DataSpaceEventsProperties();
        resolver = new ParticipantResolver(properties, new ObjectMapper());
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logged);
    }

    @Test
    void readsTheParticipantFromTheDefaultHeader() {
        assertEquals(DataSpaceEventsProperties.DEFAULT_IDENTITY_HEADER, resolver.headerName());
        assertEquals(Optional.of(PARTICIPANT_ID), resolver.participantIdOf(userinfo(claims(PARTICIPANT_ID))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void acceptedEncodings(String name, String headerValue) {
        assertEquals(Optional.of(PARTICIPANT_ID), resolver.participantIdOf(headerValue));
    }

    private static Stream<Arguments> acceptedEncodings() {
        String claims = claims(PARTICIPANT_ID);
        String padded = Base64.getEncoder().encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        return Stream.of(
                Arguments.of("base64 with padding", padded),
                Arguments.of("base64 without padding",
                        Base64.getEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8))),
                Arguments.of("base64url", Base64.getUrlEncoder().encodeToString(claims.getBytes(StandardCharsets.UTF_8))),
                Arguments.of("plain json", claims),
                Arguments.of("surrounded by whitespace", " " + padded + " "),
                Arguments.of("further claims present",
                        userinfo("{\"iss\":\"https://verifier.org\",\"participantId\":\"" + PARTICIPANT_ID + "\",\"exp\":1}")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void rejectedHeaders(String name, String headerValue) {
        assertEquals(Optional.empty(), resolver.participantIdOf(headerValue));
    }

    private static Stream<Arguments> rejectedHeaders() {
        return Stream.of(
                Arguments.of("missing", null),
                Arguments.of("blank", "   "),
                Arguments.of("not base64", "not base64 at all!"),
                Arguments.of("not json", Base64.getEncoder().encodeToString("plain text".getBytes(StandardCharsets.UTF_8))),
                Arguments.of("not an object", userinfo("[\"" + PARTICIPANT_ID + "\"]")),
                Arguments.of("claim missing", userinfo("{\"sub\":\"" + PARTICIPANT_ID + "\"}")),
                Arguments.of("claim blank", userinfo("{\"participantId\":\"  \"}")),
                Arguments.of("claim null", userinfo("{\"participantId\":null}")),
                Arguments.of("claim not a string", userinfo("{\"participantId\":42}")),
                Arguments.of("claim an object", userinfo("{\"participantId\":{\"id\":\"" + PARTICIPANT_ID + "\"}}")));
    }

    @Test
    void usesTheConfiguredHeaderAndClaim() {
        properties.getListener().getIdentity().setHeader(CUSTOM_HEADER);
        properties.getListener().getIdentity().setParticipantClaim(CUSTOM_CLAIM);

        assertEquals(CUSTOM_HEADER, resolver.headerName());
        assertEquals(Optional.of(PARTICIPANT_ID), resolver.participantIdOf(userinfo("{\"sub\":\"" + PARTICIPANT_ID + "\"}")));
        assertEquals(Optional.empty(), resolver.participantIdOf(userinfo(claims(PARTICIPANT_ID))));
    }

    @Test
    void refusesToStartWithoutAUsableConfiguration() {
        properties.getListener().getIdentity().setHeader(" ");

        assertThrows(ConfigurationException.class, () -> resolver.validateConfiguration());
    }

    @Test
    void rejectsEveryCallerWhenARefreshBreaksTheConfiguration() {
        properties.getListener().getIdentity().setParticipantClaim("");
        String header = userinfo(claims(PARTICIPANT_ID));

        assertEquals(Optional.empty(), resolver.participantIdOf(header));
        assertEquals(Optional.empty(), resolver.participantIdOf(header));

        // reported once per state, not once per request
        assertEquals(1, errors().size());
        assertEquals(Level.ERROR, errors().get(0).getLevel());

        properties.getListener().getIdentity().setParticipantClaim(DataSpaceEventsProperties.DEFAULT_PARTICIPANT_CLAIM);
        assertEquals(Optional.of(PARTICIPANT_ID), resolver.participantIdOf(header));
    }

    private List<ILoggingEvent> errors() {
        return logged.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    private static String claims(String participant) {
        return "{\"" + DataSpaceEventsProperties.DEFAULT_PARTICIPANT_CLAIM + "\":\"" + participant + "\"}";
    }

    private static String userinfo(String claims) {
        return Base64.getEncoder().encodeToString(claims.getBytes(StandardCharsets.UTF_8));
    }
}
