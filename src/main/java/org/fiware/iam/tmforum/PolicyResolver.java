package org.fiware.iam.tmforum;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.domain.ContractManagement;
import org.fiware.iam.tmforum.productcatalog.api.ProductOfferingApiClient;
import org.fiware.iam.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationRefVO;
import org.fiware.iam.tmforum.productcatalog.model.RelatedPartyVO;
import org.fiware.iam.tmforum.productcatalog.model.*;
import org.fiware.iam.tmforum.productorder.model.ProductOfferingRefVO;
import org.fiware.iam.tmforum.productorder.model.*;
import org.fiware.iam.tmforum.quote.api.QuoteApiClient;
import org.fiware.iam.tmforum.quote.model.QuoteItemVO;
import org.fiware.iam.tmforum.quote.model.QuoteStateTypeVO;
import org.fiware.iam.tmforum.quote.model.QuoteVO;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static org.fiware.iam.tmforum.OrganizationResolver.PROVIDER_ROLE;

/**
 * Extract policies from ProductOrders, either from the connected Quote or ProductSpec.
 */
@Requires(condition = GeneralProperties.TmForumCondition.class)
@Singleton
@Slf4j
@RequiredArgsConstructor
public class PolicyResolver {

    private static final String AUTHORIZATION_POLICY_KEY = "authorizationPolicy";
    private static final String QUOTE_DELETE_ACTION = "delete";

    private final ObjectMapper objectMapper;

    private final ProductOfferingApiClient productOfferingApiClient;
    private final ProductSpecificationApiClient productSpecificationApiClient;
    private final QuoteApiClient quoteApiClient;
    private final OrganizationResolver organizationResolver;

    public Mono<List<PolicyConfig>> getAuthorizationPolicy(ProductOrderVO productOrder) {
        if (productOrder.getQuote() != null && !productOrder.getQuote().isEmpty()) {
            return getAuthorizationPolicyFromQuote(productOrder.getQuote());
        }
        log.debug("No quote found, take the original offer from the order item.");
        List<Mono<PolicyConfig>> credentialsVOMonoList = productOrder.getProductOrderItem()
                .stream()
                .filter(poi -> poi.getAction() == OrderItemActionTypeVO.ADD || poi.getAction() == OrderItemActionTypeVO.MODIFY)
                .map(ProductOrderItemVO::getProductOffering)
                .map(ProductOfferingRefVO::getId)
                .map(this::getAuthorizationPolicyFromOffer)
                .toList();

        return zipMonoListPC(credentialsVOMonoList);
    }

    private Mono<List<PolicyConfig>> zipMonoListPC(List<Mono<PolicyConfig>> monoList) {
        return Mono.zip(monoList, results -> Stream.of(results).map(r -> (PolicyConfig) r).toList());
    }

    private Mono<List<PolicyConfig>> zipMonoList(List<Mono<List<PolicyConfig>>> monoList) {
        return Mono.zip(monoList, results -> Stream.of(results).map(r -> (List<PolicyConfig>) r).flatMap(List::stream).toList());

    }

    private Mono<PolicyConfig> getAuthorizationPolicyFromOffer(String offerId) {
        return productOfferingApiClient
                .retrieveProductOffering(offerId, null)
                .map(HttpResponse::body)
                .map(ProductOfferingVO::getProductSpecification)
                .map(ProductSpecificationRefVO::getId)
                .flatMap(specId -> productSpecificationApiClient.retrieveProductSpecification(specId, null))
                .map(HttpResponse::body)
                .flatMap(psvo -> {
                    List<Map<String, Object>> policies = getAuthorizationPolicyFromPSC(psvo.getProductSpecCharacteristic());
                    Optional<String> partyId = Optional.ofNullable(psvo.getRelatedParty())
                            .orElse(List.of())
                            .stream()
                            .filter(relatedPartyVO -> relatedPartyVO.getRole().equals(PROVIDER_ROLE))
                            .map(RelatedPartyVO::getId)
                            .findAny();
                    return partyId.map(string -> organizationResolver.getContractManagement(string)
                                    .map(cm -> new PolicyConfig(cm, policies)))
                            .orElseGet(() -> Mono.just(new PolicyConfig(new ContractManagement(true), policies)));
                });
    }

    private Mono<List<PolicyConfig>> getAuthorizationPolicyFromQuote(List<QuoteRefVO> quoteRefVOS) {
        return zipMonoList(quoteRefVOS.stream()
                .map(QuoteRefVO::getId)
                .map(quoteId -> quoteApiClient.retrieveQuote(quoteId, null)
                        .map(HttpResponse::body)
                        .filter(quoteVO -> quoteVO.getState() == QuoteStateTypeVO.ACCEPTED)
                        .map(QuoteVO::getQuoteItem)
                        .flatMap(quoteItemList -> {
                            List<Mono<PolicyConfig>> configMonos = quoteItemList.stream()
                                    .filter(item -> item.getState().equals(QuoteStateTypeVO.ACCEPTED.getValue()))
                                    .filter(item -> !item.getAction().equals(QUOTE_DELETE_ACTION))
                                    .map(QuoteItemVO::getProductOffering)
                                    .map(org.fiware.iam.tmforum.quote.model.ProductOfferingRefVO::getId)
                                    .map(this::getAuthorizationPolicyFromOffer)
                                    .toList();
                            return zipMonoListPC(configMonos);
                        }))
                .toList());
    }

    private List<Map<String, Object>> getAuthorizationPolicyFromPSC(List<ProductSpecificationCharacteristicVO> pscList) {
        return pscList.stream()
                .filter(psc -> psc.getValueType().equals(AUTHORIZATION_POLICY_KEY))
                .findFirst()
                .map(productSpecificationCharacteristicVO -> productSpecificationCharacteristicVO
                        .getProductSpecCharacteristicValue()
                        .stream()
                        .map(CharacteristicValueSpecificationVO::getValue)
                        .map(value -> {
                            try {
                                List<Map<String, Object>> policies = objectMapper.convertValue(value, new TypeReference<List<Map<String, Object>>>() {
                                });
                                log.debug("Policy is {}", policies);
                                return policies;
                            } catch (IllegalArgumentException iae) {
                                log.warn("The characteristic value is invalid.", iae);
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .flatMap(List::stream)
                        .toList()).orElseGet(List::of);
    }

    public record PolicyConfig(ContractManagement contractManagement, List<Map<String, Object>> policies) {
    }
}
