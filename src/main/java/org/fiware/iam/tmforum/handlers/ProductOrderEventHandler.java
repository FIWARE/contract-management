package org.fiware.iam.tmforum.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseFactory;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.TMFMapper;
import org.fiware.iam.dsp.RainbowAdapter;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.fiware.iam.tmforum.OrganizationResolver;
import org.fiware.iam.tmforum.TMForumAdapter;
import org.fiware.iam.tmforum.agreement.model.RelatedPartyTmfVO;
import org.fiware.iam.tmforum.productorder.model.*;
import org.fiware.rainbow.model.AgreementVO;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;


/**
 * Handle all incoming events in connection to ProductOrder
 */
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
	private final RainbowAdapter rainbowAdapter;
	private final TMForumAdapter tmForumAdapter;

	private final TMFMapper tmfMapper;

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
				.filter(Objects::nonNull)
				.map(rpl -> {
					if (rpl.size() != 1) {
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
			case DELETE_EVENT -> handelDeleteEvent(orgId, event);
			default -> throw new IllegalArgumentException("Invalid event type received.");
		};

	}

	private Mono<HttpResponse<?>> handelCreateEvent(String organizationId, Map<String, Object> event) {
		ProductOrderCreateEventVO productOrderCreateEventVO = objectMapper.convertValue(event, ProductOrderCreateEventVO.class);

		ProductOrderVO productOrderVO = Optional.ofNullable(productOrderCreateEventVO.getEvent())
				.map(ProductOrderCreateEventPayloadVO::getProductOrder)
				.orElseThrow(() -> new IllegalArgumentException("The event does not contain a product order."));

		boolean isCompleted = isCompleted(productOrderVO);
		if (!isCompleted) {
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
			return Mono.zipDelayError(
							createAgreement(productOrderVO, organizationId),
							allowIssuer(organizationId))
					.map(tuple -> HttpResponse.noContent());
		} else {
			return handleStopEvent(organizationId, event);
		}
	}

	private Mono<HttpResponse<?>> handleStopEvent(String organizationId, Map<String, Object> event) {
		ProductOrderStateChangeEventVO productOrderStateChangeEventVO = objectMapper.convertValue(event, ProductOrderStateChangeEventVO.class);
		ProductOrderVO productOrderVO = Optional.ofNullable(productOrderStateChangeEventVO.getEvent())
				.map(ProductOrderStateChangeEventPayloadVO::getProductOrder)
				.orElseThrow(() -> new IllegalArgumentException("The event does not contain a product order."));

		Mono<HttpResponse<?>> agreementsDeletion = deleteAgreement(productOrderVO);
		Mono<HttpResponse<?>> issuerDenial = denyIssuer(organizationId);

		return Mono.zipDelayError(List.of(agreementsDeletion, issuerDenial), responses -> Arrays.stream(responses)
				.filter(HttpResponse.class::isInstance)
				.map(HttpResponse.class::cast)
				.filter(response -> response.status().getCode() > 299)
				.findAny()
				.orElse(HttpResponse.ok()));
	}

	private Mono<HttpResponse<?>> handelDeleteEvent(String organizationId, Map<String, Object> event) {
		ProductOrderDeleteEventVO productOrderDeleteEventVO = objectMapper.convertValue(event, ProductOrderDeleteEventVO.class);
		ProductOrderVO productOrderVO = Optional.ofNullable(productOrderDeleteEventVO.getEvent())
				.map(ProductOrderDeleteEventPayloadVO::getProductOrder)
				.orElseThrow(() -> new IllegalArgumentException("The event does not contain a product order."));

		Mono<HttpResponse<?>> agreementsDeletion = deleteAgreement(productOrderVO);
		Mono<HttpResponse<?>> issuerDenial = denyIssuer(organizationId);

		return Mono.zipDelayError(List.of(agreementsDeletion, issuerDenial), responses -> Arrays.stream(responses)
				.filter(HttpResponse.class::isInstance)
				.map(HttpResponse.class::cast)
				.filter(response -> response.status().getCode() > 299)
				.findAny()
				.orElse(HttpResponse.ok()));
	}


	private Mono<?> createAgreement(ProductOrderVO productOrderVO, String organizationId) {

		List<RelatedPartyTmfVO> relatedPartyTmfVOS = productOrderVO
				.getRelatedParty()
				.stream()
				.map(tmfMapper::map)
				.toList();

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
				.flatMap(Function.identity());
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

	private Mono<HttpResponse<?>> allowIssuer(String organizationId) {
		return organizationResolver.getDID(organizationId)
				.flatMap(trustedIssuersListAdapter::allowIssuer)
				.map(issuer -> HttpResponseFactory.INSTANCE.status(HttpStatus.CREATED));
	}

	private Mono<HttpResponse<?>> deleteAgreement(ProductOrderVO productOrderVO) {
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

	private Mono<HttpResponse<?>> denyIssuer(String organizationId) {
		return organizationResolver.getDID(organizationId)
				.flatMap(trustedIssuersListAdapter::denyIssuer);
	}
}
