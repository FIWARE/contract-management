package org.fiware.iam.management;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.cm.model.OrderEventVO;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.domain.ContractManagement;
import org.fiware.iam.http.AuthHandler;
import org.fiware.iam.http.Oid4VpAuthHandler;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Optional;

import static org.fiware.iam.http.Oid4VpAuthHandler.CLIENT_ID_ATTRIBUTE;
import static org.fiware.iam.http.Oid4VpAuthHandler.SCOPE_ATTRIBUTE;


/**
 * Adapter to other contract management instances.
 */
@Requires(condition = GeneralProperties.CentralMarketplaceCondition.class)
@Singleton
@Slf4j
@RequiredArgsConstructor
public class ContractManagementAdapter {

    private static final String ORDER_PATH = "order";
    private static final String STOP_EVENT = "stop";
    private static final String START_EVENT = "start";

    private final HttpClient httpClient;
    // only use authentication if a handler is provided
    private final Optional<AuthHandler> optionalAuthHandler;

    public Mono<HttpResponse> handleOrderStart(ContractManagement contractManagement, OrderEventVO orderStartEventVO) {
        MutableHttpRequest<OrderEventVO> request = HttpRequest.POST(getOrderUri(contractManagement.getAddress(), START_EVENT), orderStartEventVO);
        request.setAttribute(SCOPE_ATTRIBUTE, contractManagement.getScope());
        request.setAttribute(CLIENT_ID_ATTRIBUTE, contractManagement.getClientId());
        return optionalAuthHandler
                .map(authHandler -> authHandler.executeWithAuth(request, req -> Mono.from(httpClient.exchange(req))))
                .orElseGet(() -> Mono.from(httpClient.exchange(request, String.class)));

    }

    public Mono<HttpResponse> handleOrderStop(ContractManagement contractManagement, OrderEventVO orderStopEventVO) {
        MutableHttpRequest<OrderEventVO> request = HttpRequest.POST(getOrderUri(contractManagement.getAddress(), STOP_EVENT), orderStopEventVO);
        request.setAttribute(SCOPE_ATTRIBUTE, contractManagement.getScope());
        request.setAttribute(CLIENT_ID_ATTRIBUTE, contractManagement.getClientId());
        return optionalAuthHandler
                .map(authHandler -> authHandler.executeWithAuth(request, req -> Mono.from(httpClient.exchange(req))))
                .orElseGet(() -> Mono.from(httpClient.exchange(request)));
    }

    private URI getOrderUri(String address, String event) {
        if (address.endsWith("/")) {
            return URI.create(address + ORDER_PATH + "/" + event);
        } else {
            return URI.create(address + "/" + ORDER_PATH + "/" + event);
        }
    }
}

