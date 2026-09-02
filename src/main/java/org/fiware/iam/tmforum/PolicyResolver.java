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

/**
 * Extract policies from ProductOrders, either from the connected Quote or ProductSpec.
 * <p>
 * Resolution is <i>tolerant</i>: an order item that carries no interpretable policy configuration
 * contributes an empty configuration instead of failing the resolution. This matters because the
 * result is consumed inside a TMForum notification handler - an aborted resolution answers the hub
 * with an error, the hub redelivers the notification, and every other handler of the same order runs
 * again.
 */
@Requires(condition = GeneralProperties.TmForumCondition.class)
@Singleton
@Slf4j
@RequiredArgsConstructor
public class PolicyResolver {

    private static final String AUTHORIZATION_POLICY_KEY = "authorizationPolicy";
    private static final String QUOTE_DELETE_ACTION = "delete";
    private static final TypeReference<Map<String, Object>> POLICY_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    private final ProductOfferingApiClient productOfferingApiClient;
    private final ProductSpecificationApiClient productSpecificationApiClient;
    private final QuoteApiClient quoteApiClient;
    private final OrganizationResolver organizationResolver;

    /**
     * Resolve the authorization policies configured for the given order.
     * <p>
     * The policies are taken from the accepted quote when the order references one, and from the
     * ordered offerings otherwise.
     *
     * @param productOrder the completed (or stopped) order
     * @return one configuration per resolved offering, empty list if the order configures nothing
     */
    public Mono<List<PolicyConfig>> getAuthorizationPolicy(ProductOrderVO productOrder) {
        if (productOrder.getQuote() != null && !productOrder.getQuote().isEmpty()) {
            return getAuthorizationPolicyFromQuote(productOrder.getQuote());
        }
        log.debug("No quote found, take the original offer from the order item.");
        List<Mono<PolicyConfig>> policyConfigMonoList = Optional
                .ofNullable(productOrder.getProductOrderItem())
                .orElseGet(List::of)
                .stream()
                .filter(Objects::nonNull)
                .filter(poi -> poi.getAction() == OrderItemActionTypeVO.ADD || poi.getAction() == OrderItemActionTypeVO.MODIFY)
                .map(ProductOrderItemVO::getProductOffering)
                .filter(Objects::nonNull)
                .map(ProductOfferingRefVO::getId)
                .filter(Objects::nonNull)
                .map(this::getAuthorizationPolicyFromOffer)
                .toList();

        return zipToList(policyConfigMonoList);
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

    private Mono<PolicyConfig> getAuthorizationPolicyFromOffer(String offerId) {
        return productOfferingApiClient
                .retrieveProductOffering(offerId, null)
                .map(HttpResponse::body)
                .flatMap(this::getAuthorizationPolicyFromSpecificationOf)
                .defaultIfEmpty(emptyConfig());
    }

    private Mono<PolicyConfig> getAuthorizationPolicyFromSpecificationOf(ProductOfferingVO productOffering) {
        if (productOffering == null) {
            log.warn("Received no product offering, no policy can be resolved.");
            return Mono.just(emptyConfig());
        }
        String specificationId = Optional.ofNullable(productOffering.getProductSpecification())
                .map(ProductSpecificationRefVO::getId)
                .orElse(null);
        if (specificationId == null) {
            // bundled offerings do not reference a specification of their own - nothing to configure here
            log.info("The offering {} does not reference a product specification, no policy will be resolved.",
                    productOffering.getId());
            return Mono.just(emptyConfig());
        }
        return productSpecificationApiClient.retrieveProductSpecification(specificationId, null)
                .map(HttpResponse::body)
                .flatMap(this::toPolicyConfig);
    }

    private Mono<PolicyConfig> toPolicyConfig(ProductSpecificationVO productSpecification) {
        if (productSpecification == null) {
            log.warn("Received no product specification, no policy can be resolved.");
            return Mono.just(emptyConfig());
        }
        List<Map<String, Object>> policies = getAuthorizationPolicyFromPSC(
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
                        .map(cm -> new PolicyConfig(cm, policies))
                        // an unresolvable provider must not be treated as local - drop the policies instead
                        .defaultIfEmpty(emptyConfig()))
                .orElseGet(() -> Mono.just(new PolicyConfig(new ContractManagement(true), policies)));
    }

    private Mono<List<PolicyConfig>> getAuthorizationPolicyFromQuote(List<QuoteRefVO> quoteRefVOS) {
        return zipToFlatList(quoteRefVOS.stream()
                .filter(Objects::nonNull)
                .map(QuoteRefVO::getId)
                .filter(Objects::nonNull)
                .map(quoteId -> quoteApiClient.retrieveQuote(quoteId, null)
                        .map(HttpResponse::body)
                        .filter(Objects::nonNull)
                        .filter(quoteVO -> quoteVO.getState() == QuoteStateTypeVO.ACCEPTED)
                        .map(QuoteVO::getQuoteItem)
                        .flatMap(this::getAuthorizationPolicyFromQuoteItems)
                        // a quote that is not accepted (anymore) configures nothing
                        .defaultIfEmpty(List.<PolicyConfig>of()))
                .toList());
    }

    private Mono<List<PolicyConfig>> getAuthorizationPolicyFromQuoteItems(List<QuoteItemVO> quoteItems) {
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
                .map(this::getAuthorizationPolicyFromOffer)
                .toList());
    }

    private List<Map<String, Object>> getAuthorizationPolicyFromPSC(List<ProductSpecificationCharacteristicVO> pscList) {
        return CharacteristicValues.byValueType(pscList, AUTHORIZATION_POLICY_KEY)
                .map(characteristic -> CharacteristicValues.flatten(objectMapper, characteristic, POLICY_TYPE))
                .orElseGet(List::of);
    }

    private static PolicyConfig emptyConfig() {
        return new PolicyConfig(new ContractManagement(true), List.of());
    }

    /**
     * The authorization policies configured for one offering, together with the contract-management
     * responsible for enforcing them.
     *
     * @param contractManagement the responsible contract-management, local unless the provider declares one
     * @param policies           the configured ODRL policies, possibly empty
     */
    public record PolicyConfig(ContractManagement contractManagement, List<Map<String, Object>> policies) {
    }
}
