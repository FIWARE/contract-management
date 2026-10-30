package org.fiware.iam.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.core.annotation.Nullable;
import lombok.Data;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Configuration of the data space event channel. Micronaut's refresh scope rebinds this singleton in place on a refresh.
 * Readers must go through the injected bean on every use and never copy its values into fields, or a refresh has no effect.
 * A key removed from the configuration keeps its previous value; set it to an empty value instead.
 * The nested classes are separate beans for binding only; inject this class, never them, or the copy read is not the rebound one.
 */
@Data
@ConfigurationProperties("dataspace.events")
public class DataSpaceEventsProperties {

    public static final Set<String> DEFAULT_ALGORITHMS = Set.of("ES256", "RS256");
    public static final String DEFAULT_PARTICIPANT_CLAIM = "participantId";
    public static final int DEFAULT_DEDUP_MAX_ENTRIES = 10_000;
    public static final Duration DEFAULT_DEDUP_TTL = Duration.ofHours(1);
    public static final Duration DEFAULT_LEASE_TTL = Duration.ofHours(24);
    public static final int DEFAULT_MAX_SUBSCRIPTIONS_PER_SUBSCRIBER = 10;
    public static final int DEFAULT_MAX_SUBSCRIPTIONS = 1000;
    public static final Duration DEFAULT_SINK_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_SINK_READ_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);
    public static final int DEFAULT_DELIVERY_MAX_ATTEMPTS = 5;
    public static final Duration DEFAULT_DELIVERY_INITIAL_BACKOFF = Duration.ofSeconds(1);
    public static final Duration DEFAULT_DELIVERY_MAX_BACKOFF = Duration.ofMinutes(1);
    public static final int DEFAULT_DELIVERY_QUEUE_SIZE = 1000;

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
     * The sending side: subscriptions and webhook delivery.
     */
    private PublisherSettings publisher = new PublisherSettings();

    /**
     * Participants allowed to publish events, with the event types each of them may publish.
     */
    private List<Publisher> publishers = new ArrayList<>();

    /**
     * True if the participant is a configured publisher of the given event type.
     */
    public boolean isAuthorized(@Nullable String publisherId, String type) {
        if (isBlank(publisherId)) {
            return false;
        }
        return publishers.stream()
                .filter(publisher -> !isBlank(publisher.getId()) && publisher.getId().equals(publisherId))
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
        if (!isBlank(ownParticipantId)) {
            return ownParticipantId;
        }
        return generalProperties.getDid();
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
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
         * Watch on the configuration directory, triggering a refresh when it changes.
         */
        private Watch watch = new Watch();

        @Data
        @ConfigurationProperties("verifier")
        public static class Verifier {

            /**
             * URL of the verifier's JWKS. Required when the listener or the publisher is enabled.
             */
            @Nullable
            private String jwksUrl;

            /**
             * Expected issuer of the tokens. Not checked if unset.
             */
            @Nullable
            private String issuer;

            /**
             * Audience the tokens have to be issued for. Required when the listener or the publisher is enabled.
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
             * Watched configuration directory, e.g. a ConfigMap mounted without subPath. Must be a directory, not a file.
             */
            @Nullable
            private String path;
        }
    }

    /**
     * The sending side. Named PublisherSettings since Publisher is an entry of the publishers list.
     */
    @Data
    @ConfigurationProperties("publisher")
    public static class PublisherSettings {

        /**
         * Enables subscriptions and delivery. Evaluated at startup only.
         */
        private boolean enabled = false;

        /**
         * Own DNS name, sent as WebHook-Request-Origin in the handshake. Required when the publisher is enabled.
         */
        @Nullable
        private String origin;

        /**
         * Lease of a subscription; it expires unless renewed within this time.
         */
        private Duration leaseTtl = DEFAULT_LEASE_TTL;

        /**
         * Maximum number of subscriptions a single subscriber may hold.
         */
        private int maxSubscriptionsPerSubscriber = DEFAULT_MAX_SUBSCRIPTIONS_PER_SUBSCRIBER;

        /**
         * Maximum number of subscriptions in total.
         */
        private int maxSubscriptions = DEFAULT_MAX_SUBSCRIPTIONS;

        /**
         * Accepts http sinks as well. When false, only https sinks are accepted.
         */
        private boolean allowInsecureSinks = false;

        /**
         * Connect timeout of handshake and delivery requests to sinks. Evaluated at startup only.
         */
        private Duration sinkConnectTimeout = DEFAULT_SINK_CONNECT_TIMEOUT;

        /**
         * Read timeout (per read) of handshake and delivery requests to sinks. Evaluated at startup only.
         */
        private Duration sinkReadTimeout = DEFAULT_SINK_READ_TIMEOUT;

        /**
         * Upper bound of a whole handshake, so a sink trickling its answer cannot hold it open.
         */
        private Duration handshakeTimeout = DEFAULT_HANDSHAKE_TIMEOUT;

        /**
         * Retries and queueing of deliveries.
         */
        private Delivery delivery = new Delivery();

        @Data
        @ConfigurationProperties("delivery")
        public static class Delivery {

            /**
             * Attempts per event, including the first one.
             */
            private int maxAttempts = DEFAULT_DELIVERY_MAX_ATTEMPTS;

            /**
             * Wait before the first retry, doubled on each further one.
             */
            private Duration initialBackoff = DEFAULT_DELIVERY_INITIAL_BACKOFF;

            /**
             * Upper bound of the wait between retries.
             */
            private Duration maxBackoff = DEFAULT_DELIVERY_MAX_BACKOFF;

            /**
             * Pending events per subscription. Further events are dropped while the queue is full.
             */
            private int queueSize = DEFAULT_DELIVERY_QUEUE_SIZE;
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
     * Enables beans of the sending side.
     */
    public static class PublisherCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            return context.getBean(DataSpaceEventsProperties.class)
                    .getPublisher()
                    .isEnabled();
        }
    }

    /**
     * Enables the bearer token verification, shared by the receiving endpoint and the subscriptions API.
     */
    public static class VerifierCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            DataSpaceEventsProperties properties = context.getBean(DataSpaceEventsProperties.class);
            return properties.getListener().isEnabled() || properties.getPublisher().isEnabled();
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
