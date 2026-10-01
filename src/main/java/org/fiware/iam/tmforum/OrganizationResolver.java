package org.fiware.iam.tmforum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.domain.ContractManagement;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.party.api.OrganizationApiClient;
import org.fiware.iam.tmforum.party.model.CharacteristicVO;
import org.fiware.iam.tmforum.party.model.ExternalReferenceVO;
import org.fiware.iam.tmforum.party.model.OrganizationVO;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

@Requires(condition = GeneralProperties.TmForumCondition.class)
@Singleton
@Slf4j
@RequiredArgsConstructor
public class OrganizationResolver {

    @Value("${general.organization.provider.role:provider}")
    private String PROVIDER_ROLE;

    private static final String PARTY_CHARACTERISTIC_DID = "did";
    private static final String FIELD_NAME_CONTRACT_MANAGEMENT = "contractManagement";
    private static final String EXTERNAL_REFERENCE_IDM_ID = "idm_id";
    private static final String DID = "did";

    private final GeneralProperties generalProperties;
    private final ObjectMapper objectMapper;
    private final OrganizationApiClient apiClient;

    //TODO Cache me if you can
    public Mono<String> getDID(String organizationId) {
        return retrieveOrganization(organizationId)
                .map(ovo -> getDid(organizationId, ovo));
    }

    public Mono<ContractManagement> getContractManagement(String organizationId) {
        return retrieveOrganization(organizationId)
                .map(ovo -> {
                    if (getDid(organizationId, ovo).equals(generalProperties.getDid())) {
                        return new ContractManagement(true);
                    }
                    return Optional.ofNullable(ovo.getPartyCharacteristic())
                            .orElse(List.of())
                            .stream()
                            .filter(pc -> FIELD_NAME_CONTRACT_MANAGEMENT.equals(pc.getName()))
                            .map(CharacteristicVO::getValue)
                            .map(pcv -> objectMapper.convertValue(pcv, ContractManagement.class))
                            .findAny()
                            .orElse(new ContractManagement(true));
                });
    }

    private Mono<OrganizationVO> retrieveOrganization(String organizationId) {
        return apiClient.retrieveOrganization(organizationId, null)
                .onErrorMap(HttpClientResponseException.class, e -> new TMForumException(FailureReason.ORGANIZATION_NOT_FOUND,
                        "Organization %s could not be retrieved from the TM Forum party API.".formatted(organizationId), e))
                .map(response -> {
                    if (response.body() == null) {
                        throw new TMForumException(FailureReason.ORGANIZATION_NOT_FOUND,
                                "Organization %s could not be retrieved from the TM Forum party API, the answer has no body.".formatted(organizationId));
                    }
                    return response.body();
                })
                .switchIfEmpty(Mono.error(() -> new TMForumException(FailureReason.ORGANIZATION_NOT_FOUND,
                        "Organization %s could not be retrieved from the TM Forum party API, the response was empty.".formatted(organizationId))));
    }

    private String getDid(String organizationId, OrganizationVO ovo) {
        String did = getDidFromExternalReference(ovo.getExternalReference())
                .or(() -> getDidFromPartyCharacteristics(ovo.getPartyCharacteristic()))
                .orElseThrow(() -> new TMForumException(FailureReason.ORGANIZATION_DID_MISSING,
                        "Organization %s has no valid DID in an externalReference of type '%s' or a partyCharacteristic '%s'.".formatted(
                                organizationId, EXTERNAL_REFERENCE_IDM_ID, PARTY_CHARACTERISTIC_DID)));
        log.debug("Organization {} has DID {}", organizationId, did);
        return did;
    }

    public boolean hasProviderRole(String role) {
        return PROVIDER_ROLE.equalsIgnoreCase(role);
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
        if (externalReferenceVOList == null) {
            return Optional.empty();
        }
        return externalReferenceVOList.stream()
                .filter(ervo -> EXTERNAL_REFERENCE_IDM_ID.equals(ervo.getExternalReferenceType()))
                .map(ExternalReferenceVO::getName)
                .filter(this::isDid)
                .findFirst();
    }

    private boolean isDid(String id) {
        String[] idParts = id.split(":");
        return idParts.length >= 3 && idParts[0].equals(DID);
    }
}

