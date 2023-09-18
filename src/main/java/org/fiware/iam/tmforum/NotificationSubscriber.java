package org.fiware.iam.tmforum;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.tmforum.api.EventsSubscriptionApiClient;
import org.fiware.iam.tmforum.model.EventSubscriptionInputVO;
import org.fiware.iam.tmforum.model.EventSubscriptionVO;

import static org.fiware.iam.tmforum.api.NotificationListenersClientSideApiClient.PATH_LISTEN_TO_PRODUCT_ORDER_CREATE_EVENT;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class NotificationSubscriber {

    private final EventsSubscriptionApiClient eventsSubscriptionApi;

    @Value("${general.basepath:}")
    private String controllerPath;

    @Value("${general.name:contract-management}")
    private String serviceUrl;

    @Scheduled(fixedRate = "2min")
    public void registerNotificationSubscription() {
        try {
            String callbackUrl = String.format("%s%s%s", serviceUrl, controllerPath, PATH_LISTEN_TO_PRODUCT_ORDER_CREATE_EVENT);
            log.info("Attempting to register subscription with callback {}", callbackUrl);

            EventSubscriptionInputVO subscription = new EventSubscriptionInputVO()
                    .callback(callbackUrl)
                    .query(""); //TODO define query
            HttpResponse<EventSubscriptionVO> eventSubscriptionVOHttpResponse = eventsSubscriptionApi.registerListener(subscription);
            log.info("Got reply {} and status {}", eventSubscriptionVOHttpResponse.body(), eventSubscriptionVOHttpResponse.getStatus());
        } catch (Exception e) {
            log.error("Could not create subscription in TM Forum API", e);
        }

        // register new notification if necessary

    }
}
