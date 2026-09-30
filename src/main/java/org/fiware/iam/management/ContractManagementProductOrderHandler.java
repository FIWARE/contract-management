package org.fiware.iam.management;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseFactory;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.cm.model.CredentialVO;
import org.fiware.iam.cm.model.OdrlPolicyJsonVO;
import org.fiware.iam.cm.model.OrderEventVO;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.domain.ContractManagement;
import org.fiware.iam.handlers.ProductOrderHandler;
import org.fiware.iam.tmforum.CredentialsConfigResolver;
import org.fiware.iam.tmforum.OrganizationResolver;
import org.fiware.iam.tmforum.PolicyResolver;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.util.*;
import java.util.function.BiFunction;

@Requires(condition = GeneralProperties.CentralMarketplaceCondition.class)
@RequiredArgsConstructor
@Singleton
@Slf4j
public class ContractManagementProductOrderHandler implements ProductOrderHandler {

    @Override
    public String getName() {
        return "contract-management";
    }

    private final ContractManagementAdapter contractManagementAdapter;
    private final OrganizationResolver organizationResolver;
    private final CredentialsConfigResolver credentialsConfigResolver;
    private final PolicyResolver policyResolver;
    private final CMMapper cmMapper;

    @Override
    public Mono<HttpResponse<?>> handleProductOrderComplete(String organizationId, ProductOrderVO productOrderVO) {
        return handleOrderEvent(organizationId, productOrderVO, contractManagementAdapter::handleOrderStart);
    }


    @Override
    public Mono<HttpResponse<?>> handleProductOrderStop(String organizationId, ProductOrderVO productOrderVO) {
        return handleOrderEvent(organizationId, productOrderVO, contractManagementAdapter::handleOrderStop);
    }

    @Override
    public Mono<HttpResponse<?>> handleProductOrderNegotiation(String organizationId, ProductOrderVO productOrderVO) {
        // nothing to do in negotiations
        return Mono.just(HttpResponse.noContent());
    }


    private Mono<HttpResponse<?>> handleOrderEvent(String organizationId, ProductOrderVO productOrderVO, BiFunction<ContractManagement, OrderEventVO, Mono<HttpResponse>> handler) {
        String orderId = productOrderVO.getId();
        return organizationResolver.getDID(organizationId)
                .switchIfEmpty(Mono.error(() -> new TMForumException(FailureReason.ORGANIZATION_DID_MISSING,
                        "No DID could be resolved for the customer organization %s.".formatted(organizationId))))
                .flatMap(did -> {
                    Mono<List<PolicyResolver.PolicyConfig>> policyConfigs = policyResolver.getAuthorizationPolicy(productOrderVO);
                    Mono<List<CredentialsConfigResolver.CredentialConfig>> credentialConfigs = credentialsConfigResolver.getCredentialsConfig(productOrderVO);
                    return Mono.zipDelayError(policyConfigs, credentialConfigs)
                            .map(resultTuple -> toOrderMap(productOrderVO, did, resultTuple))
                            .flatMap(orderMap -> {
                                List<Mono<Boolean>> orderResponses = orderMap.entrySet()
                                        .stream()
                                        // only external configs should be handled
                                        .filter(orderMapEntry -> !orderMapEntry.getKey().isLocal())
                                        .map(orderMapEntry -> forward(orderId, orderMapEntry.getKey(), orderMapEntry.getValue(), handler))
                                        .toList();
                                if (orderResponses.isEmpty()) {
                                    log.debug("Order {} has no configuration managed by a remote contract management.", orderId);
                                    return Mono.just(HttpResponseFactory.INSTANCE.status(HttpStatus.NO_CONTENT));
                                }
                                return Mono.zipDelayError(orderResponses, results -> Arrays.stream(results)
                                        .map(Boolean.class::cast)
                                        .filter(isSuccess -> !isSuccess)
                                        .map(s -> HttpResponseFactory.INSTANCE.status(HttpStatus.BAD_GATEWAY))
                                        .findAny()
                                        .orElse(HttpResponseFactory.INSTANCE.status(HttpStatus.NO_CONTENT))
                                );
                            });
                });
    }

    private Mono<Boolean> forward(String orderId, ContractManagement contractManagement, OrderEventVO orderEventVO,
                                  BiFunction<ContractManagement, OrderEventVO, Mono<HttpResponse>> handler) {
        int policies = Optional.ofNullable(orderEventVO.getPolicies()).map(List::size).orElse(0);
        int credentials = Optional.ofNullable(orderEventVO.getCredentialsConfig()).map(List::size).orElse(0);
        return Mono.defer(() -> handler.apply(contractManagement, orderEventVO))
                .onErrorMap(e -> new TMForumException(FailureReason.REMOTE_CM_REJECTED,
                        "The contract management at %s did not accept order %s.".formatted(contractManagement.getAddress(), orderId), e))
                .map(response -> {
                    int code = response.getStatus().getCode();
                    if (code < 200 || code > 299) {
                        log.warn("Order {}: the contract management at {} answered with status {}: {}", orderId,
                                contractManagement.getAddress(), code, response.getBody(String.class).orElse("<empty>"));
                        return false;
                    }
                    log.info("Order {}: forwarded {} policies and {} credentials to the contract management at {}.",
                            orderId, policies, credentials, contractManagement.getAddress());
                    return true;
                });
    }

    private Map<ContractManagement, OrderEventVO> toOrderMap(ProductOrderVO productOrderVO, String did, Tuple2<List<PolicyResolver.PolicyConfig>, List<CredentialsConfigResolver.CredentialConfig>> resultTuple) {
        Map<ContractManagement, OrderEventVO> orderMap = new HashMap<>();
        resultTuple.getT1()
                .forEach(policyConfig -> {
                    List<OdrlPolicyJsonVO> odrlPolicies = policyConfig.policies().stream()
                            .map(cmMapper::map)
                            .toList();
                    if (orderMap.containsKey(policyConfig.contractManagement())) {
                        OrderEventVO orderVO = orderMap.get(policyConfig.contractManagement());
                        List<OdrlPolicyJsonVO> allPolicies = new ArrayList<>(orderVO.getPolicies());
                        allPolicies.addAll(odrlPolicies);
                        orderVO.setPolicies(allPolicies);
                        orderMap.put(policyConfig.contractManagement(), orderVO);
                    } else {
                        orderMap.put(policyConfig.contractManagement(), new OrderEventVO()
                                .orderId(productOrderVO.getId())
                                .customerId(did)
                                .policies(odrlPolicies));
                    }
                });
        resultTuple.getT2()
                .forEach(cc -> {
                    List<CredentialVO> credentials = cc.credentialsVOS().stream()
                            .map(cmMapper::map)
                            .toList();
                    if (orderMap.containsKey(cc.contractManagement())) {
                        OrderEventVO orderVO = orderMap.get(cc.contractManagement());
                        List<CredentialVO> allCredentials = Optional.ofNullable(orderVO.getCredentialsConfig()).map(ArrayList::new).orElse(new ArrayList<>());
                        allCredentials.addAll(credentials);
                        orderVO.setCredentialsConfig(allCredentials);
                        orderMap.put(cc.contractManagement(), orderVO);
                    } else {
                        orderMap.put(cc.contractManagement(), new OrderEventVO()
                                .orderId(productOrderVO.getId())
                                .customerId(did)
                                .credentialsConfig(credentials));
                    }
                });
        return orderMap;
    }

}
