package org.fiware.iam.tmforum.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseFactory;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.TMFMapper;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.dsp.RainbowAdapter;
import org.fiware.iam.exception.RainbowException;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.fiware.iam.tmforum.CredentialsConfigResolver;
import org.fiware.iam.tmforum.OrganizationResolver;
import org.fiware.iam.tmforum.TMForumAdapter;
import org.fiware.iam.tmforum.agreement.model.RelatedPartyTmfVO;
import org.fiware.iam.tmforum.productorder.model.*;
import org.fiware.iam.tmforum.quote.model.QuoteItemVO;
import org.fiware.iam.tmforum.quote.model.QuoteStateTypeVO;
import org.fiware.iam.tmforum.quote.model.QuoteVO;
import org.fiware.rainbow.model.AgreementVO;
import org.fiware.rainbow.model.ProviderNegotiationVO;
import org.graalvm.compiler.nodes.calc.IntegerDivRemNode;
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

	private static final String STATE_VERIFIED = "dspace:VERIFIED";
	private static final String STATE_FINALIZED = "dspace:FINALIZED";

	private static final String CUSTOMER_ROLE = "Customer";

	private final ObjectMapper objectMapper;
	private final OrganizationResolver organizationResolver;
	private final CredentialsConfigResolver credentialsConfigResolver;
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
				.map(rpl -> getCustomer(rpl).orElseThrow(() -> new IllegalArgumentException("Exactly one ordering related party is expected.")))
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

	private Optional<RelatedPartyVO> getCustomer(List<RelatedPartyVO> relatedPartyVOS) {
		if (relatedPartyVOS == null || relatedPartyVOS.isEmpty()) {
			return Optional.empty();
		}
		if (relatedPartyVOS.size() == 1) {
			String role = relatedPartyVOS.getFirst().getRole();
			if (role == null || role.equals(CUSTOMER_ROLE)) {
				return Optional.of(relatedPartyVOS.getFirst());
			}
		}
		return relatedPartyVOS.stream()
				.filter(relatedPartyVO -> relatedPartyVO.getRole() != null)
				.filter(relatedPartyVO -> relatedPartyVO.getRole().equals(CUSTOMER_ROLE))
				.findFirst();
	}

	private Mono<HttpResponse<?>> handelCreateEvent(String organizationId, Map<String, Object> event) {
		ProductOrderCreateEventVO productOrderCreateEventVO = objectMapper.convertValue(event, ProductOrderCreateEventVO.class);

		ProductOrderVO productOrderVO = Optional.ofNullable(productOrderCreateEventVO.getEvent())
				.map(ProductOrderCreateEventPayloadVO::getProductOrder)
				.orElseThrow(() -> new IllegalArgumentException("The event does not contain a product order."));

		if (isNotRejected(productOrderVO) && containsQuote(productOrderVO)) {
			return updateNegotiation(productOrderVO);
		}

		boolean isCompleted = isCompleted(productOrderVO);
		if (!isCompleted) {
			log.debug("The received event is not in state completed.");
			return Mono.just(HttpResponse.noContent());
		}

		return Mono.zipDelayError(handleComplete(productOrderVO, organizationId), allowIssuer(organizationId, productOrderVO))
				.map(tuple -> HttpResponse.noContent());
	}

	private Mono<HttpResponse<?>> updateNegotiation(ProductOrderVO productOrderVO) {

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

	private static QuoteRefVO getQuoteRef(ProductOrderVO productOrderVO) {
		// integration with IDSA Contract Negotiation is only supported for productOrders with a single quote.
		if (productOrderVO.getQuote().size() != 1) {
			throw new RainbowException("IDSA Contract Negotiation does not support the inclusion of multiple processes into one product.");
		}
		return productOrderVO.getQuote().get(0);
	}

	private static boolean containsQuote(ProductOrderVO productOrderVO) {
		return productOrderVO.getQuote() != null && !productOrderVO.getQuote().isEmpty();
	}

	private static boolean isCompleted(ProductOrderVO productOrderVO) {
		return Optional.ofNullable(productOrderVO.getState())
				.filter(ProductOrderStateTypeVO.COMPLETED::equals)
				.isPresent();
	}

	private static boolean isNotRejected(ProductOrderVO productOrderVO) {
		return Optional.ofNullable(productOrderVO.getState())
				.filter(state -> state == ProductOrderStateTypeVO.REJECTED)
				.isEmpty();
	}

	private Mono<HttpResponse<?>> handelStateChangeEvent(String organizationId, Map<String, Object> event) {
		ProductOrderStateChangeEventVO productOrderStateChangeEventVO = objectMapper.convertValue(event, ProductOrderStateChangeEventVO.class);
		ProductOrderVO productOrderVO = Optional.ofNullable(productOrderStateChangeEventVO.getEvent())
				.map(ProductOrderStateChangeEventPayloadVO::getProductOrder)
				.orElseThrow(() -> new IllegalArgumentException("The event does not contain a product order."));

		if (isCompleted(productOrderVO)) {
			return Mono.zipDelayError(
							handleComplete(productOrderVO, organizationId),
							allowIssuer(organizationId, productOrderVO))
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
		Mono<HttpResponse<?>> issuerDenial = denyIssuer(organizationId, productOrderVO);

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
		Mono<HttpResponse<?>> issuerDenial = denyIssuer(organizationId, productOrderVO);

		return Mono.zipDelayError(List.of(agreementsDeletion, issuerDenial), responses -> Arrays.stream(responses)
				.filter(HttpResponse.class::isInstance)
				.map(HttpResponse.class::cast)
				.filter(response -> response.status().getCode() > 299)
				.findAny()
				.orElse(HttpResponse.ok()));
	}


	private Mono<?> handleComplete(ProductOrderVO productOrderVO, String organizationId) {

		List<RelatedPartyTmfVO> relatedPartyTmfVOS = productOrderVO
				.getRelatedParty()
				.stream()
				.map(tmfMapper::map)
				.map(rr -> {
					rr.unknownProperties(null);
					return rr;
				})
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
					.flatMap(Function.identity());
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
						throw new RainbowException("Was not able to update the negotiation.");
					});
		}
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

	private Mono<HttpResponse<?>> allowIssuer(String organizationId, ProductOrderVO productOrderVO) {
		return Mono.zip(organizationResolver.getDID(organizationId), credentialsConfigResolver.getCredentialsConfig(productOrderVO))
				.flatMap(resultTuple -> trustedIssuersListAdapter.allowIssuer(resultTuple.getT1(), resultTuple.getT2()))
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

	private Mono<HttpResponse<?>> denyIssuer(String organizationId, ProductOrderVO productOrderVO) {

		return Mono.zip(organizationResolver.getDID(organizationId), credentialsConfigResolver.getCredentialsConfig(productOrderVO))
				.flatMap(resultTuple -> trustedIssuersListAdapter.denyIssuer(resultTuple.getT1(), resultTuple.getT2()));
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

}
