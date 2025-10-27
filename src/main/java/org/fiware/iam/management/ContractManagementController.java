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
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.tmforum.CredentialsConfigResolver;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

        List<Mono<Boolean>> creationResults = orderVO.getPolicies()
                .stream()
                .map(policy -> papAdapter.createPolicy(orderVO.getCustomerId(), orderVO.getOrderId(), policy.getAdditionalProperties()))
                .toList();
        List<CredentialsVO> credentialsVOS = orderVO.getCredentialsConfig().stream().map(cmMapper::map).toList();
        Mono<Boolean> tilResult = trustedIssuersListAdapter
                .allowIssuer(orderVO.getCustomerId(), List.of(new CredentialsConfigResolver.CredentialConfig(new ContractManagement(true), credentialsVOS)));

        List<Mono<Boolean>> successList = new ArrayList<>(creationResults);
        successList.add(tilResult);

        return toResponse(successList);
    }

    @Override
    public Mono<HttpResponse<Object>> handleOrderStop(OrderEventVO orderStopEventVO) {
        CredentialsConfigResolver.CredentialConfig credentialConfig = new CredentialsConfigResolver.CredentialConfig(
                new ContractManagement(true),
                orderStopEventVO.getCredentialsConfig()
                        .stream()
                        .map(cmMapper::map)
                        .toList());

        String orderId = orderStopEventVO.getOrderId();
        String issuerId = orderStopEventVO.getCustomerId();
        List<Mono<Boolean>> policyDeleteResults = orderStopEventVO.getPolicies()
                .stream()
                .map(odrlPolicyJsonVO -> papAdapter.deletePolicy(orderId, odrlPolicyJsonVO.getAdditionalProperties()))
                .toList();
        Mono<Boolean> issuerDenyResult = trustedIssuersListAdapter.denyIssuer(issuerId,
                        List.of(credentialConfig))
                .map(HttpResponse::getStatus)
                .map(HttpStatus::getCode)
                .map(code -> code > 199 && code < 300);
        List<Mono<Boolean>> successList = new ArrayList<>(policyDeleteResults);
        successList.add(issuerDenyResult);

        return toResponse(successList);
    }

    private Mono<HttpResponse<Object>> toResponse(List<Mono<Boolean>> successList) {
        return Mono.zip(successList, results ->
                Arrays.stream(results)
                        .filter(Boolean.class::isInstance)
                        .map(Boolean.class::cast)
                        .filter(isSuccessfull -> !isSuccessfull)
                        .findAny()
                        // if something is wrong -> bad gateway
                        .map(b -> HttpResponseFactory.INSTANCE.status(HttpStatus.BAD_GATEWAY))
                        .orElse(HttpResponseFactory.INSTANCE.status(HttpStatus.OK))
        );
    }
}
