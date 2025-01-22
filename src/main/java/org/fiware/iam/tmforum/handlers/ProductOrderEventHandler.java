package org.fiware.iam.tmforum.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseFactory;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.fiware.iam.tmforum.OrganizationResolver;
import org.fiware.iam.tmforum.productorder.model.*;
import org.fiware.rainbow.api.AgreementApiClient;
import org.fiware.rainbow.model.AgreementCreateVO;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@RequiredArgsConstructor
@Singleton
@Slf4j
public class ProductOrderEventHandler implements EventHandler {

	private static final String CREATE_EVENT = "ProductOrderCreateEvent";
	private static final String DELETE_EVENT = "ProductOrderDeleteEvent";
	private static final String STATE_CHANGE_EVENT = "ProductOrderStateChangeEvent";

	private static final List<String> SUPPORTED_EVENT_TYPES = List.of(CREATE_EVENT, DELETE_EVENT, STATE_CHANGE_EVENT);

	private final ObjectMapper objectMapper;
	private final OrganizationResolver organizationResolver;
	private final TrustedIssuersListAdapter trustedIssuersListAdapter;
	private final AgreementApiClient agreementApiClient;

	@Override
	public boolean isEventTypeSupported(String eventType) {
		return SUPPORTED_EVENT_TYPES.contains(eventType);
	}

	@Override
	public Mono<HttpResponse<?>> handleEvent(String eventType, Map<String, Object> event) {

		String orgId = Stream
				.ofNullable(event)
				.map(rawEvent -> objectMapper.convertValue(rawEvent, ProductOrderCreateEventVO.class))
				.map(ProductOrderCreateEventVO::getEvent)
				.map(ProductOrderCreateEventPayloadVO::getProductOrder)
				.map(ProductOrderVO::getRelatedParty)
				.map(rpl -> {
					if (rpl.size() > 1) {
						throw new IllegalArgumentException("Expected exactly one ordering organization.");
					}
					return rpl.get(0);
				})
				.map(RelatedPartyVO::getId)
				.findAny()
				.orElseThrow(() -> new IllegalArgumentException("The ProductOrder-Event does not include a valid organization id."));

		return switch (eventType) {
			case CREATE_EVENT -> handelCreateEvent(orgId, event);
			case STATE_CHANGE_EVENT -> handelStateChangeEvent(orgId, event);
			case DELETE_EVENT -> handelDeleteEvent(orgId);
			default -> throw new IllegalArgumentException("Invalid event type received.");
		};

	}

	private Mono<HttpResponse<?>> handelCreateEvent(String organizationId, Map<String, Object> event) {
		ProductOrderCreateEventVO productOrderCreateEventVO = objectMapper.convertValue(event, ProductOrderCreateEventVO.class);

		ProductOrderVO productOrderVO = Optional.ofNullable(productOrderCreateEventVO.getEvent())
				.map(ProductOrderCreateEventPayloadVO::getProductOrder)
				.orElseThrow(() -> new IllegalArgumentException("The event does not contain a product order."));

		boolean isCompleted = isCompleted(productOrderVO);
		if (isCompleted) {
			log.debug("The received event is not in state completed.");
			return Mono.just(HttpResponse.noContent());
		}

		return Mono.zipDelayError(createAgreement(productOrderVO, organizationId), allowIssuer(organizationId))
				.map(tuple -> HttpResponse.noContent());
	}

	private static boolean isCompleted(ProductOrderVO productOrderVO) {
		return Optional.ofNullable(productOrderVO.getState())
				.filter(ProductOrderStateTypeVO.COMPLETED::equals)
				.isPresent();
	}

	private Mono<HttpResponse<?>> handelStateChangeEvent(String organizationId, Map<String, Object> event) {
		ProductOrderStateChangeEventVO productOrderStateChangeEventVO = objectMapper.convertValue(event, ProductOrderStateChangeEventVO.class);
		ProductOrderVO productOrderVO = Optional.ofNullable(productOrderStateChangeEventVO.getEvent())
				.map(ProductOrderStateChangeEventPayloadVO::getProductOrder)
				.orElseThrow(() -> new IllegalArgumentException("The event does not contain a product order."));

		if (isCompleted(productOrderVO)) {
			return Mono.zipDelayError(createAgreement(productOrderVO, organizationId), allowIssuer(organizationId))
					.map(tuple -> HttpResponse.noContent());
		} else {
			// TODO: add delete agreement once its supported by rainbow
			return denyIssuer(organizationId);
		}
	}

	private Mono<HttpResponse<?>> handelDeleteEvent(String organizationId) {
		throw new UnsupportedOperationException();
	}

	private Mono<HttpResponse<?>> createAgreement(ProductOrderVO productOrderVO, String organizationId) {

		return Mono.zipDelayError(productOrderVO
				.getProductOrderItem()
				.stream()
				.map(ProductOrderItemVO::getProductOffering)
				.map(offering -> new AgreementCreateVO().identity(organizationId).dataServiceId(offering.getId()))
				.map(agreementApiClient::createAgreement)
				.toList(), res -> HttpResponse.noContent());
	}

	private Mono<HttpResponse<?>> allowIssuer(String organizationId) {
		return organizationResolver.getDID(organizationId)
				.flatMap(trustedIssuersListAdapter::allowIssuer)
				.map(issuer -> HttpResponseFactory.INSTANCE.status(HttpStatus.CREATED));
	}

	private Mono<HttpResponse<?>> denyIssuer(String organizationId) {
		return organizationResolver.getDID(organizationId)
				.flatMap(trustedIssuersListAdapter::allowIssuer)
				.map(issuer -> HttpResponseFactory.INSTANCE.status(HttpStatus.CREATED));
	}
}
