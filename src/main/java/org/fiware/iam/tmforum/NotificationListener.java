package org.fiware.iam.tmforum;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.fiware.iam.til.TrustedIssuerMapper;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.fiware.iam.tmforum.api.NotificationListenersClientSideApi;
import org.fiware.iam.tmforum.model.*;
import reactor.core.publisher.Mono;

import java.util.List;

@Controller("${general.basepath:/}")
@RequiredArgsConstructor
public class NotificationListener implements NotificationListenersClientSideApi {

    private final TrustedIssuerMapper mapper;

    private final TrustedIssuersListAdapter adapter;

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

        // call
        adapter.allowIssuer("someDid","someVCType", List.of(mapper.map("someTargetDid",List.of("someRole"))));
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
