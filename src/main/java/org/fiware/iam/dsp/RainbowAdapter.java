package org.fiware.iam.dsp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.exception.RainbowException;
import org.fiware.rainbow.api.AgreementApiClient;
import org.fiware.rainbow.api.ContractApiClient;
import org.fiware.rainbow.api.ParticipantApiClient;
import org.fiware.rainbow.model.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;


/**
 * Adapter to handle communication with Rainbow.
 */
@Singleton
@RequiredArgsConstructor
@Slf4j
public class RainbowAdapter {

	private final AgreementApiClient agreementApiClient;
	private final ContractApiClient contractApiClient;
	private final ParticipantApiClient participantApiClient;
	private final ObjectMapper objectMapper;

	/**
	 * Create the agreement for the given organization and offer
	 */
	public Mono<AgreementVO> createAgreement(String organizationId, String offeringId) {
		AgreementCreateVO agreementCreateVO = new AgreementCreateVO()
				.identity(organizationId)
				.dataServiceId(offeringId);

		return agreementApiClient
				.createAgreement(agreementCreateVO)
				.map(HttpResponse::body)
				.map(body -> objectMapper.convertValue(body, AgreementVO.class))
				.onErrorMap(t -> {
					throw new RainbowException("Was not able to create agreement");
				});
	}

	private Mono<LastOfferVO> getTheLastOffer(String processId) {
		return contractApiClient.getLastOfferForProcess(processId)
				.map(HttpResponse::body)
				.onErrorMap(t -> new RainbowException(String.format("Was not able to find the last offer for %s.", processId)));
	}

	public Mono<AgreementVO> createAgreementAfterNegotiation(String providerId, String consumerOrganization, String providerOrganization) {
		return getNegotiationProcess(providerId)
				.flatMap(providerNegotiationVO ->
						getTheLastOffer(providerNegotiationVO.getCnProcessId())
								.flatMap(lastOfferVO -> {
									OdrlAgreementVO agreementVO = new OdrlAgreementVO()
											.atId("urn:uuid:" + UUID.randomUUID())
											.odrlColonTarget(lastOfferVO.getOfferContent().getOdrlColonTarget())
											.odrlColonAssignee(prefixDid(consumerOrganization))
											.odrlColonAssigner(prefixDid(providerOrganization))
											.odrlColonPermission(lastOfferVO.getOfferContent().getOdrlColonPermission());
									AgreementRequestVO agreementRequestVO = new AgreementRequestVO()
											.dspaceColonProviderParticipantId(prefixDid(providerOrganization))
											.dspaceColonConsumerParticipantId(prefixDid(consumerOrganization))
											.odrlColonAgreement(agreementVO);
									return contractApiClient.createAgreementForProcess(providerNegotiationVO.getCnProcessId(), lastOfferVO.getCnMessageId(), agreementRequestVO);
								}))
				.map(HttpResponse::body)
				.map(r -> new AgreementVO())
				.onErrorMap(t -> {
					throw new RainbowException("Was not able to create agreement");
				});

	}

	public Mono<AgreementVO> getAgreement(String processId) {
		return contractApiClient.getAgreement(processId)
				.onErrorMap(t -> {
					throw new RainbowException("Was not able to create agreement");
				})
				.map(HttpResponse::body);
	}

	/**
	 * Delete the agreement with the given id
	 */
	public Mono<Boolean> deleteAgreement(String agreementId) {
		return agreementApiClient.deleteAgreementById(agreementId)
				.map(objectHttpResponse -> {
					if (objectHttpResponse.status().equals(HttpStatus.ACCEPTED)) {
						return true;
					}
					return false;
				})
				.onErrorResume(t -> Mono.just(false));
	}

	/**
	 * Create the given negotiation request at rainbow and return the providerId
	 */
	public Mono<String> createNegotiationRequest(NegotiationRequestVO negotiationRequestVO) {
		return contractApiClient.createRequest(negotiationRequestVO)
				.map(HttpResponse::body)
				.map(NegotiationVO::getDspaceColonProviderPid)
				.onErrorMap(t -> {
					throw new RainbowException("Was not able to create negotiation request.", t);
				});
	}

	public Mono<String> getNegotiationProcessState(String providerId) {
		return getNegotiationProcess(providerId)
				.map(ProviderNegotiationVO::getState);
	}

	public Mono<ProviderNegotiationVO> getNegotiationProcess(String providerId) {
		return contractApiClient.getProcessById(providerId)
				.map(HttpResponse::body)
				.onErrorMap(t -> new RainbowException(String.format("Was not able to find negotiation process %s.", providerId), t));
	}

	public Mono<Object> updateNegotiationProcessByProviderId(String providerId, String state) {
		return contractApiClient.getProcessById(providerId)
				.map(HttpResponse::body)
				.flatMap(pn -> {
					NegotiationProcessVO negotiationProcessVO = new NegotiationProcessVO()
							.dspaceColonConsumerPid(pn.getConsumerId())
							.dspaceColonProviderPid(pn.getProviderId())
							.dspaceColonState(state);
					return contractApiClient.updateProcessById(pn.getCnProcessId(), negotiationProcessVO);
				})
				.map(HttpResponse::body)
				.onErrorMap(t -> new RainbowException(String.format("Was not able to update negotiation process %s to %s.", providerId, state), t));
	}


	public Mono<Boolean> isParticipant(String id) {
		return participantApiClient.getParticipantById(prefixDid(id))
				.map(r -> true)
				.onErrorResume(t -> {
					if (t instanceof HttpClientResponseException re && re.getStatus().equals(HttpStatus.NOT_FOUND)) {
						return Mono.just(false);
					}
					throw new RainbowException(String.format("Was not able to check participant %s.", id), t);
				});
	}

	public Mono<String> createParticipant(String participantId, String participantType) {
		return isParticipant(participantId)
				.filter(r -> !r)
				.flatMap(p -> participantApiClient.createParticipant(new ParticipantVO()
								.dspaceColonParticipantId(prefixDid(participantId))
								.dspaceColonParticipantType(participantType)
								// set empty, rainbow does not support null
								.dspaceColonParticipantBaseUrl("")
								.dspaceColonExtraFields(Map.of()))
						.onErrorMap(t -> {
							throw new RainbowException(String.format("Was not able to create the participant %s with type %s.", participantId, participantType), t);
						})
						.map(HttpResponse::body)
						.map(ParticipantVO::getDspaceColonParticipantId))
				.defaultIfEmpty(prefixDid(participantId));
	}

	private static String prefixDid(String did) {
		return String.format("urn:%s", did);
	}

	private static String removeUrnPrefix(String urnDid) {
		return urnDid.replaceFirst("urn:", "");
	}
}
