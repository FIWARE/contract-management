package org.fiware.iam.tmforum;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.exception.OrganizationRetrievalException;
import org.fiware.iam.tmforum.party.api.OrganizationApiClient;
import org.fiware.iam.tmforum.party.model.CharacteristicVO;
import org.fiware.iam.tmforum.party.model.OrganizationVO;

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
    public String getDID(String organizationId) {
        HttpResponse<OrganizationVO> organizationResponse = apiClient.retrieveOrganization(organizationId, FIELD_NAME_PARTY_CHARACTERISTIC);
        if (organizationResponse.getStatus() != HttpStatus.OK) {
            throw new OrganizationRetrievalException("Failed to retrieve organization. Status:" + organizationResponse.getStatus(), organizationId);
        }
        log.debug("Retrieved organization info:{}", organizationResponse.body());
        return Stream
                .ofNullable(organizationResponse.body())
                .map(OrganizationVO::getPartyCharacteristic)
                .flatMap(Collection::stream)
                .filter(entry -> PARTY_CHARACTERISTIC_DID.equals(entry.getName()))
                .map(CharacteristicVO::getValue)
                .filter(e -> e instanceof String)
                .map(e -> (String) e)
                .findAny()
                .orElseThrow(() -> new OrganizationRetrievalException("Could not find organizations DID in response: " + organizationResponse.body(), organizationId));
    }
}
