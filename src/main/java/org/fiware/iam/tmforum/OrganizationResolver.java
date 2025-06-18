package org.fiware.iam.tmforum;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.party.api.OrganizationApiClient;
import org.fiware.iam.tmforum.party.model.CharacteristicVO;
import org.fiware.iam.tmforum.party.model.ExternalReferenceVO;
import org.fiware.iam.tmforum.party.model.OrganizationVO;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Singleton
@Slf4j
@RequiredArgsConstructor
public class OrganizationResolver {
	private static final String PARTY_CHARACTERISTIC_DID = "did";
	private static final String FIELD_NAME_PARTY_CHARACTERISTIC = "partyCharacteristic";
	private static final String EXTERNAL_REFERENCE_IDM_ID = "idm_id";
	private static final String DID = "did";

	private final OrganizationApiClient apiClient;

	//TODO Cache me if you can
	public Mono<String> getDID(String organizationId) {
		return apiClient.retrieveOrganization(organizationId, null)
				.filter(response -> response.getStatus().equals(HttpStatus.OK))
				.map(HttpResponse::body)
				.map(ovo ->
						getDidFromExternalReference(ovo.getExternalReference())
								.or(() -> getDidFromPartyCharacteristics(ovo.getPartyCharacteristic()))
								.orElseThrow(() -> new TMForumException("Could not find organizations DID (%s) in response.".formatted(organizationId)))
				);
	}

	private Optional<String> getDidFromPartyCharacteristics(List<CharacteristicVO> characteristicVOS) {
		if (characteristicVOS == null) {
			return Optional.empty();
		}
		return characteristicVOS.stream()
				.filter(entry -> PARTY_CHARACTERISTIC_DID.equals(entry.getName()))
				.map(CharacteristicVO::getValue)
				.filter(e -> e instanceof String)
				.map(e -> (String) e)
				.filter(this::isDid)
				.findAny();
	}

	private Optional<String> getDidFromExternalReference(List<ExternalReferenceVO> externalReferenceVOList) {
		log.warn("External refs {}", externalReferenceVOList);
		if (externalReferenceVOList == null) {
			return Optional.empty();
		}
		return externalReferenceVOList.stream()
				.filter(ervo -> ervo.getExternalReferenceType().equals(EXTERNAL_REFERENCE_IDM_ID))
				.map(ExternalReferenceVO::getName)
				.filter(this::isDid)
				.findFirst();
	}

	private boolean isDid(String id) {
		String[] idParts = id.split(":");
		return idParts.length >= 3 && idParts[0].equals(DID);
	}
}

