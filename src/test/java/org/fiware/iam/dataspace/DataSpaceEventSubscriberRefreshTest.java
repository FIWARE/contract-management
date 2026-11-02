package org.fiware.iam.dataspace;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.PropertySource;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.fiware.iam.dataspace.PublisherSubscriptionClient.Registration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks that a publisher added or removed by a refresh is subscribed or dropped without a restart (Step 4 of the plan).
 */
@MicronautTest(packages = {"org.fiware.iam"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataSpaceEventSubscriberRefreshTest implements TestPropertyProvider {

    private static final String INITIAL_PUBLISHER_ID = "did:web:provider.org";
    private static final String ADDED_PUBLISHER_ID = "did:web:other-provider.org";
    private static final String ADDED_PREFIX = "dataspace.events.publishers[1].";
    private static final String TYPE = "org.fiware.dataspace.example";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration LEASE = Duration.ofHours(1);
    private static final String REFRESHED_SOURCE = "refreshed";

    private final List<String> subscribedPublishers = new CopyOnWriteArrayList<>();
    private final Map<String, Object> refreshed = new ConcurrentHashMap<>();

    @Inject
    private ApplicationContext context;
    @Inject
    private ConfigurationRefreshLock refreshLock;
    @Inject
    private DataSpaceSubscriptionHealthIndicator indicator;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("micronaut.server.port", String.valueOf(freePort()));
        properties.put("endpoints.all.port", String.valueOf(freePort()));
        properties.put("notification.enabled", "false");
        properties.put("general.enable-rainbow", "false");
        properties.put("dataspace.events.listener.enabled", "true");
        properties.put("dataspace.events.listener.verifier.audience", "contract-management");
        properties.put("dataspace.events.listener.verifier.jwks-url", "http://verifier/.well-known/jwks");
        properties.put("dataspace.events.own-participant-id", "did:web:consumer.org");
        properties.put("dataspace.events.subscriber.enabled", "true");
        properties.put("dataspace.events.subscriber.sink", "https://cm.consumer.org/events");
        properties.put("dataspace.events.subscriber.protocol-settings.client-id", "contract-management");
        properties.put("dataspace.events.subscriber.initial-delay", "PT0.1S");
        properties.put("dataspace.events.publishers[0].id", INITIAL_PUBLISHER_ID);
        properties.put("dataspace.events.publishers[0].origin", "cm.provider.org");
        properties.put("dataspace.events.publishers[0].types[0]", TYPE);
        properties.put("dataspace.events.publishers[0].subscriptions-url", "https://cm.provider.org/subscriptions");
        return properties;
    }

    @MockBean(PublisherSubscriptionClient.class)
    PublisherSubscriptionClient subscriptionClient() {
        PublisherSubscriptionClient accepting = mock(PublisherSubscriptionClient.class);
        when(accepting.subscribe(any())).thenAnswer(invocation -> {
            subscribedPublishers.add(((SubscriptionTarget) invocation.getArguments()[0]).publisherId());
            return Mono.just(Registration.registered("subscription-" + subscribedPublishers.size(), Instant.now().plus(LEASE)));
        });
        when(accepting.unsubscribe(any(), any())).thenReturn(Mono.empty());
        return accepting;
    }

    @Test
    void addedPublisherIsSubscribedAndRemovedOneLeavesTheHealthCheck() {
        await().atMost(AWAIT_TIMEOUT).until(() -> subscribedPublishers.contains(INITIAL_PUBLISHER_ID));
        context.getEnvironment().addPropertySource(PropertySource.of(REFRESHED_SOURCE, refreshed));

        refreshed.put(ADDED_PREFIX + "id", ADDED_PUBLISHER_ID);
        refreshed.put(ADDED_PREFIX + "origin", "cm.other-provider.org");
        refreshed.put(ADDED_PREFIX + "types[0]", TYPE);
        refreshed.put(ADDED_PREFIX + "subscriptions-url", "https://cm.other-provider.org/subscriptions");
        refresh();

        await().atMost(AWAIT_TIMEOUT).until(() -> "REGISTERED".equals(state(ADDED_PUBLISHER_ID)));
        assertEquals(1, subscribedPublishers.stream().filter(ADDED_PUBLISHER_ID::equals).count(), "registered once");

        // a refresh cannot drop a key, so the operator clears the URL (Decision 1)
        refreshed.put(ADDED_PREFIX + "subscriptions-url", "");
        refresh();

        await().atMost(AWAIT_TIMEOUT).until(() -> !details().containsKey(ADDED_PUBLISHER_ID));
        assertEquals("REGISTERED", state(INITIAL_PUBLISHER_ID));
    }

    private void refresh() {
        refreshLock.exclusively(() -> {
            context.publishEvent(new RefreshEvent(context.getEnvironment().refreshAndDiff()));
            return null;
        });
    }

    private Map<?, ?> details() {
        return (Map<?, ?>) indicator.result().getDetails();
    }

    private Object state(String publisherId) {
        Map<?, ?> publisher = (Map<?, ?>) details().get(publisherId);
        return publisher == null ? null : publisher.get(DataSpaceSubscriptionHealthIndicator.STATE_DETAIL);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
