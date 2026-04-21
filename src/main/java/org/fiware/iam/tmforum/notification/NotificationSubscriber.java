package org.fiware.iam.tmforum.notification;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.netty.DefaultHttpClient;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.micronaut.scheduling.TaskScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.configuration.NotificationProperties;
import org.fiware.iam.tmforum.party.model.EventSubscriptionInputVO;
import org.fiware.iam.tmforum.party.model.EventSubscriptionVO;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Responsible for subscribing to all configured tmforum events.
 */
@Requires(condition = NotificationProperties.NotificationCondition.class)
@Context
@RequiredArgsConstructor
@Slf4j
public class NotificationSubscriber {

    private static final String QUERY_TEMPLATE = "eventType=%s%s";
    private static final String LISTENER_ADDRESS_TEMPLATE = "%s/hub";
    private static final String LISTENER_PATH = "/listener/event";

    private final SubscriptionHealthIndicator subscriptionHealthIndicator;
    private final NotificationProperties notificationProperties;
    private final GeneralProperties generalProperties;
    private final DefaultHttpClient httpClient;
    private final TaskScheduler taskScheduler;

    @Value("${micronaut.server.port:8080}")
    private String servicePort;

    @EventListener
    public void onApplicationEvent(ServerStartupEvent e) {
        notificationProperties.getEntities()
                .forEach(tmForumEntity ->
                        Optional.ofNullable(tmForumEntity.getEventTypes())
                                .orElse(List.of())
                                .forEach(eventType -> {
                                    subscriptionHealthIndicator.initiateSubscriptionInMap(tmForumEntity.getEntityType() + eventType.getValue());
                                    taskScheduler.scheduleAtFixedRate(Duration.ofSeconds(5), Duration.ofSeconds(10), () -> createSubscription(tmForumEntity.getEntityType(), eventType.getValue(), tmForumEntity.getApiAddress()));
                                }));
    }

    private static String removeTrailingSlash(String theString) {
        if (theString.endsWith("/")) {
            return theString.substring(0, theString.length() - 1);
        }
        return theString;
    }

    public void createSubscription(String entityType, String eventType, String apiAddress) {
        String callbackUrl = String.format("http://%s:%s%s%s", notificationProperties.getHost(), servicePort, removeTrailingSlash(generalProperties.getBasePath()), LISTENER_PATH);
        log.debug("Attempting to register subscription for {} {} events at {}", entityType, eventType, String.format(LISTENER_ADDRESS_TEMPLATE, apiAddress));

        EventSubscriptionInputVO subscription = new EventSubscriptionInputVO()
                .callback(callbackUrl)
                .query(String.format(QUERY_TEMPLATE, entityType, eventType));

        HttpRequest<?> request = HttpRequest.create(HttpMethod.POST, String.format(LISTENER_ADDRESS_TEMPLATE, apiAddress)).body(subscription);

        Mono.from(httpClient.exchange(request, EventSubscriptionVO.class))
                .doOnSuccess(res -> {
                    subscriptionHealthIndicator.setSubscriptionHealthy(entityType + eventType);
                    log.info("Successfully subscribed to {} {} events at {}", entityType, eventType, request.getUri());
                })
                .onErrorResume(t -> {
                    if (t instanceof HttpClientResponseException e) {
                        if (e.getStatus() == HttpStatus.CONFLICT) {
                            subscriptionHealthIndicator.setSubscriptionHealthy(entityType + eventType);
                            log.info("Subscription for {} {} already exists at {}", entityType, eventType, request.getUri());
                        } else {
                            String body = e.getResponse().getBody(String.class).orElse("<no body>");
                            log.warn("Event registration failed for {} at {} - Status: {} | Message: {} | Body: {}", entityType, request.getUri(), e.getStatus(), e.getMessage(), body);
                        }
                        return Mono.empty();
                    }
                    log.warn("Could not create subscription for {} in TM Forum API", entityType, t);
                    return Mono.empty();
                }).subscribe();

    }
}
