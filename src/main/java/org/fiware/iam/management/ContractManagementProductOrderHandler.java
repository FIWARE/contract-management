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
        return organizationResolver.getDID(organizationId)
                .flatMap(did -> {
                    Mono<List<PolicyResolver.PolicyConfig>> policyConfigs = policyResolver.getAuthorizationPolicy(productOrderVO);
                    Mono<List<CredentialsConfigResolver.CredentialConfig>> credentialConfigs = credentialsConfigResolver.getCredentialsConfig(productOrderVO);
                    return Mono.zipDelayError(policyConfigs, credentialConfigs)
                            .map(resultTuple -> toOrderMap(productOrderVO, did, resultTuple))
                            .flatMap(orderMap -> {
                                List<Mono<HttpResponse>> orderResponses = orderMap.entrySet()
                                        .stream()
                                        // only external configs should be handled
                                        .filter(orderMapEntry -> !orderMapEntry.getKey().isLocal())
                                        .map(orderMapEntry -> handler.apply(orderMapEntry.getKey(), orderMapEntry.getValue()))
                                        .toList();
                                if (orderResponses.isEmpty()) {
                                    return Mono.just(HttpResponseFactory.INSTANCE.status(HttpStatus.NO_CONTENT));
                                }
                                return Mono.zip(orderResponses, responses -> Arrays.stream(responses)
                                        .filter(HttpResponse.class::isInstance)
                                        .map(HttpResponse.class::cast)
                                        .map(HttpResponse::getStatus)
                                        .map(HttpStatus::getCode)
                                        .map(res -> res >= 200 && res < 300)
                                        .filter(isSuccess -> !isSuccess)
                                        .map(s -> HttpResponseFactory.INSTANCE.status(HttpStatus.BAD_GATEWAY))
                                        .findAny()
                                        .orElse(HttpResponseFactory.INSTANCE.status(HttpStatus.NO_CONTENT))
                                );
                            });
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
