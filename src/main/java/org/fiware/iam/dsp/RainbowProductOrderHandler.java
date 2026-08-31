package org.fiware.iam.dsp;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.handlers.ProductOrderHandler;
import org.fiware.iam.exception.RainbowException;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.TMForumAdapter;
import org.fiware.iam.tmforum.productorder.model.AgreementRefVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderItemVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import org.fiware.iam.tmforum.productorder.model.QuoteRefVO;
import org.fiware.rainbow.model.AgreementVO;
import org.fiware.iam.tmforum.quote.model.QuoteStateTypeVO;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Keeps the Rainbow/DSP side of a product order in sync: it creates the DSP agreement for a direct
 * order and drives the contract negotiation to its final state for a quoted one.
 *
 * <p>Writing the TM Forum agreement is <em>not</em> part of this handler. It moved to
 * {@link org.fiware.iam.tmforum.handlers.AgreementProductOrderHandler}, because the TM Forum contract
 * is produced by the order itself and must not depend on Rainbow being enabled - Rainbow is
 * deprecated, and while it owned the creation, switching it off left completed orders with no
 * agreement at all.
 */
@Requires(condition = GeneralProperties.RainbowCondition.class)
@RequiredArgsConstructor
@Singleton
@Slf4j
public class RainbowProductOrderHandler implements ProductOrderHandler {

    private static final String STATE_VERIFIED = "dspace:VERIFIED";
    private static final String STATE_FINALIZED = "dspace:FINALIZED";

    private final TMForumAdapter tmForumAdapter;
    private final RainbowAdapter rainbowAdapter;

    @Override
    public Mono<HttpResponse<?>> handleProductOrderComplete(String organizationId, ProductOrderVO productOrderVO) {

        if (!containsQuote(productOrderVO)) {
            List<Mono<AgreementVO>> creations = Optional.ofNullable(productOrderVO.getProductOrderItem())
                    .orElse(List.of())
                    .stream()
                    .map(ProductOrderItemVO::getProductOffering)
                    .filter(Objects::nonNull)
                    .map(offering -> rainbowAdapter.createAgreement(organizationId, offering.getId()))
                    .toList();
            // zipping an empty list completes empty, which the listener renders as 404 - and the
            // TM Forum API answers that by redelivering the notification indefinitely
            if (creations.isEmpty()) {
                log.warn("Order {} references no product offering; no DSP agreement is created.", productOrderVO.getId());
                return Mono.just(HttpResponse.noContent());
            }
            return Mono.zipDelayError(creations, res -> (HttpResponse<?>) HttpResponse.noContent());
        } else {
            return tmForumAdapter.getQuoteById(getQuoteRef(productOrderVO).getId())
                    .flatMap(quoteVO -> rainbowAdapter.getNegotiationProcessState(quoteVO.getExternalId())
                            .flatMap(state -> {
                                if (state.equals(STATE_FINALIZED)) {
                                    // nothing to do here, but we want the chain to continue
                                    return Mono.just(Optional.empty());
                                }
                                if (!state.equals(STATE_VERIFIED)) {
                                    throw new RainbowException(String.format("Negotiation process %s is in state %s. Not allowed for order completion.", quoteVO.getExternalId(), state));
                                }
                                return rainbowAdapter.updateNegotiationProcessByProviderId(quoteVO.getExternalId(), STATE_FINALIZED);
                            }))
                    .onErrorMap(t -> {
                        log.warn("Was not able to update negotiation.", t);
                        throw new RainbowException("Was not able to update the negotiation.");
                    })
                    .map(t -> (HttpResponse<?>) HttpResponse.noContent());
        }
    }

    @Override
    public Mono<HttpResponse<?>> handleProductOrderStop(String organizationId, ProductOrderVO productOrderVO) {
        // only the DSP agreement is removed here; ending the TM Forum agreement is done by the
        // TM Forum handler.
        List<Mono<Boolean>> deletionMonos = productOrderVO.getAgreement()
                .stream()
                .map(AgreementRefVO::getId)
                .map(rainbowAdapter::deleteAgreement)
                .toList();
        return Mono.zipDelayError(deletionMonos, deletions -> {
            if (Set.of(deletions).contains(false)) {
                log.warn("Was not able to delete the agreement for order {}.", productOrderVO);
                HttpResponse.status(HttpStatus.BAD_GATEWAY);
            }
            return HttpResponse.status(HttpStatus.ACCEPTED);
        });
    }

    @Override
    public Mono<HttpResponse<?>> handleProductOrderNegotiation(String organizationId, ProductOrderVO productOrderVO) {
        return tmForumAdapter
                .getQuoteById(getQuoteRef(productOrderVO).getId())
                .flatMap(quoteVO -> {
                    if (quoteVO.getState() != QuoteStateTypeVO.ACCEPTED) {
                        throw new TMForumException(String.format("The quote is not in state accepted, cannot be used for product ordering. %s:%s.", quoteVO.getId(), quoteVO.getState()));
                    }
                    return rainbowAdapter.updateNegotiationProcessByProviderId(quoteVO.getExternalId(), STATE_VERIFIED);
                })
                .map(t -> HttpResponse.noContent());
    }

    private QuoteRefVO getQuoteRef(ProductOrderVO productOrderVO) {
        // integration with IDSA Contract Negotiation is only supported for productOrders with a single quote.
        if (productOrderVO.getQuote().size() != 1) {
            throw new RainbowException("IDSA Contract Negotiation does not support the inclusion of multiple processes into one product.");
        }
        return productOrderVO.getQuote().get(0);
    }

    private boolean containsQuote(ProductOrderVO productOrderVO) {
        return productOrderVO.getQuote() != null && !productOrderVO.getQuote().isEmpty();
    }
}
