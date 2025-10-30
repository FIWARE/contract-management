package org.fiware.iam.pap;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.handlers.ProductOrderHandler;
import org.fiware.iam.tmforum.OrganizationResolver;
import org.fiware.iam.tmforum.PolicyResolver;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Handler implementation to publish product order information to the pap
 */
@Requires(condition = GeneralProperties.PapCondition.class)
@RequiredArgsConstructor
@Singleton
@Slf4j
public class PapProductOrderHandler implements ProductOrderHandler {

    private final PolicyResolver policyResolver;
    private final OrganizationResolver organizationResolver;
    private final PAPAdapter papAdapter;

    @Override
    public Mono<HttpResponse<?>> handleProductOrderComplete(String organizationId, ProductOrderVO productOrderVO) {
        return createPolicy(organizationId, productOrderVO);
    }

    @Override
    public Mono<HttpResponse<?>> handleProductOrderStop(String organizationId, ProductOrderVO productOrderVO) {

        return policyResolver
                .getAuthorizationPolicy(productOrderVO)
                .map(this::filterLocalPolicies)
                .flatMap(policies -> Mono.zipDelayError(policies.stream()
                                .map(p -> papAdapter.deletePolicy(productOrderVO.getId(), p)).toList(),
                        results -> {
                            if (Stream.of(results).map(r -> (Boolean) r).toList().contains(false)) {
                                return HttpResponse.status(HttpStatus.BAD_GATEWAY);
                            }
                            return HttpResponse.ok();
                        }
                ));
    }

    @Override
    public Mono<HttpResponse<?>> handleProductOrderNegotiation(String organizationId, ProductOrderVO productOrderVO) {
        // nothing to do
        return Mono.just(HttpResponse.noContent());
    }

    private Mono<HttpResponse<?>> createPolicy(String organizationId, ProductOrderVO productOrderVO) {

        return organizationResolver.getDID(organizationId)
                .flatMap(did -> policyResolver
                        .getAuthorizationPolicy(productOrderVO)
                        .map(this::filterLocalPolicies)
                        .flatMap(policies -> Mono.zipDelayError(policies.stream()
                                        .map(p -> papAdapter.createPolicy(did, productOrderVO.getId(), p)).toList(),
                                results -> {
                                    if (Stream.of(results).map(r -> (Boolean) r).toList().contains(false)) {
                                        return HttpResponse.status(HttpStatus.BAD_GATEWAY);
                                    }
                                    return HttpResponse.ok();
                                }
                        )));
    }

    // only return policies intended for local
    private List<Map<String, Object>> filterLocalPolicies(List<PolicyResolver.PolicyConfig> policyConfigs) {
        return policyConfigs.stream()
                .filter(policyConfig -> policyConfig.contractManagement().isLocal())
                .map(PolicyResolver.PolicyConfig::policies)
                .flatMap(List::stream)
                .toList();
    }
}
