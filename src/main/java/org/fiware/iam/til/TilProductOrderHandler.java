package org.fiware.iam.til;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseFactory;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.handlers.ProductOrderHandler;
import org.fiware.iam.tmforum.CredentialsConfigResolver;
import org.fiware.iam.tmforum.OrganizationResolver;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import reactor.core.publisher.Mono;

@Requires(condition = GeneralProperties.TilCondition.class)
@RequiredArgsConstructor
@Singleton
@Slf4j
public class TilProductOrderHandler implements ProductOrderHandler {

    private final OrganizationResolver organizationResolver;
    private final CredentialsConfigResolver credentialsConfigResolver;
    private final TrustedIssuersListAdapter trustedIssuersListAdapter;

    @Override
    public Mono<HttpResponse<?>> handleProductOrderComplete(String organizationId, ProductOrderVO productOrderVO) {
        return allowIssuer(organizationId, productOrderVO);
    }

    @Override
    public Mono<HttpResponse<?>> handleProductOrderStop(String organizationId, ProductOrderVO productOrderVO) {
        return Mono.zip(organizationResolver.getDID(organizationId), credentialsConfigResolver.getCredentialsConfig(productOrderVO))
                .flatMap(resultTuple -> trustedIssuersListAdapter.denyIssuer(resultTuple.getT1(), resultTuple.getT2()));
    }

    @Override
    public Mono<HttpResponse<?>> handleProductOrderNegotiation(String organizationId, ProductOrderVO productOrderVO) {
        // nothing to do
        return Mono.just(HttpResponse.noContent());
    }

    private Mono<HttpResponse<?>> allowIssuer(String organizationId, ProductOrderVO productOrderVO) {
        return Mono.zip(
                        organizationResolver.getDID(organizationId),
                        credentialsConfigResolver.getCredentialsConfig(productOrderVO))
                .flatMap(resultTuple -> trustedIssuersListAdapter.allowIssuer(resultTuple.getT1(), resultTuple.getT2()))
                .map(success -> {
                    if (success) {
                        return HttpResponseFactory.INSTANCE.status(HttpStatus.CREATED);
                    } else {
                        log.warn("Was not able to allow issuer {} for product order {}.", organizationId, productOrderVO);
                        return HttpResponseFactory.INSTANCE.status(HttpStatus.BAD_GATEWAY);
                    }
                });
    }
}
