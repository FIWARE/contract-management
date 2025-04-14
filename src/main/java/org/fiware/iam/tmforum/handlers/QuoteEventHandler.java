package org.fiware.iam.tmforum.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.PriceMapper;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.domain.Policy;
import org.fiware.iam.dsp.RainbowAdapter;
import org.fiware.iam.exception.RainbowException;
import org.fiware.iam.tmforum.TMForumAdapter;
import org.fiware.iam.tmforum.quote.model.*;
import org.fiware.rainbow.model.NegotiationRequestVO;
import org.fiware.rainbow.model.ObligationVO;
import org.fiware.rainbow.model.OfferVO;
import org.fiware.rainbow.model.PermissionVO;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Stream;

import static org.fiware.iam.PriceMapper.PAYMENT_ACTION;
import static org.fiware.iam.tmforum.TMForumAdapter.CONSUMER_ROLE;

/**
 * Handle all incoming events in connection to Quote
 */
@RequiredArgsConstructor
@Singleton
@Slf4j
public class QuoteEventHandler implements EventHandler {

	private static final String URN_UUID_TEMPLATE = "urn:uuid:%s";


	private static final String CREATE_EVENT = "QuoteCreateEvent";
	private static final String DELETE_EVENT = "QuoteDeleteEvent";
	private static final String STATE_CHANGE_EVENT = "QuoteStateChangeEvent";
	private static final String ATTRIBUTE_CHANGE_EVENT = "QuoteAttributeValueChangeEvent";

	private static final List<String> SUPPORTED_EVENT_TYPES = List.of(CREATE_EVENT, DELETE_EVENT, STATE_CHANGE_EVENT, ATTRIBUTE_CHANGE_EVENT);

	private final ObjectMapper objectMapper;
	private final GeneralProperties generalProperties;
	private final PriceMapper priceMapper;
	private final TMForumAdapter tmForumAdapter;
	private final RainbowAdapter rainbowAdapter;


	@Override
	public boolean isEventTypeSupported(String eventType) {
		return SUPPORTED_EVENT_TYPES.contains(eventType);
	}

	@Override
	public Mono<HttpResponse<?>> handleEvent(String eventType, Map<String, Object> event) {
		return switch (eventType) {
			case CREATE_EVENT -> handleQuoteCreation(event);
			case STATE_CHANGE_EVENT, ATTRIBUTE_CHANGE_EVENT -> handleQuoteStateChange(event);
			case DELETE_EVENT -> handleQuoteDeletion(event);
			default -> throw new IllegalArgumentException("Event type %s is not supported.".formatted(eventType));
		};
	}

	private Mono<HttpResponse<?>> handleQuoteCreation(Map<String, Object> event) {
		QuoteCreateEventVO quoteCreateEventVO = objectMapper.convertValue(event, QuoteCreateEventVO.class);
		QuoteVO quoteVO = quoteCreateEventVO.getEvent()
				.getQuote();
		List<PermissionVO> permissionVOS = new ArrayList<>(getPermissionsFromQuote(quoteVO));

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

	private Mono<HttpResponse<?>> handleQuoteStateChange(Map<String, Object> event) {
		QuoteStateChangeEventVO quoteStateChangeEventVO = objectMapper.convertValue(event, QuoteStateChangeEventVO.class);
		QuoteVO quoteVO = quoteStateChangeEventVO.getEvent()
				.getQuote();

		List<PermissionVO> permissionVOS = new ArrayList<>(getPermissionsFromQuote(quoteVO));
		QuoteStateTypeVO quoteStateTypeVO = quoteVO.getState();
		return switch (quoteStateTypeVO) {
			case APPROVED ->
					rainbowAdapter.updateNegotiationProcessByProviderId(quoteVO.getExternalId(), "dspace:OFFERED", permissionVOS)
							.map(t -> HttpResponse.noContent());
			case ACCEPTED -> tmForumAdapter.getConsumerDid(quoteVO)
					.flatMap(consumerDid ->
							rainbowAdapter
									.createAgreementAfterNegotiation(quoteVO.getExternalId(), consumerDid, generalProperties.getDid())
									.flatMap(r -> rainbowAdapter.updateNegotiationProcessByProviderId(quoteVO.getExternalId(), "dspace:AGREED", permissionVOS)
											.map(t -> HttpResponse.noContent())));
			default -> Mono.just(HttpResponse.badRequest());
		};
	}

	private Mono<HttpResponse<?>> handleQuoteDeletion(Map<String, Object> event) {
		QuoteDeleteEventVO quoteDeleteEventVO = objectMapper.convertValue(event, QuoteDeleteEventVO.class);
		QuoteVO quoteVO = quoteDeleteEventVO.getEvent()
				.getQuote();

		if (quoteVO.getExternalId() == null && !quoteVO.getExternalId().isEmpty()) {
			return rainbowAdapter.updateNegotiationProcessByProviderId(quoteVO.getExternalId(), "dspace:TERMINATED", List.of())
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

	private List<Policy> getPoliciesFromQuote(QuoteVO quoteVO) {
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

	private List<PermissionVO> getPermissionsFromQuote(QuoteVO quoteVO) {
		return getPoliciesFromQuote(quoteVO)
				.stream()
				.map(o -> objectMapper.convertValue(o, Policy.class))
				.map(Policy::getPermission)
				.flatMap(List::stream)
				.toList();
	}
}
