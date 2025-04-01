package org.fiware.iam.dsp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.exception.RainbowException;
import org.fiware.rainbow.api.AgreementApiClient;
import org.fiware.rainbow.api.ContractApiClient;
import org.fiware.rainbow.model.*;
import reactor.core.publisher.Mono;


/**
 * Adapter to handle communication with Rainbow.
 */
@Singleton
@RequiredArgsConstructor
@Slf4j
public class RainbowAdapter {

	private final AgreementApiClient agreementApiClient;
	private final ContractApiClient contractApiClient;
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
				.onErrorResume(t -> {
					return Mono.just(false);
				});
	}

	/**
	 * Create the given negotiation request at rainbow and return the providerId
	 */
	public Mono<String> createNegotiationRequest(NegotiationRequestVO negotiationRequestVO) {
		return contractApiClient.createRequest(negotiationRequestVO)
				.map(HttpResponse::body)
				.map(NegotiationVO::getDspaceColonProviderPid)
				.onErrorMap(t -> new RainbowException("Was not able to create negotiation request.", t));
	}

	public Mono<String> getNegotiationProcessState(String providerId) {
		return contractApiClient.getProcessById(providerId)
				.map(HttpResponse::body)
				.map(ProviderNegotiationVO::getState)
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


}
