package org.fiware.iam.dataspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the caller's participant id from the identity header the API gateway sets from the token it verified.
 * CM does not verify the token itself, so the header is only as trustworthy as the gateway in front of it.
 * Reads the refreshable identity configuration on every call; a refresh that makes it unusable fails closed.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor
@Requires(condition = DataSpaceEventsProperties.IdentityCondition.class)
public class ParticipantResolver {

    private static final String CONFIG_PREFIX = "dataspace.events.listener.identity.";
    // APISIX sets base64(JSON(claims)); a gateway passing the claims unencoded is accepted as well
    private static final char JSON_START = '{';

    private final DataSpaceEventsProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicReference<String> reportedMisconfiguration = new AtomicReference<>();

    @PostConstruct
    void validateConfiguration() {
        misconfiguration(identity()).ifPresent(problem -> {
            throw new ConfigurationException(problem);
        });
    }

    /**
     * Name of the header carrying the caller's claims, so a caller can read it off the current request.
     */
    public String headerName() {
        return identity().getHeader();
    }

    /**
     * The participant id in the identity header, empty if the header is missing or carries no usable claim.
     */
    public Optional<String> participantIdOf(@Nullable String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            log.debug("No {} header on the request.", headerName());
            return Optional.empty();
        }
        DataSpaceEventsProperties.Listener.Identity identity = identity();
        Optional<String> misconfiguration = misconfiguration(identity);
        reportMisconfiguration(misconfiguration);
        if (misconfiguration.isPresent()) {
            return Optional.empty();
        }
        return claims(headerValue.strip())
                .flatMap(claims -> participantClaim(claims, identity.getParticipantClaim()));
    }

    private Optional<JsonNode> claims(String headerValue) {
        byte[] json;
        if (headerValue.charAt(0) == JSON_START) {
            json = headerValue.getBytes(StandardCharsets.UTF_8);
        } else {
            try {
                json = decodeBase64(headerValue);
            } catch (IllegalArgumentException e) {
                log.debug("Rejecting the {} header: not base64.", headerName());
                return Optional.empty();
            }
        }
        try {
            JsonNode claims = objectMapper.readTree(json);
            if (!claims.isObject()) {
                log.debug("Rejecting the {} header: the claims are not a JSON object.", headerName());
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (Exception e) {
            log.debug("Rejecting the {} header: the claims are not valid JSON.", headerName());
            return Optional.empty();
        }
    }

    // gateways differ in the base64 alphabet they use; both are unambiguous, so accept either
    private static byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return Base64.getUrlDecoder().decode(value);
        }
    }

    private Optional<String> participantClaim(JsonNode claims, String claimName) {
        JsonNode claim = claims.get(claimName);
        if (claim == null || !claim.isTextual() || claim.textValue().isBlank()) {
            log.debug("Rejecting the {} header: no participant claim {}.", headerName(), claimName);
            return Optional.empty();
        }
        return Optional.of(claim.textValue());
    }

    /**
     * Why no caller can be identified at all, empty if the configuration is usable.
     */
    private static Optional<String> misconfiguration(DataSpaceEventsProperties.Listener.Identity identity) {
        if (isBlank(identity.getHeader())) {
            return Optional.of(CONFIG_PREFIX + "header must not be empty.");
        }
        if (isBlank(identity.getParticipantClaim())) {
            return Optional.of(CONFIG_PREFIX + "participant-claim must not be empty.");
        }
        return Optional.empty();
    }

    // any caller can trigger this, so a broken refresh is reported once per state instead of once per request
    private void reportMisconfiguration(Optional<String> misconfiguration) {
        String current = misconfiguration.orElse(null);
        String previous = reportedMisconfiguration.getAndSet(current);
        if (current == null) {
            if (previous != null) {
                log.info("The participant identity is configured again, accepting callers.");
            }
            return;
        }
        if (current.equals(previous)) {
            log.debug("Rejecting the caller: {}", current);
        } else {
            log.error("Rejecting all callers until the configuration is fixed: {}", current);
        }
    }

    private DataSpaceEventsProperties.Listener.Identity identity() {
        return properties.getListener().getIdentity();
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
