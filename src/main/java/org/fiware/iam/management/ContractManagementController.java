package org.fiware.iam.management;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseFactory;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.pap.PAPAdapter;
import org.fiware.iam.cm.api.OrderApi;
import org.fiware.iam.cm.model.OrderEventVO;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.domain.ContractManagement;
import org.fiware.iam.handlers.OrderAction;
import org.fiware.iam.http.HttpResponses;
import org.fiware.iam.logging.DownstreamError;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.tmforum.CredentialsConfigResolver;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Requires(condition = GeneralProperties.CentralMarketplaceCondition.class)
@Slf4j
@Controller("${general.basepath:/}")
@RequiredArgsConstructor
public class ContractManagementController implements OrderApi {

    private final TrustedIssuersListAdapter trustedIssuersListAdapter;
    private final PAPAdapter papAdapter;
    private final CMMapper cmMapper;

    @Override
    public Mono<HttpResponse<Object>> handleOrderStart(OrderEventVO orderVO) {
        log.info("Order {}: received start from a remote contract management for customer {}.",
                orderVO.getOrderId(), orderVO.getCustomerId());

        List<Mono<Boolean>> creationResults = Optional.ofNullable(orderVO.getPolicies()).orElse(List.of())
                .stream()
                .map(policy -> papAdapter.createPolicy(orderVO.getCustomerId(), orderVO.getOrderId(), policy.getAdditionalProperties()))
                .toList();
        List<CredentialsVO> credentialsVOS = Optional.ofNullable(orderVO.getCredentialsConfig()).orElse(List.of())
                .stream().map(cmMapper::map).toList();
        // the originating order scopes the grant here too, so this participant can revoke exactly it
        Mono<Boolean> tilResult = trustedIssuersListAdapter
                .allowIssuer(orderVO.getCustomerId(), orderVO.getOrderId(),
                        List.of(new CredentialsConfigResolver.CredentialConfig(new ContractManagement(true),
                                credentialsVOS)));

        List<Mono<Boolean>> successList = new ArrayList<>(creationResults);
        successList.add(tilResult);

        return toResponse(orderVO.getOrderId(), OrderAction.START, successList);
    }

    @Override
    public Mono<HttpResponse<Object>> handleOrderStop(OrderEventVO orderStopEventVO) {
        String orderId = orderStopEventVO.getOrderId();
        String issuerId = orderStopEventVO.getCustomerId();
        log.info("Order {}: received stop from a remote contract management for customer {}.", orderId, issuerId);
        List<Mono<Boolean>> policyDeleteResults = Optional.ofNullable(orderStopEventVO.getPolicies()).orElse(List.of())
                .stream()
                .map(odrlPolicyJsonVO -> papAdapter.deletePolicy(orderId, odrlPolicyJsonVO.getAdditionalProperties()))
                .toList();
        // what has to be revoked is what was granted, which the trusted-issuers-list records under
        // the order's id - the credentials in the event are not needed for it
        Mono<Boolean> issuerDenyResult = trustedIssuersListAdapter.denyIssuer(issuerId, orderId)
                .map(HttpResponses::isSuccess);
        List<Mono<Boolean>> successList = new ArrayList<>(policyDeleteResults);
        successList.add(issuerDenyResult);

        return toResponse(orderId, OrderAction.STOP, successList);
    }

    private Mono<HttpResponse<Object>> toResponse(String orderId, OrderAction action, List<Mono<Boolean>> successList) {
        // delay errors, so that one failing call does not cancel the others and leave an unlogged partial state
        return Mono.<HttpResponse<Object>>zipDelayError(successList, results -> {
                    long failed = Arrays.stream(results)
                            .filter(Boolean.class::isInstance)
                            .map(Boolean.class::cast)
                            .filter(isSuccessfull -> !isSuccessfull)
                            .count();
                    if (failed > 0) {
                        log.warn("Order {}: {} failed, {} of {} calls to the pap and trusted-issuers-list did not succeed.",
                                orderId, action, failed, results.length);
                        return HttpResponseFactory.INSTANCE.<Object>status(HttpStatus.BAD_GATEWAY);
                    }
                    log.info("Order {}: {} succeeded.", orderId, action);
                    return HttpResponseFactory.INSTANCE.<Object>status(HttpStatus.OK);
                })
                .doOnError(e -> log.warn("Order {}: {} failed: {}", orderId, action, DownstreamError.describe(e)));
    }
}
