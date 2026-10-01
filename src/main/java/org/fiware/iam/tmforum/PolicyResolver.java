package org.fiware.iam.tmforum;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.domain.ContractManagement;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.productcatalog.api.ProductOfferingApiClient;
import org.fiware.iam.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationRefVO;
import org.fiware.iam.tmforum.productcatalog.model.*;
import org.fiware.iam.tmforum.productorder.model.ProductOfferingRefVO;
import org.fiware.iam.tmforum.productorder.model.*;
import org.fiware.iam.tmforum.quote.api.QuoteApiClient;
import org.fiware.iam.tmforum.quote.model.QuoteItemVO;
import org.fiware.iam.tmforum.quote.model.QuoteStateTypeVO;
import org.fiware.iam.tmforum.quote.model.QuoteVO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Extract policies from ProductOrders, either from the connected Quote or ProductSpec.
 * <p>
 * Resolution distinguishes two cases that used to look the same:
 * <ul>
 *     <li><b>Nothing is configured.</b> An order without items, an offering that bundles others
 *     instead of referencing a specification, a specification without an
 *     {@code authorizationPolicy} characteristic - all of these legitimately configure no policy and
 *     contribute an empty configuration. They must not fail the resolution, because the result is
 *     consumed inside a TMForum notification handler: an aborted resolution answers the hub with an
 *     error, the hub redelivers the notification, and every other handler of the same order runs
 *     again.</li>
 *     <li><b>A referenced configuration cannot be resolved.</b> An offering, specification or
 *     provider that is referenced but cannot be read is a broken catalog, not an empty
 *     configuration. It is logged and raised as a {@link TMForumException} rather than silently
 *     ignored - activating an order while parts of its configuration could not be read would grant
 *     access nobody can account for.</li>
 * </ul>
 * <p>
 * When the ordered specification is composed of {@code ServiceSpecification}s, the policies of every
 * part are <b>unioned</b>: the effective configuration of a product is the union over the product
 * and its parts, de-duplicated by {@code odrl:uid}, and no part narrows or replaces another. Two
 * different policies claiming the same {@code odrl:uid} are a hard error, because the ODRL-PAP keys
 * an installed policy by that uid plus the order id and would otherwise silently keep one of the
 * two.
 */
@Requires(condition = GeneralProperties.TmForumCondition.class)
@Singleton
@Slf4j
@RequiredArgsConstructor
public class PolicyResolver {

    private static final String AUTHORIZATION_POLICY_KEY = "authorizationPolicy";
    private static final String QUOTE_DELETE_ACTION = "delete";
    private static final String OFFERING_NOT_RESOLVABLE = "The referenced product offering %s could not be resolved.";
    private static final String SPECIFICATION_NOT_RESOLVABLE = "The product specification %s referenced by offering %s could not be resolved.";
    private static final String PROVIDER_NOT_RESOLVABLE = "The contract-management of provider %s referenced by product specification %s could not be resolved.";
    private static final String QUOTE_NOT_RESOLVABLE = "The quote %s referenced by the order could not be resolved.";
    private static final String CONFLICTING_POLICIES = "The composition of specification %s contains two different policies claiming the uid %s. Refusing to install either of them.";
    private static final String CONFLICTING_PROVIDERS = "The composition of specification %s declares more than one provider: %s. Composition across providers is not supported.";
    private static final String ODRL_UID_KEY = "odrl:uid";
    private static final TypeReference<Map<String, Object>> POLICY_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    private final ProductOfferingApiClient productOfferingApiClient;
    private final ProductSpecificationApiClient productSpecificationApiClient;
    private final QuoteApiClient quoteApiClient;
    private final OrganizationResolver organizationResolver;
    private final SpecificationGraphResolver specificationGraphResolver;

    /**
     * Resolve the authorization policies configured for the given order.
     * <p>
     * The policies are taken from the accepted quote when the order references one, and from the
     * ordered offerings otherwise.
     *
     * @param productOrder the completed (or stopped) order
     * @return one configuration per resolved offering, empty list if the order configures nothing
     * @throws TMForumException if a referenced offering, specification or provider cannot be resolved
     */
    public Mono<List<PolicyConfig>> getAuthorizationPolicy(ProductOrderVO productOrder) {
        if (productOrder.getQuote() != null && !productOrder.getQuote().isEmpty()) {
            return getAuthorizationPolicyFromQuote(productOrder.getQuote());
        }
        log.debug("Order {} references no quote, the policies are taken from the ordered offerings.", productOrder.getId());
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
     * empty list instead. Every element mono is guaranteed to either emit exactly one value or fail.
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
                .onErrorMap(HttpClientResponseException.class, e -> unresolvableReference(FailureReason.OFFERING_NOT_RESOLVABLE,
                        OFFERING_NOT_RESOLVABLE.formatted(offerId), e))
                .flatMap(response -> getAuthorizationPolicyFromSpecificationOf(response.body(), offerId))
                .switchIfEmpty(Mono.error(() -> unresolvableReference(FailureReason.OFFERING_NOT_RESOLVABLE,
                        OFFERING_NOT_RESOLVABLE.formatted(offerId), null)));
    }

    private Mono<PolicyConfig> getAuthorizationPolicyFromSpecificationOf(ProductOfferingVO productOffering,
            String offerId) {
        if (productOffering == null) {
            return Mono.error(unresolvableReference(FailureReason.OFFERING_NOT_RESOLVABLE, OFFERING_NOT_RESOLVABLE.formatted(offerId), null));
        }
        String specificationId = Optional.ofNullable(productOffering.getProductSpecification())
                .map(ProductSpecificationRefVO::getId)
                .orElse(null);
        if (specificationId == null) {
            // bundled offerings do not reference a specification of their own - nothing to configure here
            log.debug("The offering {} does not reference a product specification, no policy will be resolved.",
                    productOffering.getId());
            return Mono.just(emptyConfig());
        }
        return productSpecificationApiClient.retrieveProductSpecification(specificationId, null)
                .onErrorMap(HttpClientResponseException.class, e -> unresolvableReference(FailureReason.SPECIFICATION_NOT_RESOLVABLE,
                        SPECIFICATION_NOT_RESOLVABLE.formatted(specificationId, offerId), e))
                .flatMap(response -> toPolicyConfig(response.body(), specificationId, offerId))
                .switchIfEmpty(Mono.error(() -> unresolvableReference(FailureReason.SPECIFICATION_NOT_RESOLVABLE,
                        SPECIFICATION_NOT_RESOLVABLE.formatted(specificationId, offerId), null)));
    }

    private Mono<PolicyConfig> toPolicyConfig(ProductSpecificationVO productSpecification, String specificationId,
            String offerId) {
        if (productSpecification == null) {
            return Mono.error(unresolvableReference(FailureReason.SPECIFICATION_NOT_RESOLVABLE,
                    SPECIFICATION_NOT_RESOLVABLE.formatted(specificationId, offerId), null));
        }
        return specificationGraphResolver.resolve(productSpecification)
                .flatMap(graph -> toPolicyConfig(graph, productSpecification.getId()));
    }

    private Mono<PolicyConfig> toPolicyConfig(SpecificationGraphResolver.SpecificationGraph graph,
            String specificationId) {
        List<Map<String, Object>> policies = aggregatePolicies(graph, specificationId);
        log.debug("Specification {} configures the policies {}.", specificationId,
                policies.stream().map(policy -> policy.getOrDefault(ODRL_UID_KEY, "<no uid>")).toList());
        return governingProvider(graph, specificationId)
                .map(id -> organizationResolver.getContractManagement(id)
                        .map(cm -> new PolicyConfig(cm, policies))
                        // a referenced provider that cannot be resolved is a broken reference, not an empty config
                        .switchIfEmpty(Mono.error(() -> unresolvableReference(FailureReason.PROVIDER_NOT_RESOLVABLE,
                                PROVIDER_NOT_RESOLVABLE.formatted(id, specificationId), null))))
                .orElseGet(() -> Mono.just(new PolicyConfig(new ContractManagement(true), policies)));
    }

    /**
     * Union the policies of every specification in the composition.
     * <p>
     * The first matching characteristic is read <i>per specification</i>, so a composed product
     * contributes one policy configuration per part rather than only the first one found. Identical
     * policies are de-duplicated silently - a service specification shared by several parts of the
     * same product is normal.
     *
     * @param graph           the resolved composition
     * @param specificationId the ordered specification, for the error message
     * @return the effective policies of the product
     * @throws TMForumException if two different policies claim the same {@code odrl:uid}
     */
    private List<Map<String, Object>> aggregatePolicies(SpecificationGraphResolver.SpecificationGraph graph,
            String specificationId) {
        List<Map<String, Object>> policies = graph.nodes()
                .stream()
                .map(SpecificationGraphResolver.SpecificationNode::characteristics)
                .map(this::getAuthorizationPolicyFrom)
                .flatMap(List::stream)
                .toList();

        Map<Object, Map<String, Object>> byUid = new LinkedHashMap<>();
        policies.forEach(policy -> {
            // a policy without a uid cannot be keyed by one - it is then only de-duplicated against
            // an identical copy of itself, and the ODRL-PAP rejects it later on anyway
            Object uid = policy.getOrDefault(ODRL_UID_KEY, policy);
            Map<String, Object> known = byUid.putIfAbsent(uid, policy);
            if (known != null && !known.equals(policy)) {
                throw new TMForumException(FailureReason.CONFLICTING_POLICIES, CONFLICTING_POLICIES.formatted(specificationId, uid));
            }
        });
        return List.copyOf(byUid.values());
    }

    /**
     * The single provider responsible for the whole composition.
     * <p>
     * One order activates at exactly one contract-management, so a composition that declares more
     * than one provider is refused: splitting an activation across two contract-managements has no
     * rollback story - one side would grant and the other would not. A part that declares no provider
     * inherits the one of the composition, which is the shape BAE produces (it replaces
     * {@code relatedParty} with commercial roles only).
     *
     * @param graph           the resolved composition
     * @param specificationId the ordered specification, for the error message
     * @return the responsible provider, or empty if the composition declares none
     * @throws TMForumException if the composition declares more than one provider
     */
    private Optional<String> governingProvider(SpecificationGraphResolver.SpecificationGraph graph,
            String specificationId) {
        List<String> providers = graph.nodes()
                .stream()
                .map(SpecificationGraphResolver.SpecificationNode::relatedParties)
                .flatMap(List::stream)
                .filter(party -> organizationResolver.hasProviderRole(party.role()))
                .map(SpecificationGraphResolver.PartyReference::id)
                .distinct()
                .toList();
        if (providers.size() > 1) {
            throw new TMForumException(FailureReason.CONFLICTING_PROVIDERS, CONFLICTING_PROVIDERS.formatted(specificationId, providers));
        }
        return providers.stream().findFirst();
    }

    private Mono<List<PolicyConfig>> getAuthorizationPolicyFromQuote(List<QuoteRefVO> quoteRefVOS) {
        return zipToFlatList(quoteRefVOS.stream()
                .filter(Objects::nonNull)
                .map(QuoteRefVO::getId)
                .filter(Objects::nonNull)
                .map(quoteId -> quoteApiClient.retrieveQuote(quoteId, null)
                        .onErrorMap(HttpClientResponseException.class, e -> unresolvableReference(FailureReason.QUOTE_NOT_RESOLVABLE,
                                QUOTE_NOT_RESOLVABLE.formatted(quoteId), e))
                        .flatMap(response -> getAuthorizationPolicyFrom(response.body(), quoteId))
                        .switchIfEmpty(Mono.error(() -> unresolvableReference(FailureReason.QUOTE_NOT_RESOLVABLE,
                                QUOTE_NOT_RESOLVABLE.formatted(quoteId), null))))
                .toList());
    }

    private Mono<List<PolicyConfig>> getAuthorizationPolicyFrom(QuoteVO quote, String quoteId) {
        if (quote == null) {
            return Mono.error(unresolvableReference(FailureReason.QUOTE_NOT_RESOLVABLE, QUOTE_NOT_RESOLVABLE.formatted(quoteId), null));
        }
        if (quote.getState() != QuoteStateTypeVO.ACCEPTED) {
            // a quote that is not accepted (anymore) configures nothing
            log.debug("The quote {} is in state {}, no policy will be resolved.", quoteId, quote.getState());
            return Mono.just(List.of());
        }
        return getAuthorizationPolicyFromQuoteItems(quote.getQuoteItem());
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
        return getAuthorizationPolicyFrom(CharacteristicValues.ofProductSpecification(pscList));
    }

    /**
     * Read the authorization policies from already normalized characteristics.
     * <p>
     * Only the first matching characteristic is read, which is the behaviour every writer in the data
     * space currently relies on.
     *
     * @param characteristics the characteristics of one or more specifications
     * @return the configured policies, empty if none is configured
     */
    private List<Map<String, Object>> getAuthorizationPolicyFrom(
            List<CharacteristicValues.Characteristic> characteristics) {
        return CharacteristicValues.byValueType(characteristics, AUTHORIZATION_POLICY_KEY)
                .map(characteristic -> CharacteristicValues.flatten(objectMapper, characteristic, POLICY_TYPE))
                .orElseGet(List::of);
    }

    private static PolicyConfig emptyConfig() {
        return new PolicyConfig(new ContractManagement(true), List.of());
    }

    /**
     * Build the exception for a configuration that is referenced but cannot be read. It is not logged
     * here: the order handler logs it once, together with the order it belongs to.
     *
     * @param reason  the failure reason
     * @param message what could not be resolved
     * @param cause   the failed call, if any
     * @return the exception to raise
     */
    private static TMForumException unresolvableReference(FailureReason reason, String message, @Nullable Throwable cause) {
        return new TMForumException(reason, message, cause);
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
