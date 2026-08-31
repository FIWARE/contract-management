package org.fiware.iam.tmforum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.dsp.OfferingParameters;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.agreement.api.AgreementApiClient;
import org.fiware.iam.tmforum.agreement.model.*;
import org.fiware.iam.tmforum.productcatalog.api.ProductOfferingApiClient;
import org.fiware.iam.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.iam.tmforum.productcatalog.model.*;
import org.fiware.iam.tmforum.productorder.api.ProductOrderApiClient;
import org.fiware.iam.tmforum.productorder.model.AgreementRefVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderUpdateVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import org.fiware.iam.tmforum.quote.api.QuoteApiClient;
import org.fiware.iam.tmforum.quote.model.QuoteUpdateVO;
import org.fiware.iam.tmforum.quote.model.QuoteVO;
import reactor.core.publisher.Mono;

import org.fiware.iam.configuration.ConsentProperties;
import org.fiware.iam.tmforum.productcatalog.model.ProductOfferingVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationRefVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationCharacteristicVO;
import org.fiware.iam.tmforum.productcatalog.model.CharacteristicValueSpecificationVO;
import org.fiware.iam.tmforum.agreement.model.AgreementUpdateTmfVO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/**
 * Adapter to handle communication with TMForum APIs.
 */
@Requires(condition = GeneralProperties.TmForumCondition.class)
@Singleton
@RequiredArgsConstructor
@Slf4j
public class TMForumAdapter {

    public static final String DATA_SPACE_PROTOCOL_AGREEMENT_ID = "Data-Space-Protocol-Agreement-Id";

    /**
     * Agreement characteristics the consent-facade reads to project a contract. The names are a wire
     * contract with that component - it looks them up verbatim.
     */
    public static final String PROVIDER_ID = "provider-id";
    public static final String CONSUMER_ID = "consumer-id";
    public static final String SIGNING_DATE = "signing-date";
    public static final String POLICY = "policy";

    /**
     * {@code valueType} of the product-specification characteristic carrying the ODRL policies. The
     * same declaration the authorization policies are read from, so a provider declares it once.
     */
    private static final String AUTHORIZATION_POLICY_VALUE_TYPE = "authorizationPolicy";

    /** Agreement status the consent-facade maps to a terminated contract. */
    private static final String AGREEMENT_STATUS_CANCELLED = "cancelled";
    public static final String CONSUMER_ROLE = "Consumer";

    private final ObjectMapper objectMapper;

    private final OrganizationResolver organizationResolver;
    private final ProductOrderApiClient productOrderApiClient;
    private final ProductOfferingApiClient productOfferingApiClient;
    private final ProductSpecificationApiClient productSpecificationApiClient;
    private final AgreementApiClient agreementApiClient;
    private final QuoteApiClient quoteApiClient;
    private final ConsentProperties consentProperties;

    /**
     * Create a TMForum Agreement for the given product order, unless the order already has one.
     *
     * <p>The order's {@code agreement} refs are the record of that: they carry the id of the
     * agreement itself, and {@link #addAgreementToOrder(String, List)} writes them immediately after
     * creation. An order that already references an agreement has been handled, so the referenced id
     * is returned and nothing is written.
     *
     * <p>This is needed because the same completion can be delivered more than once - TMForum
     * notifications are retried - and a second agreement for the same order is not harmless
     * downstream: the consent integration projects a privacy notice per provider/consumer agreement
     * and cannot tell which of two duplicates governs an access.
     *
     * <p>The existing id is returned rather than an empty result because the caller patches the
     * order's ref list with whatever comes back, and that patch REPLACES the list: dropping the id
     * would erase the ref, leaving nothing to detect on the next delivery.
     *
     * <p>The order is re-read rather than taken from the notification payload: the payload is by
     * definition stale for a duplicate delivery, which is exactly the case being guarded.
     */
    public Mono<String> createAgreement(String productOrderId, String productOfferingId, String agreementId, List<RelatedPartyTmfVO> relatedParties, String customerOrganizationId) {
        return findExistingAgreement(productOrderId)
                .switchIfEmpty(Mono.defer(() -> doCreateAgreement(productOrderId, productOfferingId, agreementId, relatedParties, customerOrganizationId)));
    }

    /**
     * Returns the id of an agreement the order already references, or empty when it references none.
     *
     * <p>Failing to read the order is not evidence that no agreement exists, but refusing to create
     * would strand the order entirely - so it resolves to empty and creation proceeds.
     */
    private Mono<String> findExistingAgreement(String productOrderId) {
        return productOrderApiClient.retrieveProductOrder(productOrderId, null)
                .map(HttpResponse::body)
                .flatMapIterable(productOrder -> Optional.ofNullable(productOrder.getAgreement()).orElse(List.of()))
                .map(AgreementRefVO::getId)
                .filter(Objects::nonNull)
                .next()
                .doOnNext(existingId -> log.info("Order {} already references agreement {}; not creating another.",
                        productOrderId, existingId))
                .onErrorResume(t -> {
                    log.warn("Could not read order {} to check for an existing agreement.", productOrderId, t);
                    return Mono.empty();
                });
    }

    private Mono<String> doCreateAgreement(String productOrderId, String productOfferingId, String agreementId, List<RelatedPartyTmfVO> relatedParties, String customerOrganizationId) {
        AgreementItemTmfVO agreementItemTmfVO = new AgreementItemTmfVO()
                .addProductItem(
                        new ProductRefTmfVO()
                                .id(productOrderId))
                .addProductOfferingItem(
                        new ProductOfferingRefTmfVO()
                                .id(productOfferingId));
        return consentEnrichment(productOfferingId, customerOrganizationId)
                .flatMap(consentCharacteristics -> {
                    List<CharacteristicTmfVO> characteristics = new ArrayList<>();
                    // an order concluded without a DSP negotiation has no such id; an empty
                    // characteristic would read as "negotiated, id unknown"
                    if (agreementId != null) {
                        characteristics.add(new CharacteristicTmfVO()
                                .name(DATA_SPACE_PROTOCOL_AGREEMENT_ID)
                                .value(agreementId));
                    }
                    characteristics.addAll(consentCharacteristics);
                    AgreementCreateTmfVO agreementCreateTmfVO = new AgreementCreateTmfVO()
                            .characteristic(characteristics)
                            .engagedParty(relatedParties)
                            // prevent empty refs
                            .agreementSpecification(null)
                            .addAgreementItemItem(agreementItemTmfVO);
                    return createAgreement(agreementCreateTmfVO);
                });
    }

    /**
     * The characteristics the consent integration needs on the agreement.
     *
     * <p>When consent enrichment is off (or unconfigured) this resolves to nothing, so the agreement
     * is exactly what it was before.
     *
     * <p>The parties are carried as characteristics rather than as engaged-party roles: the TM Forum
     * API does not persist {@code role} on an agreement's engaged parties (a stored engagedParty has
     * only id/href/name), so a role written here would silently vanish and the consent-facade's
     * role-based fallback could never resolve a participant.
     */
    private Mono<List<CharacteristicTmfVO>> consentEnrichment(String productOfferingId, String customerOrganizationId) {
        if (!consentProperties.isEnabled()) {
            return Mono.just(List.of());
        }
        if (consentProperties.getSelfDescriptionBaseUrl() == null || consentProperties.getSelfDescriptionBaseUrl().isBlank()) {
            log.warn("Consent enrichment is enabled but consent.self-description-base-url is unset; agreements are written unenriched.");
            return Mono.just(List.of());
        }
        if (customerOrganizationId == null || customerOrganizationId.isBlank()) {
            log.warn("No customer organization for the order; agreement is written unenriched.");
            return Mono.just(List.of());
        }
        return resolveSpecification(productOfferingId)
                .map(specification -> buildCharacteristics(specification, customerOrganizationId))
                // an offering whose specification cannot be read must still yield an agreement:
                // failing here would block the order over a consent concern.
                .onErrorResume(t -> {
                    log.warn("Could not resolve the specification of offering {}; agreement is written unenriched.",
                            productOfferingId, t);
                    return Mono.just(List.<CharacteristicTmfVO>of());
                })
                .defaultIfEmpty(List.of());
    }

    /** Resolves offering -> product specification, the way the authorization policies are resolved. */
    private Mono<ProductSpecificationVO> resolveSpecification(String productOfferingId) {
        return productOfferingApiClient.retrieveProductOffering(productOfferingId, null)
                .map(HttpResponse::body)
                .map(ProductOfferingVO::getProductSpecification)
                .map(ProductSpecificationRefVO::getId)
                .flatMap(specificationId -> productSpecificationApiClient.retrieveProductSpecification(specificationId, null))
                .map(HttpResponse::body);
    }

    private List<CharacteristicTmfVO> buildCharacteristics(ProductSpecificationVO specification, String customerOrganizationId) {
        String providerOrganizationId = providerOrganizationId(specification);
        if (providerOrganizationId == null) {
            log.warn("Specification {} names no party in the provider role; agreement is written unenriched.",
                    specification.getId());
            return List.of();
        }

        List<CharacteristicTmfVO> characteristics = new ArrayList<>();
        characteristics.add(new CharacteristicTmfVO()
                .name(PROVIDER_ID)
                .value(selfDescriptionUrl(providerOrganizationId)));
        characteristics.add(new CharacteristicTmfVO()
                .name(CONSUMER_ID)
                .value(selfDescriptionUrl(customerOrganizationId)));
        // presence is what marks the contract concluded for the consent-facade; the value is the
        // conclusion time in epoch seconds
        characteristics.add(new CharacteristicTmfVO()
                .name(SIGNING_DATE)
                .value(Instant.now().getEpochSecond()));
        authorizationPolicy(specification).ifPresent(policy -> characteristics.add(new CharacteristicTmfVO()
                .name(POLICY)
                .value(policy)));
        return characteristics;
    }

    private String providerOrganizationId(ProductSpecificationVO specification) {
        return Optional.ofNullable(specification.getRelatedParty()).orElse(List.of())
                .stream()
                .filter(party -> organizationResolver.hasProviderRole(party.getRole()))
                .map(org.fiware.iam.tmforum.productcatalog.model.RelatedPartyVO::getId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * The ODRL policy the consent is scoped by, taken from the specification's
     * {@code authorizationPolicy} characteristic - the same declaration the access policies are
     * derived from, so a provider declares the policy once.
     */
    private Optional<Object> authorizationPolicy(ProductSpecificationVO specification) {
        return Optional.ofNullable(specification.getProductSpecCharacteristic()).orElse(List.of())
                .stream()
                .filter(characteristic -> AUTHORIZATION_POLICY_VALUE_TYPE.equals(characteristic.getValueType()))
                .map(ProductSpecificationCharacteristicVO::getProductSpecCharacteristicValue)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(CharacteristicValueSpecificationVO::getValue)
                .filter(Objects::nonNull)
                .findFirst();
    }

    private String selfDescriptionUrl(String organizationId) {
        String base = consentProperties.getSelfDescriptionBaseUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/participants/" + organizationId;
    }

    private Mono<String> createAgreement(AgreementCreateTmfVO agreementCreateTmfVO) {
        return agreementApiClient
                .createAgreement(agreementCreateTmfVO)
                .map(HttpResponse::body)
                .map(AgreementTmfVO::getId)
                .onErrorMap(t -> {
                    log.warn("Was not able to create aggreement", t);
                    throw new TMForumException("Was not able to create agreement", t);
                });
    }


    /**
     * Add the id of agreements(from rainbow) to the given product order
     */
    public Mono<ProductOrderVO> addAgreementToOrder(String productOrderId, List<String> agreementIds) {
        // The patch REPLACES the order's agreement list, so the ids already on the order are read
        // and kept: dropping them would lose an agreement written by anyone else and would erase the
        // very evidence createAgreement uses to avoid creating a duplicate.
        return existingAgreementIds(productOrderId)
                .flatMap(existingIds -> {
                    List<String> mergedIds = new ArrayList<>(existingIds);
                    agreementIds.stream()
                            .filter(Objects::nonNull)
                            .filter(id -> !mergedIds.contains(id))
                            .forEach(mergedIds::add);
                    return patchAgreementRefs(productOrderId, mergedIds);
                });
    }

    /**
     * Marks an agreement as no longer in force, so the consent integration stops treating it as a
     * concluded contract.
     *
     * <p>Two things have to change: the {@code signing-date} characteristic is dropped (its mere
     * presence makes the consent-facade report the contract as <em>signed</em>, overriding any
     * status) and the status is set to {@code cancelled} (which the facade maps to
     * <em>terminated</em>). Without this a stopped order leaves a signed contract behind, and the
     * consent granted against it would keep authorising access.
     *
     * <p>Only active when consent enrichment is enabled: for a deployment without it, changing the
     * agreement on stop would be new behaviour nobody asked for.
     */
    public Mono<Boolean> terminateAgreement(String agreementId) {
        if (!consentProperties.isEnabled()) {
            return Mono.just(true);
        }
        return agreementApiClient.retrieveAgreement(agreementId, null)
                .map(HttpResponse::body)
                .flatMap(agreement -> {
                    List<CharacteristicTmfVO> remaining = Optional.ofNullable(agreement.getCharacteristic())
                            .orElse(List.of())
                            .stream()
                            .filter(characteristic -> !SIGNING_DATE.equals(characteristic.getName()))
                            .toList();
                    AgreementUpdateTmfVO update = new AgreementUpdateTmfVO()
                            .characteristic(remaining)
                            .status(AGREEMENT_STATUS_CANCELLED);
                    return agreementApiClient.patchAgreement(agreementId, update);
                })
                .map(response -> true)
                .onErrorResume(t -> {
                    log.warn("Was not able to terminate agreement {}.", agreementId, t);
                    return Mono.just(false);
                })
                .defaultIfEmpty(false);
    }

    /** The agreement ids the order already references, or an empty list when it cannot be read. */
    private Mono<List<String>> existingAgreementIds(String productOrderId) {
        return productOrderApiClient.retrieveProductOrder(productOrderId, null)
                .map(HttpResponse::body)
                .map(productOrder -> Optional.ofNullable(productOrder.getAgreement()).orElse(List.<AgreementRefVO>of())
                        .stream()
                        .map(AgreementRefVO::getId)
                        .filter(Objects::nonNull)
                        .toList())
                .onErrorResume(t -> {
                    log.warn("Could not read the agreements of order {}; writing only the new ones.", productOrderId, t);
                    return Mono.just(List.of());
                })
                .defaultIfEmpty(List.of());
    }

    private Mono<ProductOrderVO> patchAgreementRefs(String productOrderId, List<String> agreementIds) {
        List<AgreementRefVO> agreementRefVOS = agreementIds.stream()
                .map(id -> new AgreementRefVO().id(id))
                .toList();
        ProductOrderUpdateVO productOrderUpdateVO = new ProductOrderUpdateVO().agreement(agreementRefVOS);
        return productOrderApiClient
                .patchProductOrder(productOrderId, productOrderUpdateVO)
                .map(HttpResponse::body)
                .onErrorMap(t -> {
                    log.warn("Was not able to update the product order {}", productOrderId, t);
                    throw new TMForumException("Was not able to update the product order");
                });
    }

    /**
     * Update the externalId of a quote.
     */
    public Mono<QuoteVO> updateExternalId(QuoteVO quoteVO, String externalId) {
        QuoteUpdateVO quoteUpdateVO = objectMapper.convertValue(quoteVO.externalId(externalId), QuoteUpdateVO.class);
        // remove the quote object
        quoteUpdateVO.setUnknownProperties("id", null);
        quoteUpdateVO.setUnknownProperties("href", null);
        quoteUpdateVO.setUnknownProperties("quoteDate", null);

        return quoteApiClient.patchQuote(quoteVO.getId(), quoteUpdateVO)
                .onErrorMap(t -> {
                    log.warn("Was not able to update the quote", t);
                    throw new TMForumException(String.format("Was not able to update the quote %s.", quoteVO.getId()), t);
                })
                .map(HttpResponse::body);
    }

    /**
     * Return the quote with the given id.
     */
    public Mono<QuoteVO> getQuoteById(String id) {
        return quoteApiClient.retrieveQuote(id, null)
                .onErrorMap(t -> {
                    throw new TMForumException(String.format("Was not able to get the quote %s.", id), t);
                })
                .map(HttpResponse::body);
    }

    public Mono<ProductSpecificationVO> getSpecFromOfferRef(String refId) {
        return productOfferingApiClient.retrieveProductOffering(refId, null)
                .onErrorMap(t -> new TMForumException(String.format("Was not able to retrieve offering %s", refId), t))
                .map(HttpResponse::body)
                .map(ProductOfferingVO::getProductSpecification)
                .map(ProductSpecificationRefVO::getId)
                .flatMap(id -> productSpecificationApiClient.retrieveProductSpecification(id, null))
                .onErrorMap(t -> new TMForumException(String.format("Was not able to retrieve specification for offering %s", refId), t))
                .map(HttpResponse::body);
    }


    public Mono<String> getConsumerDid(QuoteVO quoteVO) {
        return organizationResolver.getDID(getConsumerIdFromQuote(quoteVO));
    }


    public String getConsumerIdFromQuote(QuoteVO quoteVO) {
        if (quoteVO.getRelatedParty() == null || quoteVO.getRelatedParty().isEmpty()) {
            throw new TMForumException(String.format("Quote %s does not have valid consumer.", quoteVO.getId()));
        }
        return quoteVO.getRelatedParty()
                .stream()
                .filter(rp -> rp.getRole().equals(CONSUMER_ROLE))
                .findFirst()
                .map(org.fiware.iam.tmforum.quote.model.RelatedPartyVO::getId)
                .orElseThrow(() -> new TMForumException(String.format("Quote %s does not have valid consumer.", quoteVO.getId())));
    }

    public Mono<OfferingParameters> getOfferingParameters(String offeringId) {
        return getSpecFromOfferRef(offeringId)
                .map(spec -> fromProductSpecChars(spec.getProductSpecCharacteristic()));
    }

    private OfferingParameters fromProductSpecChars(List<ProductSpecificationCharacteristicVO> specChars) {
        String target = "";
        String action = "";

        for (ProductSpecificationCharacteristicVO specChar : specChars) {
            if (specChar.getValueType().equals(ProductOfferingConstants.ENDPOINT_URL_TYPE)) {
                target = specChar.getProductSpecCharacteristicValue()
                        .stream()
                        .filter(CharacteristicValueSpecificationVO::getIsDefault)
                        .map(CharacteristicValueSpecificationVO::getValue)
                        .map(String.class::cast)
                        .findAny()
                        .orElseThrow(() -> new TMForumException("Was not able to retrieve endpoint from spec."));
                continue;
            }
            if (specChar.getValueType().equals(ProductOfferingConstants.ALLOWED_ACTION_TYPE)) {
                action = specChar.getProductSpecCharacteristicValue()
                        .stream()
                        .filter(CharacteristicValueSpecificationVO::getIsDefault)
                        .map(CharacteristicValueSpecificationVO::getValue)
                        .map(String.class::cast)
                        .findAny()
                        .orElseThrow(() -> new TMForumException("Was not able to retrieve action from spec."));
            }
        }
        if (action.isEmpty() || target.isEmpty()) {
            throw new TMForumException(String.format("Was not able to get valid action %s and/or target %s.", action, target));
        }
        return new OfferingParameters(target, action);
    }


}
