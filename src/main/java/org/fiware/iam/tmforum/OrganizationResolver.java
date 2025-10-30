package org.fiware.iam.tmforum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.domain.ContractManagement;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.party.api.OrganizationApiClient;
import org.fiware.iam.tmforum.party.model.CharacteristicVO;
import org.fiware.iam.tmforum.party.model.ExternalReferenceVO;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

@Requires(condition = GeneralProperties.TmForumCondition.class)
@Singleton
@Slf4j
@RequiredArgsConstructor
public class OrganizationResolver {

    public static final String PROVIDER_ROLE = "provider";

    private static final String PARTY_CHARACTERISTIC_DID = "did";
    private static final String FIELD_NAME_CONTRACT_MANAGEMENT = "contractManagement";
    private static final String EXTERNAL_REFERENCE_IDM_ID = "idm_id";
    private static final String DID = "did";

    private final GeneralProperties generalProperties;
    private final ObjectMapper objectMapper;
    private final OrganizationApiClient apiClient;

    //TODO Cache me if you can
    public Mono<String> getDID(String organizationId) {
        return apiClient.retrieveOrganization(organizationId, null)
                .filter(response -> response.getStatus().equals(HttpStatus.OK))
                .map(HttpResponse::body)
                .map(ovo -> {
                            String did = getDidFromExternalReference(ovo.getExternalReference())
                                    .or(() -> getDidFromPartyCharacteristics(ovo.getPartyCharacteristic()))
                                    .orElseThrow(() -> new TMForumException("Could not find organizations DID (%s) in response.".formatted(organizationId)));
                            log.debug("Did is {}", did);
                            return did;
                        }
                );
    }

    public Mono<ContractManagement> getContractManagement(String organizationId) {
        return getDID(organizationId)
                .flatMap(did -> {
                    if (did.equals(generalProperties.getDid())) {
                        return Mono.just(new ContractManagement(true));
                    } else {
                        return apiClient.retrieveOrganization(organizationId, null)
                                .filter(response -> response.getStatus().equals(HttpStatus.OK))
                                .map(HttpResponse::body)
                                .map(ovo ->
                                        ovo.getPartyCharacteristic()
                                                .stream()
                                                .filter(pc -> pc.getName().equals(FIELD_NAME_CONTRACT_MANAGEMENT))
                                                .map(CharacteristicVO::getValue)
                                                .map(pcv -> objectMapper.convertValue(pcv, ContractManagement.class))
                                                .findAny()
                                                .orElse(new ContractManagement(true))
                                );
                    }
                });
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

