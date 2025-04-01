package org.fiware.iam.tmforum.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.dsp.RainbowAdapter;
import org.fiware.iam.exception.RainbowException;
import org.fiware.iam.tmforum.TMForumAdapter;
import org.fiware.iam.tmforum.quote.api.QuoteApiClient;
import org.fiware.iam.tmforum.quote.model.*;
import org.fiware.rainbow.api.ContractApiClient;
import org.fiware.rainbow.model.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
	private final ContractApiClient contractApiClient;
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
		NegotiationRequestVO negotiationRequestVO = quoteVO
				.getQuoteItem()
				.stream().map(QuoteItemVO::getProductOffering)
				.map(ProductOfferingRefVO::getId)
				.findFirst()
				.map(offerId -> new NegotiationRequestVO()
						.dspaceColonCallbackAddress("http://something.org")
						.dspaceColonConsumerPid(String.format(URN_UUID_TEMPLATE, UUID.randomUUID()))
						.dspaceColonOffer(new OfferVO()
								.atId(offerId)
								.odrlColonTarget("urn:some:urn")
								.odrlColonPermission(List.of(new PermissionVO()
										.odrlColonAction("odrl:use")))))
				.orElseThrow(() -> new IllegalArgumentException("The event does not reference an offer."));

		return rainbowAdapter
				.createNegotiationRequest(negotiationRequestVO)
				.flatMap(id -> tmForumAdapter.updateExternalId(quoteVO, id))
				.map(t -> HttpResponse.noContent());
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
			case ACCEPTED ->
					rainbowAdapter.updateNegotiationProcessByProviderId(quoteVO.getExternalId(), "dspace:AGREED")
							.map(t -> HttpResponse.noContent());
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
}
