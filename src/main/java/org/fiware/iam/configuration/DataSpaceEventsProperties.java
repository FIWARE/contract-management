package org.fiware.iam.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.core.annotation.Nullable;
import lombok.Data;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Configuration of the data space event channel. Micronaut's refresh scope rebinds this singleton in place on a refresh.
 * Readers must go through the injected bean on every use and never copy its values into fields, or a refresh has no effect.
 * A key removed from the configuration keeps its previous value; set it to an empty value instead.
 */
@Data
@ConfigurationProperties("dataspace.events")
public class DataSpaceEventsProperties {

    public static final Set<String> DEFAULT_ALGORITHMS = Set.of("ES256", "RS256");
    public static final String DEFAULT_PARTICIPANT_CLAIM = "participantId";
    public static final int DEFAULT_DEDUP_MAX_ENTRIES = 10_000;
    public static final Duration DEFAULT_DEDUP_TTL = Duration.ofHours(1);

    /**
     * Id of the data space this instance belongs to. Events carrying a different dataspaceid are rejected.
     */
    @Nullable
    private String dataspaceId;

    /**
     * Participant id of this instance, used to recognize events about itself. Falls back to general.did.
     */
    @Nullable
    private String ownParticipantId;

    /**
     * The receiving endpoint.
     */
    private Listener listener = new Listener();

    /**
     * Participants allowed to publish events, with the event types each of them may publish.
     */
    private List<Publisher> publishers = new ArrayList<>();

    /**
     * True if the participant is a configured publisher of the given event type.
     */
    public boolean isAuthorized(String publisherId, String type) {
        return publishers.stream()
                .filter(publisher -> Objects.equals(publisher.getId(), publisherId))
                .anyMatch(publisher -> publisher.getTypes() != null && publisher.getTypes().contains(type));
    }

    /**
     * The configured publisher origin matching the requested webhook origin. DNS names compare case-insensitively.
     */
    public Optional<String> allowedOrigin(@Nullable String requestOrigin) {
        if (requestOrigin == null) {
            return Optional.empty();
        }
        return publishers.stream()
                .map(Publisher::getOrigin)
                .filter(requestOrigin::equalsIgnoreCase)
                .findFirst();
    }

    /**
     * The participant id of this instance: own-participant-id if set, otherwise general.did.
     */
    public String ownId(GeneralProperties generalProperties) {
        if (ownParticipantId != null && !ownParticipantId.isBlank()) {
            return ownParticipantId;
        }
        return generalProperties.getDid();
    }

    /**
     * The receiving endpoint.
     */
    @Data
    @ConfigurationProperties("listener")
    public static class Listener {

        /**
         * Enables the receiving endpoint. Evaluated at startup only.
         */
        private boolean enabled = false;

        /**
         * Verification of the bearer tokens issued by the verifier.
         */
        private Verifier verifier = new Verifier();

        /**
         * Deduplication of retried deliveries.
         */
        private Dedup dedup = new Dedup();

        /**
         * Watch on the configuration file, triggering a refresh when it changes.
         */
        private Watch watch = new Watch();

        @Data
        @ConfigurationProperties("verifier")
        public static class Verifier {

            /**
             * URL of the verifier's JWKS. Required when the listener is enabled.
             */
            @Nullable
            private String jwksUrl;

            /**
             * Expected issuer of the tokens. Not checked if unset.
             */
            @Nullable
            private String issuer;

            /**
             * Audience the tokens have to be issued for. Required when the listener is enabled.
             */
            @Nullable
            private String audience;

            /**
             * Top-level token claim carrying the participant id of the caller.
             */
            private String participantClaim = DEFAULT_PARTICIPANT_CLAIM;

            /**
             * JWS algorithms accepted on the tokens.
             */
            private Set<String> algorithms = DEFAULT_ALGORITHMS;
        }

        @Data
        @ConfigurationProperties("dedup")
        public static class Dedup {

            /**
             * Maximum number of remembered (source, id) pairs.
             */
            private int maxEntries = DEFAULT_DEDUP_MAX_ENTRIES;

            /**
             * How long a (source, id) pair is remembered.
             */
            private Duration ttl = DEFAULT_DEDUP_TTL;
        }

        @Data
        @ConfigurationProperties("watch")
        public static class Watch {

            /**
             * Enables the watch. Evaluated at startup only.
             */
            private boolean enabled = false;

            /**
             * Path of the watched configuration file.
             */
            @Nullable
            private String path;
        }
    }

    /**
     * A participant allowed to publish events. The id is matched against the token, the origin against the webhook handshake.
     */
    @Data
    public static class Publisher {
        private String id;
        private String origin;
        private List<String> types = new ArrayList<>();
    }

    /**
     * Enables beans of the receiving endpoint.
     */
    public static class ListenerCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            return context.getBean(DataSpaceEventsProperties.class)
                    .getListener()
                    .isEnabled();
        }
    }

    /**
     * Enables the configuration file watch.
     */
    public static class WatchCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            return context.getBean(DataSpaceEventsProperties.class)
                    .getListener()
                    .getWatch()
                    .isEnabled();
        }
    }
}
