package org.fiware.iam.dsp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.handlers.QuoteHandler;
import org.fiware.iam.exception.RainbowException;
import org.fiware.iam.tmforum.TMForumAdapter;
import org.fiware.iam.tmforum.quote.model.QuoteItemVO;
import org.fiware.iam.tmforum.quote.model.QuoteStateTypeVO;
import org.fiware.iam.tmforum.quote.model.QuoteVO;
import org.fiware.rainbow.model.NegotiationRequestVO;
import org.fiware.rainbow.model.ObligationVO;
import org.fiware.rainbow.model.OfferVO;
import org.fiware.rainbow.model.PermissionVO;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Stream;

import static org.fiware.iam.dsp.PriceMapper.PAYMENT_ACTION;
import static org.fiware.iam.tmforum.TMForumAdapter.CONSUMER_ROLE;

@Requires(condition = GeneralProperties.RainbowCondition.class)
@RequiredArgsConstructor
@Singleton
@Slf4j
public class RainbowQuoteHandler implements QuoteHandler {


    private static final String URN_UUID_TEMPLATE = "urn:uuid:%s";

    private final ObjectMapper objectMapper;
    private final TMForumAdapter tmForumAdapter;
    private final RainbowAdapter rainbowAdapter;
    private final PriceMapper priceMapper;
    private final GeneralProperties generalProperties;

    @Override
    public Mono<HttpResponse<?>> handleQuoteCreation(QuoteVO quoteVO) {
        List<PermissionVO> permissionVOS = new ArrayList<>(getPermissionsFromQuote(objectMapper, quoteVO));

        return tmForumAdapter.getConsumerDid(quoteVO)
                .flatMap(id -> rainbowAdapter.createParticipant(id, CONSUMER_ROLE))
                .onErrorMap(t -> new RainbowException("Was not able to create consumer.", t))
                .flatMap(r -> getObligationFromQuote(quoteVO))
                .flatMap(obligation -> {
                    String offerId = getOfferIdFromQuote(quoteVO);
                    return tmForumAdapter.getOfferingParameters(offerId)
                            .map(offeringParams -> {
                                permissionVOS.add(new PermissionVO()
                                        .odrlColonAction(offeringParams.action()));
                                OfferVO offerVO = new OfferVO()
                                        .atId(offerId)
                                        .odrlColonTarget(offeringParams.target())
                                        .odrlColonPermission(permissionVOS);
                                if (obligation.getOdrlColonConstraint() != null && !obligation.getOdrlColonConstraint().isEmpty()) {
                                    offerVO.odrlColonObligation(List.of(obligation));
                                }
                                return new NegotiationRequestVO()
                                        .dspaceColonCallbackAddress("")
                                        .dspaceColonConsumerPid(String.format(URN_UUID_TEMPLATE, UUID.randomUUID()))
                                        .dspaceColonOffer(offerVO);
                            });
                })
                .onErrorMap(t -> {
                    throw new RainbowException("Was not able to get the offering parameters.", t);
                })
                .flatMap(negotiationRequestVO ->
                        rainbowAdapter
                                .createNegotiationRequest(negotiationRequestVO)
                                .flatMap(id -> tmForumAdapter.updateExternalId(quoteVO, id))
                                .map(t -> HttpResponse.noContent()));
    }

    @Override
    public Mono<HttpResponse<?>> handleQuoteStateChange(QuoteVO quoteVO) {
        QuoteStateTypeVO quoteStateTypeVO = quoteVO.getState();
        log.warn("Quote state is {}", quoteStateTypeVO);
        return switch (quoteStateTypeVO) {
            case APPROVED ->
                    rainbowAdapter.updateNegotiationProcessByProviderId(quoteVO.getExternalId(), "dspace:OFFERED")
                            .map(t -> HttpResponse.noContent());
            case ACCEPTED -> tmForumAdapter.getConsumerDid(quoteVO)
                    .flatMap(consumerDid ->
                            rainbowAdapter
                                    .createAgreementAfterNegotiation(quoteVO.getExternalId(), consumerDid, generalProperties.getDid())
                                    .flatMap(r -> rainbowAdapter.updateNegotiationProcessByProviderId(quoteVO.getExternalId(), "dspace:AGREED")
                                            .map(t -> HttpResponse.noContent())));
            // a lot of requests can just be ignored
            default -> Mono.just(HttpResponse.noContent());
        };
    }

    @Override
    public Mono<HttpResponse<?>> handleQuoteDeletion(QuoteVO quoteVO) {
        if (quoteVO.getExternalId() == null && !quoteVO.getExternalId().isEmpty()) {
            return rainbowAdapter.updateNegotiationProcessByProviderId(quoteVO.getExternalId(), "dspace:TERMINATED")
                    .map(t -> HttpResponse.noContent());
        }

        return Mono.just(HttpResponse.noContent());
    }

    private String getOfferIdFromQuote(QuoteVO quoteVO) {
        return getRelevantQuoteItems(quoteVO)
                .map(QuoteItemVO::getProductOffering)
                .map(org.fiware.iam.tmforum.quote.model.ProductOfferingRefVO::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The event does not reference an offer."));
    }

    private Stream<QuoteItemVO> getRelevantQuoteItems(QuoteVO quoteVO) {
        return quoteVO
                .getQuoteItem()
                .stream()
                .filter(qi -> !qi.getState().equals("rejected"));
    }

    private List<Policy> getPoliciesFromQuote(ObjectMapper objectMapper, QuoteVO quoteVO) {
        return getRelevantQuoteItems(quoteVO)
                .map(QuoteItemVO::getUnknownProperties)
                .map(Map::entrySet)
                .flatMap(Set::stream)
                .filter(ap -> ap.getKey().equals("policy"))
                .map(Map.Entry::getValue)
                .filter(List.class::isInstance)
                .map(List.class::cast)
                .flatMap(List::stream)
                .map(v -> objectMapper.convertValue(v, Policy.class))
                .toList();
    }

    private List<PermissionVO> getPermissionsFromQuote(ObjectMapper objectMapper, QuoteVO quoteVO) {
        return getPoliciesFromQuote(objectMapper, quoteVO)
                .stream()
                .map(o -> objectMapper.convertValue(o, Policy.class))
                .map(Policy::getPermission)
                .flatMap(List::stream)
                .toList();
    }


    private Mono<ObligationVO> getObligationFromQuote(QuoteVO quoteVO) {
        ObligationVO obligationVO = new ObligationVO();
        obligationVO.odrlColonAction(PAYMENT_ACTION);

        return Mono.zip(
                getRelevantQuoteItems(quoteVO)
                        .map(QuoteItemVO::getQuoteItemPrice)
                        .flatMap(List::stream)
                        .map(priceMapper::toObligationConstraints)
                        .toList(),
                cons -> obligationVO.odrlColonConstraint(
                        Arrays.asList(cons)
                                .stream()
                                .filter(List.class::isInstance)
                                .map(List.class::cast)
                                .flatMap(List::stream)
                                .toList()));
    }
}
