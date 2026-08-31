package org.fiware.iam.tmforum.handlers;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.handlers.ProductOrderHandler;
import org.fiware.iam.tmforum.TMFMapper;
import org.fiware.iam.tmforum.TMForumAdapter;
import org.fiware.iam.tmforum.agreement.model.RelatedPartyTmfVO;
import org.fiware.iam.tmforum.productorder.model.AgreementRefVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderItemVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Creates the TM Forum agreement for a completed product order, and ends it when the order stops.
 *
 * <p>This is the agreement a marketplace purchase produces: a consumer orders an offering, the order
 * completes, and the contract that governs the resulting access is written to the Agreement API. The
 * consent integration projects its privacy notice from exactly that agreement.
 *
 * <p>It used to live in the Rainbow handler, where the agreement was a by-product of a DSP
 * negotiation. Rainbow is deprecated, and because that handler is conditional, switching Rainbow off
 * silently removed the only creator of the agreement - an order would complete, every other handler
 * would succeed, and no agreement would exist. Agreement creation is a TM Forum concern, so it lives
 * here and no longer depends on a DSP negotiation happening.
 *
 * <p>Active whenever the TM Forum integration is: it is the only component creating this agreement.
 */
@Requires(condition = GeneralProperties.TmForumCondition.class)
@RequiredArgsConstructor
@Singleton
@Slf4j
public class AgreementProductOrderHandler implements ProductOrderHandler {

    private final TMForumAdapter tmForumAdapter;
    private final TMFMapper tmfMapper;

    /**
     * Creates one agreement per ordered offering and links them to the order.
     *
     * <p>{@link TMForumAdapter#createAgreement} is create-once: an order that already references an
     * agreement is left alone, so a retried notification does not produce a second contract for the
     * same purchase.
     */
    @Override
    public Mono<HttpResponse<?>> handleProductOrderComplete(String organizationId, ProductOrderVO productOrderVO) {
        List<RelatedPartyTmfVO> relatedParties = Optional.ofNullable(productOrderVO.getRelatedParty())
                .orElse(List.of())
                .stream()
                .map(tmfMapper::map)
                .peek(party -> party.unknownProperties(null))
                .toList();

        List<String> offeringIds = Optional.ofNullable(productOrderVO.getProductOrderItem())
                .orElse(List.of())
                .stream()
                .map(ProductOrderItemVO::getProductOffering)
                .filter(Objects::nonNull)
                .map(offering -> offering.getId())
                .filter(Objects::nonNull)
                .toList();

        if (offeringIds.isEmpty()) {
            log.warn("Order {} references no product offering; no agreement is created.", productOrderVO.getId());
            return Mono.just(HttpResponse.noContent());
        }

        return Mono.zipDelayError(
                        offeringIds.stream()
                                // no DSP agreement id: this contract is concluded by the order itself
                                .map(offeringId -> tmForumAdapter.createAgreement(
                                        productOrderVO.getId(), offeringId, null, relatedParties, organizationId))
                                .toList(),
                        created -> java.util.Arrays.stream(created)
                                .filter(String.class::isInstance)
                                .map(String.class::cast)
                                .toList())
                .flatMap(agreementIds -> tmForumAdapter.addAgreementToOrder(productOrderVO.getId(), agreementIds))
                .<HttpResponse<?>>map(order -> HttpResponse.noContent())
                .onErrorResume(t -> {
                    log.warn("Was not able to create the agreement for order {}.", productOrderVO.getId(), t);
                    return Mono.just(HttpResponse.serverError());
                });
    }

    /**
     * Ends the agreements of a stopped order.
     *
     * <p>Without this a stopped order leaves a contract that still reads as concluded, and a consent
     * granted against it would keep authorising access. Terminating is a no-op unless consent
     * enrichment is enabled - see {@link TMForumAdapter#terminateAgreement(String)}.
     */
    @Override
    public Mono<HttpResponse<?>> handleProductOrderStop(String organizationId, ProductOrderVO productOrderVO) {
        List<String> agreementIds = Optional.ofNullable(productOrderVO.getAgreement())
                .orElse(List.of())
                .stream()
                .map(AgreementRefVO::getId)
                .filter(Objects::nonNull)
                .toList();
        if (agreementIds.isEmpty()) {
            return Mono.just(HttpResponse.noContent());
        }
        return Mono.zipDelayError(
                        agreementIds.stream().map(tmForumAdapter::terminateAgreement).toList(),
                        terminations -> (HttpResponse<?>) HttpResponse.noContent());
    }

    @Override
    public Mono<HttpResponse<?>> handleProductOrderNegotiation(String organizationId, ProductOrderVO productOrderVO) {
        // an agreement is written when the order completes, not while it is negotiated
        return Mono.just(HttpResponse.noContent());
    }
}
