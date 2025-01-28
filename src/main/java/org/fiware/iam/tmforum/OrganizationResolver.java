package org.fiware.iam.tmforum;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.party.api.OrganizationApiClient;
import org.fiware.iam.tmforum.party.model.CharacteristicVO;
import org.fiware.iam.tmforum.party.model.OrganizationVO;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.stream.Stream;

@Singleton
@Slf4j
@RequiredArgsConstructor
public class OrganizationResolver {
	private static final String PARTY_CHARACTERISTIC_DID = "did";
	private static final String FIELD_NAME_PARTY_CHARACTERISTIC = "partyCharacteristic";

	private final OrganizationApiClient apiClient;


	//TODO Cache me if you can
	public Mono<String> getDID(String organizationId) {
		return apiClient.retrieveOrganization(organizationId, FIELD_NAME_PARTY_CHARACTERISTIC)
				.filter(response -> response.getStatus().equals(HttpStatus.OK))
				.map(HttpResponse::body)
				.map(OrganizationVO::getPartyCharacteristic)
				.map(l ->
						l.stream()
								.filter(entry -> PARTY_CHARACTERISTIC_DID.equals(entry.getName()))
								.map(CharacteristicVO::getValue)
								.filter(e -> e instanceof String)
								.map(e -> (String) e)
								.findAny()
								.orElseThrow(() -> new TMForumException("Could not find organizations DID (%s) in response.".formatted(organizationId)))
				);
	}
}
