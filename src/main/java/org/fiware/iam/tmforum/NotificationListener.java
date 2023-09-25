package org.fiware.iam.tmforum;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
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
        log.warn("Received an unimplemented CancelProductOrderCreateEvent: {}", data);
        return HttpResponse.status(HttpStatus.NOT_IMPLEMENTED, "Not supported yet.");
    }

    @Override
    public HttpResponse<EventSubscriptionVO> listenToCancelProductOrderInformationRequiredEvent(CancelProductOrderInformationRequiredEventVO data) {
        log.warn("Received an unimplemented CancelProductOrderInformationRequiredEvent: {}", data);
        return HttpResponse.status(HttpStatus.NOT_IMPLEMENTED, "Not supported yet.");
    }

    @Override
    public HttpResponse<EventSubscriptionVO> listenToCancelProductOrderStateChangeEvent(CancelProductOrderStateChangeEventVO data) {
        log.warn("Received an unimplemented CancelProductOrderStateChangeEvent: {}", data);
        return HttpResponse.status(HttpStatus.NOT_IMPLEMENTED, "Not supported yet.");
    }

    @Override
    public HttpResponse<EventSubscriptionVO> listenToProductOrderAttributeValueChangeEvent(ProductOrderAttributeValueChangeEventVO data) {
        log.warn("Received an unimplemented ProductOrderAttributeValueChangeEvent: {}", data);
        return HttpResponse.status(HttpStatus.NOT_IMPLEMENTED, "Not supported yet.");
    }


    @Override
    public HttpResponse<EventSubscriptionVO> listenToProductOrderCreateEvent(ProductOrderCreateEventVO data) {
        log.info("Received a ProductOrder Created Event: {}", data);
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

        adapter.allowIssuer(didOrderingOrganization);
        log.info("Successfully added {}", didOrderingOrganization);
        return HttpResponse.noContent();
    }

    @Override
    public HttpResponse<EventSubscriptionVO> listenToProductOrderDeleteEvent(ProductOrderDeleteEventVO data) {
        log.warn("Received an unimplemented ProductOrderDeleteEvent: {}", data);
        return HttpResponse.status(HttpStatus.NOT_IMPLEMENTED, "Not supported yet.");
    }

    @Override
    public HttpResponse<EventSubscriptionVO> listenToProductOrderInformationRequiredEvent(ProductOrderInformationRequiredEventVO data) {
        log.warn("Received an unimplemented ProductOrderInformationRequiredEvent: {}", data);
        return HttpResponse.status(HttpStatus.NOT_IMPLEMENTED, "Not supported yet.");
    }

    @Override
    public HttpResponse<EventSubscriptionVO> listenToProductOrderStateChangeEvent(ProductOrderStateChangeEventVO data) {
        log.warn("Received an unimplemented ProductOrderStateChangeEvent: {}", data);
        return HttpResponse.status(HttpStatus.NOT_IMPLEMENTED, "Not supported yet.");
    }
}
