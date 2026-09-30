package org.fiware.iam.pap;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TMForumException;
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

    @Override
    public String getName() {
        return "pap";
    }

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
                .flatMap(policies -> {
                    if (policies.isEmpty()) {
                        log.debug("Order {} carries no local policy; nothing to delete at the pap.", productOrderVO.getId());
                        return Mono.just(HttpResponse.noContent());
                    }
                    return Mono.zipDelayError(policies.stream()
                                    .map(p -> papAdapter.deletePolicy(productOrderVO.getId(), p)).toList(),
                            results -> toResponse(productOrderVO.getId(), "deleted", results.length, results));
                });
    }

    @Override
    public Mono<HttpResponse<?>> handleProductOrderNegotiation(String organizationId, ProductOrderVO productOrderVO) {
        // nothing to do
        return Mono.just(HttpResponse.noContent());
    }

    private Mono<HttpResponse<?>> createPolicy(String organizationId, ProductOrderVO productOrderVO) {

        return organizationResolver.getDID(organizationId)
                .switchIfEmpty(Mono.error(() -> new TMForumException(FailureReason.ORGANIZATION_DID_MISSING,
                        "No DID could be resolved for the customer organization %s.".formatted(organizationId))))
                .flatMap(did -> policyResolver
                        .getAuthorizationPolicy(productOrderVO)
                        .map(this::filterLocalPolicies)
                        .flatMap(policies -> {
                            // An order without a local policy is not a failure - but zipping an empty
                            // list completes empty, the listener then answers 404 and the TM Forum API
                            // keeps redelivering the notification, re-running every order handler.
                            if (policies.isEmpty()) {
                                log.debug("Order {} carries no local policy; nothing to publish at the pap.",
                                        productOrderVO.getId());
                                return Mono.just(HttpResponse.noContent());
                            }
                            return Mono.zipDelayError(policies.stream()
                                            .map(p -> papAdapter.createPolicy(did, productOrderVO.getId(), p)).toList(),
                                    results -> toResponse(productOrderVO.getId(), "created", policies.size(), results));
                        }));
    }

    private HttpResponse<?> toResponse(String orderId, String action, int expected, Object[] results) {
        long failed = Stream.of(results).map(r -> (Boolean) r).filter(success -> !success).count();
        if (failed > 0) {
            log.warn("Order {}: only {} of {} policies could be {} at the pap.", orderId, expected - failed, expected, action);
            return HttpResponse.status(HttpStatus.BAD_GATEWAY);
        }
        log.debug("Order {}: {} {} policies at the pap.", orderId, action, expected);
        return HttpResponse.ok();
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
