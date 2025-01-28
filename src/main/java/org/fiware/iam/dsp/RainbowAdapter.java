package org.fiware.iam.dsp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.exception.RainbowException;
import org.fiware.rainbow.api.AgreementApiClient;
import org.fiware.rainbow.model.AgreementCreateVO;
import org.fiware.rainbow.model.AgreementVO;
import reactor.core.publisher.Mono;


/**
 * Adapter to handle communication with Rainbow.
 */
@Singleton
@RequiredArgsConstructor
@Slf4j
public class RainbowAdapter {

	private final AgreementApiClient agreementApiClient;
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
				.onErrorMap(t -> new RainbowException("Was not able to create agreement"))
				.map(HttpResponse::body)
				.map(body -> objectMapper.convertValue(body, AgreementVO.class));
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
				.onErrorReturn(false);
	}

}
