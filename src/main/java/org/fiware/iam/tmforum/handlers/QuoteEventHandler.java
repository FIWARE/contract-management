package org.fiware.iam.tmforum.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.dsp.RainbowAdapter;
import org.fiware.iam.exception.RainbowException;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.OrganizationResolver;
import org.fiware.iam.tmforum.TMForumAdapter;
import org.fiware.iam.tmforum.productcatalog.model.CharacteristicValueSpecificationVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationCharacteristicVO;
import org.fiware.iam.tmforum.quote.model.*;
import org.fiware.rainbow.model.NegotiationRequestVO;
import org.fiware.rainbow.model.OfferVO;
import org.fiware.rainbow.model.PermissionVO;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
	private final OrganizationResolver organizationResolver;
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
			default -> throw new IllegalArgumentException("Even type %s is not supported.".formatted(eventType));
		};
	}

	private Mono<HttpResponse<?>> handleQuoteCreation(Map<String, Object> event) {
		QuoteCreateEventVO quoteCreateEventVO = objectMapper.convertValue(event, QuoteCreateEventVO.class);
		QuoteVO quoteVO = quoteCreateEventVO.getEvent()
				.getQuote();

		return tmForumAdapter.getConsumerDid(quoteVO)
				.flatMap(id -> rainbowAdapter.createParticipant(id, CONSUMER_ROLE))
				.onErrorMap(t -> new RainbowException("Was not able to create consumer.", t))
				.flatMap(r -> {
					String offerId = getOfferIdFromQuote(quoteVO);
					return tmForumAdapter.getOfferingParameters(offerId)
							.map(offeringParams -> new NegotiationRequestVO()
									.dspaceColonCallbackAddress("")
									.dspaceColonConsumerPid(String.format(URN_UUID_TEMPLATE, UUID.randomUUID()))
									.dspaceColonOffer(new OfferVO()
											.atId(offerId)
											.odrlColonTarget(offeringParams.target())
											.odrlColonPermission(List.of(new PermissionVO()
													.odrlColonAction(offeringParams.action())))));
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


	private Mono<HttpResponse<?>> handleQuoteStateChange(Map<String, Object> event) {
		QuoteStateChangeEventVO quoteStateChangeEventVO = objectMapper.convertValue(event, QuoteStateChangeEventVO.class);
		QuoteVO quoteVO = quoteStateChangeEventVO.getEvent()
				.getQuote();

		QuoteStateTypeVO quoteStateTypeVO = quoteVO.getState();
		return switch (quoteStateTypeVO) {
			case APPROVED ->
					rainbowAdapter.updateNegotiationProcessByProviderId(quoteVO.getExternalId(), "dspace:OFFERED")
							.map(t -> HttpResponse.noContent());
			case ACCEPTED -> tmForumAdapter.getConsumerDid(quoteVO)
					.flatMap(consumerDid -> rainbowAdapter
							.createAgreementAfterNegotiation(quoteVO.getExternalId(), consumerDid, generalProperties.getDid())
							.flatMap(r -> rainbowAdapter.updateNegotiationProcessByProviderId(quoteVO.getExternalId(), "dspace:AGREED")
									.map(t -> HttpResponse.noContent())));
			default -> Mono.just(HttpResponse.badRequest());
		};
	}

	private Mono<HttpResponse<?>> handleQuoteDeletion(Map<String, Object> event) {
		QuoteDeleteEventVO quoteDeleteEventVO = objectMapper.convertValue(event, QuoteDeleteEventVO.class);
		QuoteVO quoteVO = quoteDeleteEventVO.getEvent()
				.getQuote();

		if (quoteVO.getExternalId() == null && !quoteVO.getExternalId().isEmpty()) {
			return rainbowAdapter.updateNegotiationProcessByProviderId(quoteVO.getExternalId(), "dspace:TERMINATED")
					.map(t -> HttpResponse.noContent());
		}

		return Mono.just(HttpResponse.noContent());
	}

	private String getOfferIdFromQuote(QuoteVO quoteVO) {
		return quoteVO
				.getQuoteItem()
				.stream()
				.filter(qi -> !qi.getState().equals("rejected"))
				.map(QuoteItemVO::getProductOffering)
				.map(org.fiware.iam.tmforum.quote.model.ProductOfferingRefVO::getId)
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("The event does not reference an offer."));
	}
}
