package org.fiware.iam.dsp;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.tmforum.TMFMapper;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.handlers.ProductOrderHandler;
import org.fiware.iam.exception.RainbowException;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.TMForumAdapter;
import org.fiware.iam.tmforum.agreement.model.RelatedPartyTmfVO;
import org.fiware.iam.tmforum.productorder.model.AgreementRefVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderItemVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import org.fiware.iam.tmforum.productorder.model.QuoteRefVO;
import org.fiware.iam.tmforum.quote.model.QuoteItemVO;
import org.fiware.iam.tmforum.quote.model.QuoteStateTypeVO;
import org.fiware.iam.tmforum.quote.model.QuoteVO;
import org.fiware.rainbow.model.AgreementVO;
import org.fiware.rainbow.model.ProviderNegotiationVO;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Function;

@Requires(condition = GeneralProperties.RainbowCondition.class)
@RequiredArgsConstructor
@Singleton
@Slf4j
public class RainbowProductOrderHandler implements ProductOrderHandler {

    private static final String STATE_VERIFIED = "dspace:VERIFIED";
    private static final String STATE_FINALIZED = "dspace:FINALIZED";

    private final TMForumAdapter tmForumAdapter;
    private final TMFMapper tmfMapper;
    private final RainbowAdapter rainbowAdapter;

    @Override
    public Mono<HttpResponse<?>> handleProductOrderComplete(String organizationId, ProductOrderVO productOrderVO) {

        List<RelatedPartyTmfVO> relatedPartyTmfVOS = productOrderVO
                .getRelatedParty()
                .stream()
                .map(tmfMapper::map)
                .peek(rr -> rr.unknownProperties(null))
                .toList();

        if (!containsQuote(productOrderVO)) {
            return Mono.zipDelayError(
                            productOrderVO
                                    .getProductOrderItem()
                                    .stream()
                                    .map(ProductOrderItemVO::getProductOffering)
                                    .filter(Objects::nonNull)
                                    .map(offering -> rainbowAdapter.createAgreement(organizationId, offering.getId()))
                                    .toList(),
                            res -> {
                                List<AgreementVO> agreementVOS = Arrays.stream(res).filter(Objects::nonNull).filter(AgreementVO.class::isInstance).map(AgreementVO.class::cast).toList();
                                return updateProductOrder(productOrderVO, agreementVOS, relatedPartyTmfVOS);
                            })
                    .flatMap(Function.identity())
                    .map(po -> (HttpResponse<?>) HttpResponse.noContent());
        } else {
            return tmForumAdapter.getQuoteById(getQuoteRef(productOrderVO).getId())
                    .flatMap(quoteVO -> {
                        String offerId = getOfferIdFromQuote(quoteVO);

                        Mono<?> agreementMono = rainbowAdapter.getNegotiationProcess(quoteVO.getExternalId())
                                .map(ProviderNegotiationVO::getCnProcessId)
                                .flatMap(rainbowAdapter::getAgreement)
                                .map(avo -> avo.dataServiceId(offerId))
                                .flatMap(agreementVO -> updateProductOrder(productOrderVO, List.of(agreementVO), relatedPartyTmfVOS));

                        Mono<?> negotiationMono = rainbowAdapter.getNegotiationProcessState(quoteVO.getExternalId())
                                .flatMap(state -> {
                                    if (state.equals(STATE_FINALIZED)) {
                                        // nothing to do here, but we want the chain to continue
                                        return Mono.just(Optional.empty());
                                    }
                                    if (!state.equals(STATE_VERIFIED)) {
                                        throw new RainbowException(String.format("Negotiation process %s is in state %s. Not allowed for order completion.", quoteVO.getExternalId(), state));
                                    }
                                    return rainbowAdapter.updateNegotiationProcessByProviderId(quoteVO.getExternalId(), "dspace:FINALIZED");
                                });
                        return Mono.zipDelayError(agreementMono, negotiationMono);
                    })
                    .onErrorMap(t -> {
                        log.warn("Was not able to update negotiation.", t);
                        throw new RainbowException("Was not able to update the negotiation.");
                    })
                    .map(t -> (HttpResponse<?>) HttpResponse.noContent());
        }
    }

    @Override
    public Mono<HttpResponse<?>> handleProductOrderStop(String organizationId, ProductOrderVO productOrderVO) {
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

    private String getOfferIdFromQuote(QuoteVO quoteVO) {
        return quoteVO
                .getQuoteItem()
                .stream()
                .filter(qi -> qi.getState().equals("accepted"))
                .map(QuoteItemVO::getProductOffering)
                .map(org.fiware.iam.tmforum.quote.model.ProductOfferingRefVO::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The event does not reference an offer."));
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

    private Mono<ProductOrderVO> updateProductOrder(ProductOrderVO productOrderVO, List<AgreementVO> agreementVOS, List<RelatedPartyTmfVO> relatedPartyTmfVOS) {
        return Mono.zipDelayError(
                agreementVOS.stream()
                        .map(agreementVO ->
                                tmForumAdapter.createAgreement(productOrderVO.getId(), agreementVO.getDataServiceId(), agreementVO.getAgreementId(), relatedPartyTmfVOS))
                        .toList(),
                agreements -> {
                    List<String> agreementIds = Arrays.stream(agreements)
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .toList();
                    return tmForumAdapter.addAgreementToOrder(productOrderVO.getId(), agreementIds);
                }).flatMap(Function.identity());

    }
}
