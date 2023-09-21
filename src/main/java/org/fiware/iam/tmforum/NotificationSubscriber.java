package org.fiware.iam.tmforum;

import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.http.HttpResponse;
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

    private final EventsSubscriptionApiClient eventsSubscriptionApi;

    private final TaskScheduler taskScheduler;

    @Value("${general.basepath:}")
    private String controllerPath;

    @Value("${general.name:contract-management}")
    private String serviceUrl;

    /**
     * Register a subscription at the TM Forum API service after startup. Using the
     * {@link io.micronaut.scheduling.annotation.Scheduled} annotation resulted in start
     * failure like https://stackoverflow.com/q/77075901/4341660
     * @param event
     */
    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        //TODO Fixed rate should be better fit if we don't have an option to check if the subscription still exists
        taskScheduler.schedule(Duration.ofSeconds(10), () -> {
            try {
                String callbackUrl = String.format("http://%s%s%s", serviceUrl, controllerPath, PATH_LISTEN_TO_PRODUCT_ORDER_CREATE_EVENT);
                log.info("Attempting to register subscription with callback {}", callbackUrl);

                EventSubscriptionInputVO subscription = new EventSubscriptionInputVO()
                        .callback(callbackUrl)
                        .query("eventType=ProductOrderCreateEvent"); //TODO define query
                HttpResponse<EventSubscriptionVO> eventSubscriptionVOHttpResponse = eventsSubscriptionApi.registerListener(subscription);
                log.info("Got reply {} and status {}", eventSubscriptionVOHttpResponse.body(), eventSubscriptionVOHttpResponse.getStatus());
            } catch (Exception e) {
                log.error("Could not create subscription in TM Forum API", e);
            }
        });
    }
}
