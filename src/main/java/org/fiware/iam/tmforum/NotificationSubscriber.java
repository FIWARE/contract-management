package org.fiware.iam.tmforum;

import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.tmforum.product.client.api.EventsSubscriptionApiClient;
import org.fiware.iam.tmforum.product.client.model.EventSubscriptionInputVO;
import org.fiware.iam.tmforum.product.client.model.EventSubscriptionVO;

import java.time.Duration;

import static org.fiware.iam.tmforum.product.server.api.NotificationListenersClientSideApi.PATH_LISTEN_TO_PRODUCT_ORDER_CREATE_EVENT;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class NotificationSubscriber implements ApplicationEventListener<ServerStartupEvent> {

    private static final String QUERY_PRODUCT_ORDER_CREATED = "eventType=ProductOrderCreateEvent";

    private final EventsSubscriptionApiClient eventsSubscriptionApi;

    private final TaskScheduler taskScheduler;

    @Value("${general.basepath:}")
    private String controllerPath;

    @Value("${micronaut.server.port:8080}")
    private String servicePort;

    @Value("${general.name:contract-management}")
    private String serviceUrl;

    /**
     * Register a subscription at the TM Forum API service after startup. Using the
     * {@link io.micronaut.scheduling.annotation.Scheduled} annotation resulted in start
     * failure like https://stackoverflow.com/q/77075901/4341660
     *
     * @param event
     */
    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        //Using fixed rate since we don't have an option to check if the subscription (still) exists
        taskScheduler.scheduleAtFixedRate(Duration.ofSeconds(10), Duration.ofSeconds(30), () -> {
            try {
                String callbackUrl = String.format("http://%s:%s%s%s", serviceUrl, servicePort, controllerPath, PATH_LISTEN_TO_PRODUCT_ORDER_CREATE_EVENT);
                log.info("Attempting to register subscription with callback {}", callbackUrl);

                EventSubscriptionInputVO subscription = new EventSubscriptionInputVO()
                        .callback(callbackUrl)
                        .query(QUERY_PRODUCT_ORDER_CREATED);
                HttpResponse<EventSubscriptionVO> eventSubscriptionVOHttpResponse = eventsSubscriptionApi.registerListener(subscription);
                log.info("Got reply {} and status {}", eventSubscriptionVOHttpResponse.body(), eventSubscriptionVOHttpResponse.getStatus());
            } catch (HttpClientResponseException e) {
                if (e.getStatus() == HttpStatus.CONFLICT) {
                    log.debug("Subscription for query {}, was already present in TM Forum API service", QUERY_PRODUCT_ORDER_CREATED);
                } else {
                    log.error("Could not create subscription in TM Forum API. Response was {}", e.getResponse(), e);
                }
            } catch (HttpClientException e) {
                log.error("Could not create subscription in TM Forum API", e);
            }
        });
    }
}
