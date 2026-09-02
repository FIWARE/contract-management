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

/**
 * Extract the credential configuration from ProductOrders, either from the connected Quote or
 * ProductSpec.
 * <p>
 * Resolution is <i>tolerant</i>: an order item that carries no interpretable credential
 * configuration contributes an empty configuration instead of failing the resolution. This matters
 * because the result is consumed inside a TMForum notification handler - an aborted resolution
 * answers the hub with an error, the hub redelivers the notification, and every other handler of the
 * same order runs again.
 */
@Requires(condition = GeneralProperties.TmForumCondition.class)
@Singleton
@Slf4j
@RequiredArgsConstructor
public class CredentialsConfigResolver {

    private static final String CREDENTIALS_CONFIG_KEY = "credentialsConfiguration";
    private static final String QUOTE_DELETE_ACTION = "delete";
    private static final TypeReference<CredentialsVO> CREDENTIALS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final OrganizationResolver organizationResolver;

    private final ProductOfferingApiClient productOfferingApiClient;
    private final ProductSpecificationApiClient productSpecificationApiClient;
    private final QuoteApiClient quoteApiClient;

    /**
     * Resolve the credential configurations for the given order.
     * <p>
     * The configuration is taken from the accepted quote when the order references one, and from the
     * ordered offerings otherwise.
     *
     * @param productOrder the completed (or stopped) order
     * @return one configuration per resolved offering, empty list if the order configures nothing
     */
    public Mono<List<CredentialConfig>> getCredentialsConfig(ProductOrderVO productOrder) {
        if (productOrder.getQuote() != null && !productOrder.getQuote().isEmpty()) {
            return getCredentialsConfigFromQuote(productOrder.getQuote());
        }
        log.debug("No quote found, take the original offer from the order item.");
        List<Mono<CredentialConfig>> credentialsVOMonoList = Optional
                .ofNullable(productOrder.getProductOrderItem())
                .orElseGet(List::of)
                .stream()
                .filter(Objects::nonNull)
                .filter(poi -> poi.getAction() == OrderItemActionTypeVO.ADD || poi.getAction() == OrderItemActionTypeVO.MODIFY)
                .map(ProductOrderItemVO::getProductOffering)
                .filter(Objects::nonNull)
                .map(ProductOfferingRefVO::getId)
                .filter(Objects::nonNull)
                .map(this::getCredentialsConfigFromOffer)
                .toList();

        return zipToList(credentialsVOMonoList);
    }

    /**
     * Combine the per-offering resolutions into one list.
     * <p>
     * {@link Mono#zip(Iterable, java.util.function.Function)} completes <i>empty</i> for an empty
     * iterable, which would silently drop the whole order, so the empty case is answered with an
     * empty list instead. Every element mono is guaranteed to emit exactly one value.
     */
    private static <T> Mono<List<T>> zipToList(List<Mono<T>> monoList) {
        if (monoList.isEmpty()) {
            return Mono.just(List.of());
        }
        return Mono.zip(monoList, results -> Stream.of(results).map(result -> (T) result).toList());
    }

    /**
     * Combine resolutions that each already yield a list, flattening the result.
     *
     * @see #zipToList(List)
     */
    private static <T> Mono<List<T>> zipToFlatList(List<Mono<List<T>>> monoList) {
        if (monoList.isEmpty()) {
            return Mono.just(List.of());
        }
        return Mono.zip(monoList, results -> Stream.of(results)
                .map(result -> (List<T>) result)
                .flatMap(List::stream)
                .toList());
    }

    private Mono<CredentialConfig> getCredentialsConfigFromOffer(String offerId) {
        return productOfferingApiClient
                .retrieveProductOffering(offerId, null)
                .map(HttpResponse::body)
                .flatMap(this::getCredentialsConfigFromSpecificationOf)
                .defaultIfEmpty(emptyConfig());
    }

    private Mono<CredentialConfig> getCredentialsConfigFromSpecificationOf(ProductOfferingVO productOffering) {
        if (productOffering == null) {
            log.warn("Received no product offering, no credentials config can be resolved.");
            return Mono.just(emptyConfig());
        }
        String specificationId = Optional.ofNullable(productOffering.getProductSpecification())
                .map(ProductSpecificationRefVO::getId)
                .orElse(null);
        if (specificationId == null) {
            // bundled offerings do not reference a specification of their own - nothing to configure here
            log.info("The offering {} does not reference a product specification, no credentials config will be resolved.",
                    productOffering.getId());
            return Mono.just(emptyConfig());
        }
        return productSpecificationApiClient.retrieveProductSpecification(specificationId, null)
                .map(HttpResponse::body)
                .flatMap(this::toCredentialConfig);
    }

    private Mono<CredentialConfig> toCredentialConfig(ProductSpecificationVO productSpecification) {
        if (productSpecification == null) {
            log.warn("Received no product specification, no credentials config can be resolved.");
            return Mono.just(emptyConfig());
        }
        List<CredentialsVO> credentialsVOS = getCredentialsConfigFromPSC(
                productSpecification.getProductSpecCharacteristic());
        Optional<String> partyId = Optional.ofNullable(productSpecification.getRelatedParty())
                .orElseGet(List::of)
                .stream()
                .filter(Objects::nonNull)
                .filter(relatedPartyVO -> organizationResolver.hasProviderRole(relatedPartyVO.getRole()))
                .map(RelatedPartyVO::getId)
                .filter(Objects::nonNull)
                .findAny();
        return partyId
                .map(id -> organizationResolver.getContractManagement(id)
                        .map(cm -> new CredentialConfig(cm, credentialsVOS))
                        // an unresolvable provider must not be treated as local - drop the configuration instead
                        .defaultIfEmpty(emptyConfig()))
                .orElseGet(() -> Mono.just(new CredentialConfig(new ContractManagement(true), credentialsVOS)));
    }

    private Mono<List<CredentialConfig>> getCredentialsConfigFromQuote(List<QuoteRefVO> quoteRefVOS) {
        return zipToFlatList(quoteRefVOS.stream()
                .filter(Objects::nonNull)
                .map(QuoteRefVO::getId)
                .filter(Objects::nonNull)
                .map(quoteId -> quoteApiClient.retrieveQuote(quoteId, null)
                        .map(HttpResponse::body)
                        .filter(Objects::nonNull)
                        .filter(quoteVO -> quoteVO.getState() == QuoteStateTypeVO.ACCEPTED)
                        .map(QuoteVO::getQuoteItem)
                        .flatMap(this::getCredentialsConfigFromQuoteItems)
                        // a quote that is not accepted (anymore) configures nothing
                        .defaultIfEmpty(List.<CredentialConfig>of()))
                .toList());
    }

    private Mono<List<CredentialConfig>> getCredentialsConfigFromQuoteItems(List<QuoteItemVO> quoteItems) {
        return zipToList(Optional.ofNullable(quoteItems)
                .orElseGet(List::of)
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> QuoteStateTypeVO.ACCEPTED.getValue().equals(item.getState()))
                .filter(item -> !QUOTE_DELETE_ACTION.equals(item.getAction()))
                .map(QuoteItemVO::getProductOffering)
                .filter(Objects::nonNull)
                .map(org.fiware.iam.tmforum.quote.model.ProductOfferingRefVO::getId)
                .filter(Objects::nonNull)
                .map(this::getCredentialsConfigFromOffer)
                .toList());
    }

    private List<CredentialsVO> getCredentialsConfigFromPSC(List<ProductSpecificationCharacteristicVO> pscList) {
        return CharacteristicValues.byValueType(pscList, CREDENTIALS_CONFIG_KEY)
                .map(characteristic -> CharacteristicValues.flatten(objectMapper, characteristic, CREDENTIALS_TYPE))
                .orElseGet(List::of);
    }

    private static CredentialConfig emptyConfig() {
        return new CredentialConfig(new ContractManagement(true), List.of());
    }

    /**
     * The credential configuration of one offering, together with the contract-management responsible
     * for granting it.
     *
     * @param contractManagement the responsible contract-management, local unless the provider declares one
     * @param credentialsVOS     the configured credentials, possibly empty
     */
    public record CredentialConfig(ContractManagement contractManagement, List<CredentialsVO> credentialsVOS) {
    }
}
