package org.fiware.iam.tmforum;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.fiware.iam.tmforum.product.server.api.NotificationListenersClientSideApi;
import org.fiware.iam.tmforum.product.server.model.*;

import java.util.Collection;
import java.util.stream.Stream;

@Slf4j
@Controller("${general.basepath:/}")
@RequiredArgsConstructor
public class NotificationListener implements NotificationListenersClientSideApi {

    private final TrustedIssuersListAdapter adapter;

    private final OrganizationResolver organizationResolver;

    @Override
    public HttpResponse<EventSubscriptionVO> listenToCancelProductOrderCreateEvent(CancelProductOrderCreateEventVO data) {
        return HttpResponse.ok();
    }

    @Override
    public HttpResponse<EventSubscriptionVO> listenToCancelProductOrderInformationRequiredEvent(CancelProductOrderInformationRequiredEventVO data) {
        return HttpResponse.ok();
    }

    @Override
    public HttpResponse<EventSubscriptionVO> listenToCancelProductOrderStateChangeEvent(CancelProductOrderStateChangeEventVO data) {
        return HttpResponse.ok();
    }

    @Override
    public HttpResponse<EventSubscriptionVO> listenToProductOrderAttributeValueChangeEvent(ProductOrderAttributeValueChangeEventVO data) {
        return HttpResponse.ok();
    }


    @Override
    public HttpResponse<EventSubscriptionVO> listenToProductOrderCreateEvent(ProductOrderCreateEventVO data) {
        // validate notification type
        log.info("Received a ProductOrder Created Event: {}", data);
        try {
            String didOrderingOrganization = Stream
                    .ofNullable(data)
                    .map(ProductOrderCreateEventVO::getEvent)
                    .map(ProductOrderCreateEventPayloadVO::getProductOrder)
                    .map(ProductOrderVO::getRelatedParty)
                    .flatMap(Collection::stream)
                    .map(RelatedPartyVO::getId)
                    .reduce((a, b) -> {
                        throw new IllegalArgumentException("Expected exactly one ordering organization.");
                    })
                    .map(organizationResolver::getDID)
                    .orElseThrow(() -> new IllegalArgumentException("Expected exactly one ordering organization, none found."));

            // call
            adapter.allowIssuer(didOrderingOrganization);
        } catch (Exception e) {
            log.error("Could not set up trusted issuer based on the event: {}", data, e);
        }
        // Notification sender does not care about the listeners issues
        return HttpResponse.ok();
    }

    @Override
    public HttpResponse<EventSubscriptionVO> listenToProductOrderDeleteEvent(ProductOrderDeleteEventVO data) {
        return HttpResponse.ok();
    }

    @Override
    public HttpResponse<EventSubscriptionVO> listenToProductOrderInformationRequiredEvent(ProductOrderInformationRequiredEventVO data) {
        return HttpResponse.ok();
    }

    @Override
    public HttpResponse<EventSubscriptionVO> listenToProductOrderStateChangeEvent(ProductOrderStateChangeEventVO data) {
        return HttpResponse.ok();
    }
}
