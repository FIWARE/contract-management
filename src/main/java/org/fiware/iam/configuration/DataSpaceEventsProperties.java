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

/**
 * Configuration of the data space event channel. Micronaut's refresh scope rebinds this singleton in place on a refresh.
 * Readers must go through the injected bean on every use and never copy its values into fields, or a refresh has no effect.
 * A key removed from the configuration keeps its previous value; set it to an empty value instead.
 * The nested classes are separate beans for binding only; inject this class, never them, or the copy read is not the rebound one.
 */
@Data
@ConfigurationProperties("dataspace.events")
public class DataSpaceEventsProperties {

    public static final String DEFAULT_IDENTITY_HEADER = "X-Userinfo";
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
    public static final Duration DEFAULT_DELIVERY_ATTEMPT_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_SUBSCRIBER_INITIAL_DELAY = Duration.ofSeconds(5);
    public static final Duration DEFAULT_SUBSCRIBER_RETRY_INITIAL_BACKOFF = Duration.ofSeconds(5);
    public static final Duration DEFAULT_SUBSCRIBER_RETRY_MAX_BACKOFF = Duration.ofMinutes(5);
    public static final double DEFAULT_SUBSCRIBER_RENEWAL_FRACTION = 0.5;
    public static final Duration DEFAULT_SUBSCRIBER_MIN_RENEWAL_DELAY = Duration.ofSeconds(10);
    public static final Duration DEFAULT_SUBSCRIBER_CONFLICT_RENEWAL_INTERVAL = Duration.ofHours(1);
    public static final Duration DEFAULT_SUBSCRIBER_RECONCILE_INTERVAL = Duration.ofMinutes(1);
    public static final Duration DEFAULT_TIR_WATCHER_INITIAL_DELAY = Duration.ofSeconds(30);
    public static final Duration DEFAULT_TIR_WATCHER_INTERVAL = Duration.ofMinutes(1);
    public static final int DEFAULT_TIR_WATCHER_PAGE_SIZE = TirWatcherSettings.MAX_PAGE_SIZE;
    public static final int DEFAULT_TIR_WATCHER_MAX_PAGES = 1000;
    public static final int DEFAULT_TIR_WATCHER_CONFIRMATIONS = 2;
    public static final int DEFAULT_TIR_WATCHER_MAX_REMOVALS_PER_CYCLE = 5;

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
     * The subscribing side: registration of the own listener at the configured publishers.
     */
    private SubscriberSettings subscriber = new SubscriberSettings();

    /**
     * The trust anchor side: announces participants removed from the trusted issuers registry. Requires the publisher.
     */
    private TirWatcherSettings tirWatcher = new TirWatcherSettings();

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
         * The caller identity the API gateway passes on.
         */
        private Identity identity = new Identity();

        /**
         * Deduplication of retried deliveries.
         */
        private Dedup dedup = new Dedup();

        /**
         * Watch on the configuration directory, triggering a refresh when it changes.
         */
        private Watch watch = new Watch();

        @Data
        @ConfigurationProperties("identity")
        public static class Identity {

            /**
             * Request header the API gateway sets with the claims of the token it verified, base64-encoded or plain JSON.
             */
            private String header = DEFAULT_IDENTITY_HEADER;

            /**
             * Top-level claim carrying the participant id of the caller.
             */
            private String participantClaim = DEFAULT_PARTICIPANT_CLAIM;
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

            /**
             * Upper bound of one attempt incl. a token fetch the sink triggers, not counting the wait for the rate limit.
             * A timed-out attempt is retried.
             */
            private Duration attemptTimeout = DEFAULT_DELIVERY_ATTEMPT_TIMEOUT;
        }
    }

    /**
     * The subscribing side: registers the own listener as webhook sink at every publisher with a subscriptions-url.
     */
    @Data
    @ConfigurationProperties("subscriber")
    public static class SubscriberSettings {

        /**
         * Enables the registration at the publishers. Requires the listener. Evaluated at startup only.
         */
        private boolean enabled = false;

        /**
         * Public URL of the own POST /events, registered as sink. Required when the subscriber is enabled.
         */
        @Nullable
        private String sink;

        /**
         * OID4VP client id used towards the publishers' subscriptions API, unless a publisher overrides it.
         */
        @Nullable
        private String clientId;

        /**
         * OID4VP scopes used towards the publishers' subscriptions API, unless a publisher overrides them.
         */
        private List<String> scopes = new ArrayList<>();

        /**
         * Client at the own verifier that the publishers use when the sink answers 401, sent as protocolsettings.
         */
        private ProtocolSettings protocolSettings = new ProtocolSettings();

        /**
         * Delay of the first registrations after startup.
         */
        private Duration initialDelay = DEFAULT_SUBSCRIBER_INITIAL_DELAY;

        /**
         * Fraction of the remaining lease after which it is renewed. Values outside (0, 1) fall back to the default.
         */
        private double renewalFraction = DEFAULT_SUBSCRIBER_RENEWAL_FRACTION;

        /**
         * Lower bound of the renewal delay, so a short or past expiry cannot cause a tight loop.
         */
        private Duration minRenewalDelay = DEFAULT_SUBSCRIBER_MIN_RENEWAL_DELAY;

        /**
         * Wait before re-registering after a 409, which carries no expiry.
         */
        private Duration conflictRenewalInterval = DEFAULT_SUBSCRIBER_CONFLICT_RENEWAL_INTERVAL;

        /**
         * Interval of the periodic reconcile backing up the refresh reaction.
         */
        private Duration reconcileInterval = DEFAULT_SUBSCRIBER_RECONCILE_INTERVAL;

        /**
         * Backoff of failed registrations, retried without limit.
         */
        private Retry retry = new Retry();

        @Data
        @ConfigurationProperties("protocol-settings")
        public static class ProtocolSettings {

            /**
             * OID4VP client id at the own verifier. Required when the subscriber is enabled.
             */
            @Nullable
            private String clientId;

            /**
             * OID4VP scopes at the own verifier.
             */
            private List<String> scopes = new ArrayList<>();
        }

        @Data
        @ConfigurationProperties("retry")
        public static class Retry {

            /**
             * Wait before the first retry, doubled on each further one.
             */
            private Duration initialBackoff = DEFAULT_SUBSCRIBER_RETRY_INITIAL_BACKOFF;

            /**
             * Upper bound of the wait between retries.
             */
            private Duration maxBackoff = DEFAULT_SUBSCRIBER_RETRY_MAX_BACKOFF;
        }
    }

    /**
     * Polling of the trusted issuers registry. A refreshed value out of range is clamped by its effective getter, which
     * all readers use; at startup such a value is rejected instead.
     */
    @Data
    @ConfigurationProperties("tir-watcher")
    public static class TirWatcherSettings {

        // Bounds of page[size] in the trusted issuers registry API.
        public static final int MIN_PAGE_SIZE = 1;
        public static final int MAX_PAGE_SIZE = 100;

        public static final int MIN_MAX_PAGES = 1;
        public static final int MIN_CONFIRMATIONS = 1;
        public static final int MIN_MAX_REMOVALS_PER_CYCLE = 0;

        /**
         * Enables the watcher. Requires the publisher. Evaluated at startup only.
         */
        private boolean enabled = false;

        /**
         * Delay of the first cycle after startup. Null or non-positive falls back to the default.
         */
        @Nullable
        private Duration initialDelay = DEFAULT_TIR_WATCHER_INITIAL_DELAY;

        /**
         * Wait between the end of a cycle and the start of the next. Null or non-positive falls back to the default.
         */
        @Nullable
        private Duration interval = DEFAULT_TIR_WATCHER_INTERVAL;

        /**
         * Requested page[size], clamped to 1..100. The registry may still answer with smaller pages.
         */
        private int pageSize = DEFAULT_TIR_WATCHER_PAGE_SIZE;

        /**
         * Pages fetched per enumeration at most; a registry needing more is treated as incomplete. At least 1.
         */
        private int maxPages = DEFAULT_TIR_WATCHER_MAX_PAGES;

        /**
         * Consecutive complete cycles a participant must be missing before its removal is announced. At least 1.
         */
        private int confirmations = DEFAULT_TIR_WATCHER_CONFIRMATIONS;

        /**
         * Circuit breaker: more participants missing at once (newly missing plus those awaiting confirmation) stops the
         * cycle with an alert. 0 lets every removal trip it, so none is announced until the cap is raised.
         */
        private int maxRemovalsPerCycle = DEFAULT_TIR_WATCHER_MAX_REMOVALS_PER_CYCLE;

        public Duration effectiveInitialDelay() {
            return positiveOrDefault(initialDelay, DEFAULT_TIR_WATCHER_INITIAL_DELAY);
        }

        public Duration effectiveInterval() {
            return positiveOrDefault(interval, DEFAULT_TIR_WATCHER_INTERVAL);
        }

        public int effectivePageSize() {
            return Math.clamp(pageSize, MIN_PAGE_SIZE, MAX_PAGE_SIZE);
        }

        public int effectiveMaxPages() {
            return Math.max(maxPages, MIN_MAX_PAGES);
        }

        public int effectiveConfirmations() {
            return Math.max(confirmations, MIN_CONFIRMATIONS);
        }

        public int effectiveMaxRemovalsPerCycle() {
            return Math.max(maxRemovalsPerCycle, MIN_MAX_REMOVALS_PER_CYCLE);
        }

        private static Duration positiveOrDefault(@Nullable Duration value, Duration fallback) {
            return value == null || value.isZero() || value.isNegative() ? fallback : value;
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

        /**
         * URL of the publisher's subscriptions API. Without it (or blank) the publisher is listener-only and never subscribed to.
         */
        @Nullable
        private String subscriptionsUrl;

        /**
         * OID4VP client id towards this publisher's subscriptions API. Blank falls back to subscriber.client-id.
         */
        @Nullable
        private String clientId;

        /**
         * OID4VP scopes towards this publisher's subscriptions API. Empty falls back to subscriber.scopes.
         */
        @Nullable
        private List<String> scopes;
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
     * Enables beans of the subscribing side.
     */
    public static class SubscriberCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            return context.getBean(DataSpaceEventsProperties.class)
                    .getSubscriber()
                    .isEnabled();
        }
    }

    /**
     * Enables the resolution of the caller identity, shared by the receiving endpoint and the subscriptions API.
     */
    public static class IdentityCondition implements Condition {

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

    /**
     * Enables the trust anchor watcher, which publishes through the sending side.
     */
    public static class TirWatcherCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            DataSpaceEventsProperties properties = context.getBean(DataSpaceEventsProperties.class);
            return properties.getPublisher().isEnabled() && properties.getTirWatcher().isEnabled();
        }
    }
}
