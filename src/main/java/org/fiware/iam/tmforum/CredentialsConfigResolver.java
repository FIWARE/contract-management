package org.fiware.iam.tmforum;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.domain.ContractManagement;
import org.fiware.iam.exception.TMForumException;
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
 * Resolution distinguishes two cases that used to look the same:
 * <ul>
 *     <li><b>Nothing is configured.</b> An order without items, an offering that bundles others
 *     instead of referencing a specification, a specification without a
 *     {@code credentialsConfiguration} characteristic - all of these legitimately configure no
 *     credential and contribute an empty configuration. They must not fail the resolution, because
 *     the result is consumed inside a TMForum notification handler: an aborted resolution answers
 *     the hub with an error, the hub redelivers the notification, and every other handler of the
 *     same order runs again.</li>
 *     <li><b>A referenced configuration cannot be resolved.</b> An offering, specification, quote or
 *     provider that is referenced but cannot be read is a broken catalog, not an empty
 *     configuration. It is logged and raised as a {@link TMForumException} rather than silently
 *     ignored - activating an order while parts of its configuration could not be read would grant
 *     access nobody can account for.</li>
 * </ul>
 */
@Requires(condition = GeneralProperties.TmForumCondition.class)
@Singleton
@Slf4j
@RequiredArgsConstructor
public class CredentialsConfigResolver {

    private static final String CREDENTIALS_CONFIG_KEY = "credentialsConfiguration";
    private static final String QUOTE_DELETE_ACTION = "delete";
    private static final String OFFERING_NOT_RESOLVABLE = "The referenced product offering %s could not be resolved.";
    private static final String SPECIFICATION_NOT_RESOLVABLE = "The product specification %s referenced by offering %s could not be resolved.";
    private static final String PROVIDER_NOT_RESOLVABLE = "The contract-management of provider %s referenced by product specification %s could not be resolved.";
    private static final String QUOTE_NOT_RESOLVABLE = "The quote %s referenced by the order could not be resolved.";
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
     * @throws TMForumException if a referenced offering, specification, quote or provider cannot be resolved
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

    private Mono<CredentialConfig> getCredentialsConfigFromOffer(String offerId) {
        return productOfferingApiClient
                .retrieveProductOffering(offerId, null)
                .flatMap(response -> getCredentialsConfigFromSpecificationOf(response.body(), offerId))
                .switchIfEmpty(Mono.error(() -> unresolvableReference(OFFERING_NOT_RESOLVABLE.formatted(offerId))));
    }

    private Mono<CredentialConfig> getCredentialsConfigFromSpecificationOf(ProductOfferingVO productOffering,
            String offerId) {
        if (productOffering == null) {
            return Mono.error(unresolvableReference(OFFERING_NOT_RESOLVABLE.formatted(offerId)));
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
                .flatMap(response -> toCredentialConfig(response.body(), specificationId, offerId))
                .switchIfEmpty(Mono.error(() -> unresolvableReference(
                        SPECIFICATION_NOT_RESOLVABLE.formatted(specificationId, offerId))));
    }

    private Mono<CredentialConfig> toCredentialConfig(ProductSpecificationVO productSpecification,
            String specificationId, String offerId) {
        if (productSpecification == null) {
            return Mono.error(unresolvableReference(
                    SPECIFICATION_NOT_RESOLVABLE.formatted(specificationId, offerId)));
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
                        // a referenced provider that cannot be resolved is a broken reference, not an empty config
                        .switchIfEmpty(Mono.error(() -> unresolvableReference(
                                PROVIDER_NOT_RESOLVABLE.formatted(id, productSpecification.getId())))))
                .orElseGet(() -> Mono.just(new CredentialConfig(new ContractManagement(true), credentialsVOS)));
    }

    private Mono<List<CredentialConfig>> getCredentialsConfigFromQuote(List<QuoteRefVO> quoteRefVOS) {
        return zipToFlatList(quoteRefVOS.stream()
                .filter(Objects::nonNull)
                .map(QuoteRefVO::getId)
                .filter(Objects::nonNull)
                .map(quoteId -> quoteApiClient.retrieveQuote(quoteId, null)
                        .flatMap(response -> getCredentialsConfigFrom(response.body(), quoteId))
                        .switchIfEmpty(Mono.error(() -> unresolvableReference(
                                QUOTE_NOT_RESOLVABLE.formatted(quoteId)))))
                .toList());
    }

    private Mono<List<CredentialConfig>> getCredentialsConfigFrom(QuoteVO quote, String quoteId) {
        if (quote == null) {
            return Mono.error(unresolvableReference(QUOTE_NOT_RESOLVABLE.formatted(quoteId)));
        }
        if (quote.getState() != QuoteStateTypeVO.ACCEPTED) {
            // a quote that is not accepted (anymore) configures nothing
            log.debug("The quote {} is in state {}, no credentials config will be resolved.", quoteId,
                    quote.getState());
            return Mono.just(List.of());
        }
        return getCredentialsConfigFromQuoteItems(quote.getQuoteItem());
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
        return getCredentialsConfigFrom(CharacteristicValues.ofProductSpecification(pscList));
    }

    /**
     * Read the credential configuration from already normalized characteristics.
     * <p>
     * Only the first matching characteristic is read, which is the behaviour every writer in the data
     * space currently relies on.
     *
     * @param characteristics the characteristics of one or more specifications
     * @return the configured credentials, empty if none is configured
     */
    private List<CredentialsVO> getCredentialsConfigFrom(
            List<CharacteristicValues.Characteristic> characteristics) {
        return CharacteristicValues.byValueType(characteristics, CREDENTIALS_CONFIG_KEY)
                .map(characteristic -> CharacteristicValues.flatten(objectMapper, characteristic, CREDENTIALS_TYPE))
                .orElseGet(List::of);
    }

    private static CredentialConfig emptyConfig() {
        return new CredentialConfig(new ContractManagement(true), List.of());
    }

    /**
     * Log and build the exception for a configuration that is referenced but cannot be read.
     * <p>
     * Only ever called on the failing path, so it is safe to log here - but it must be invoked
     * lazily (via {@link Mono#error(java.util.function.Supplier)}), since the arguments of
     * {@code switchIfEmpty} are evaluated when the pipeline is assembled, not when it fails.
     *
     * @param message what could not be resolved
     * @return the exception to raise
     */
    private static TMForumException unresolvableReference(String message) {
        log.error(message);
        return new TMForumException(message);
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
