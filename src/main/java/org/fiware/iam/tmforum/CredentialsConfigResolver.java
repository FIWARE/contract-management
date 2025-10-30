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
import org.fiware.iam.til.model.CredentialsVO;
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
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static org.fiware.iam.tmforum.OrganizationResolver.PROVIDER_ROLE;

@Requires(condition = GeneralProperties.TmForumCondition.class)
@Singleton
@Slf4j
@RequiredArgsConstructor
public class CredentialsConfigResolver {

    private static final String CREDENTIALS_CONFIG_KEY = "credentialsConfiguration";
    private static final String QUOTE_DELETE_ACTION = "delete";

    private final ObjectMapper objectMapper;
    private final OrganizationResolver organizationResolver;

    private final ProductOfferingApiClient productOfferingApiClient;
    private final ProductSpecificationApiClient productSpecificationApiClient;
    private final QuoteApiClient quoteApiClient;

    public Mono<List<CredentialConfig>> getCredentialsConfig(ProductOrderVO productOrder) {
        if (productOrder.getQuote() != null && !productOrder.getQuote().isEmpty()) {
            return getCredentialsConfigFromQuote(productOrder.getQuote());
        }
        log.debug("No quote found, take the original offer from the order item.");
        List<Mono<CredentialConfig>> credentialsVOMonoList = productOrder.getProductOrderItem()
                .stream()
                .filter(poi -> poi.getAction() == OrderItemActionTypeVO.ADD || poi.getAction() == OrderItemActionTypeVO.MODIFY)
                .map(ProductOrderItemVO::getProductOffering)
                .map(ProductOfferingRefVO::getId)
                .map(this::getCredentialsConfigFromOffer)
                .toList();

        return zipMonoListCC(credentialsVOMonoList);
    }

    private Mono<List<CredentialConfig>> zipMonoListCC(List<Mono<CredentialConfig>> monoList) {
        return Mono.zip(monoList, results -> Stream.of(results).map(r -> (CredentialConfig) r).toList());

    }

    private Mono<List<CredentialConfig>> zipMonoList(List<Mono<List<CredentialConfig>>> monoList) {
        return Mono.zip(monoList, results -> Stream.of(results).map(r -> (List<CredentialConfig>) r).flatMap(List::stream).toList());

    }

    private Mono<CredentialConfig> getCredentialsConfigFromOffer(String offerId) {

        return productOfferingApiClient
                .retrieveProductOffering(offerId, null)
                .map(HttpResponse::body)
                .map(ProductOfferingVO::getProductSpecification)
                .map(ProductSpecificationRefVO::getId)
                .flatMap(specId -> productSpecificationApiClient.retrieveProductSpecification(specId, null))
                .map(HttpResponse::body)
                .flatMap(psvo -> {
                    List<CredentialsVO> credentialsVOS = getCredentialsConfigFromPSC(psvo.getProductSpecCharacteristic());

                    Optional<String> partyId = Optional.ofNullable(psvo.getRelatedParty())
                            .orElse(List.of())
                            .stream()
                            .filter(relatedPartyVO -> relatedPartyVO.getRole().equals(PROVIDER_ROLE))
                            .map(RelatedPartyVO::getId)
                            .findAny();
                    return partyId.map(string ->
                                    organizationResolver.getContractManagement(string)
                                            .map(cm -> new CredentialConfig(cm, credentialsVOS)))
                            .orElseGet(() -> Mono.just(new CredentialConfig(new ContractManagement(true), credentialsVOS)));
                });
    }


    private Mono<List<CredentialConfig>> getCredentialsConfigFromQuote(List<QuoteRefVO> quoteRefVOS) {
        return zipMonoList(quoteRefVOS.stream()
                .map(QuoteRefVO::getId)
                .map(quoteId -> quoteApiClient.retrieveQuote(quoteId, null)
                        .map(HttpResponse::body)
                        .filter(quoteVO -> quoteVO.getState() == QuoteStateTypeVO.ACCEPTED)
                        .map(QuoteVO::getQuoteItem)
                        .flatMap(quoteItemList -> {
                            List<Mono<CredentialConfig>> configMonos = quoteItemList.stream()
                                    .filter(item -> item.getState().equals(QuoteStateTypeVO.ACCEPTED.getValue()))
                                    .filter(item -> !item.getAction().equals(QUOTE_DELETE_ACTION))
                                    .map(QuoteItemVO::getProductOffering)
                                    .map(org.fiware.iam.tmforum.quote.model.ProductOfferingRefVO::getId)
                                    .map(this::getCredentialsConfigFromOffer)
                                    .toList();
                            return zipMonoListCC(configMonos);
                        }))
                .toList());
    }

    private List<CredentialsVO> getCredentialsConfigFromPSC(List<ProductSpecificationCharacteristicVO> pscList) {
        return pscList.stream()
                .filter(psc -> psc.getValueType().equals(CREDENTIALS_CONFIG_KEY))
                .findFirst()
                .map(productSpecificationCharacteristicVO -> productSpecificationCharacteristicVO
                        .getProductSpecCharacteristicValue()
                        .stream()
                        .map(CharacteristicValueSpecificationVO::getValue)
                        .map(value -> {
                            try {
                                List<CredentialsVO> credentialsVOS = objectMapper.convertValue(value, new TypeReference<List<CredentialsVO>>() {
                                });
                                log.debug("Config is {}", credentialsVOS);
                                return credentialsVOS;
                            } catch (IllegalArgumentException iae) {
                                log.warn("The characteristic value is invalid.", iae);
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .flatMap(List::stream)
                        .toList()).orElseGet(List::of);
    }

    public record CredentialConfig(ContractManagement contractManagement, List<CredentialsVO> credentialsVOS) {
    }
}
