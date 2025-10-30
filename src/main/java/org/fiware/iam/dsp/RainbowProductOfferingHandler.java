package org.fiware.iam.dsp;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.handlers.ProductOfferingHandler;
import org.fiware.iam.tmforum.ProductOfferingConstants;
import org.fiware.iam.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.iam.tmforum.productcatalog.model.*;
import org.fiware.rainbow.api.CatalogApiClient;
import org.fiware.rainbow.model.CatalogVO;
import org.fiware.rainbow.model.DataServiceVO;
import org.fiware.rainbow.model.NewDataserviceVO;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;


/**
 * Handler to manage product offerings and exchange them with Rainbow.
 */
@Requires(condition = GeneralProperties.RainbowCondition.class)
@RequiredArgsConstructor
@Singleton
@Slf4j
public class RainbowProductOfferingHandler implements ProductOfferingHandler {

    private static final String EMPTY_STRING_MARKER = "Empty";
    private static final String OWNER_ROLE = "Owner";

    private final CatalogApiClient rainbowCatalogApiClient;
    private final org.fiware.iam.tmforum.productcatalog.api.CatalogApiClient catalogApiClient;
    private final ProductSpecificationApiClient productSpecificationApiClient;

    @Override
    public Mono<HttpResponse<?>> handleOfferingCreation(ProductOfferingVO productOfferingVO) {

        Mono<NewDataserviceVO> dataserviceVOMono = prepareNewDataservice(productOfferingVO);
        Mono<List<String>> catalogsMono = getCatalogsForProductOffering(productOfferingVO);
        return Mono.zip(dataserviceVOMono, catalogsMono, this::createDataservice)
                        .flatMap(Function.identity());
    }

    @Override
    public Mono<HttpResponse<?>> handleOfferingStateChange(ProductOfferingVO productOfferingVO) {
        // find catalogs that the offering should be in
        Mono<List<String>> targetCatalogs = getCatalogsForProductOffering(productOfferingVO);

        // (rainbow) catalogs that the offering is currently included
        Mono<List<String>> currentCatalogs = rainbowCatalogApiClient.getCatalogs()
                .map(HttpResponse::body)
                .map(catalogVOS -> catalogVOS.stream().map(CatalogVO::getAtId).toList());

        return Mono.zipDelayError(targetCatalogs, currentCatalogs)
                .flatMap(tuple -> handleCatalogEntries(tuple.getT1(), tuple.getT2(), productOfferingVO));
    }

    @Override
    public Mono<HttpResponse<?>> handleOfferingDeletion(ProductOfferingVO productOfferingVO) {
        return rainbowCatalogApiClient.getCatalogs()
                .map(HttpResponse::body)
                .flatMap(catalogVOS ->
                        Mono.zipDelayError(
                                catalogVOS.stream()
                                        .filter(catalogVO ->
                                                catalogVO.getDcatColonService()
                                                        .stream()
                                                        .map(DataServiceVO::getAtId)
                                                        .anyMatch(id -> id.equals(productOfferingVO.getId()))
                                        )
                                        .map(catalogVO -> rainbowCatalogApiClient
                                                .deleteDataserviceInCatalog(catalogVO.getAtId(), productOfferingVO.getId())

                                        ).toList(),
                                responses -> HttpResponse.noContent())
                );
    }

    private Optional<String> getCharValue(List<CharacteristicValueSpecificationVO> specs) {
        if (specs == null || specs.isEmpty()) {
            return Optional.empty();
        }
        if (specs.size() == 1 && specs.get(0).getValue() instanceof String stringValue) {
            return Optional.of(stringValue);
        }
        return specs.stream()
                .filter(cvs -> Optional.ofNullable(cvs.getIsDefault()).orElse(false))
                .findFirst()
                .map(CharacteristicValueSpecificationVO::getValue)
                .filter(String.class::isInstance)
                .map(String.class::cast);
    }

    private void setEndpoint(ProductSpecificationVO spec, NewDataserviceVO newDataserviceVO) {
        if (spec.getProductSpecCharacteristic() != null && !spec.getProductSpecCharacteristic().isEmpty()) {
            spec.getProductSpecCharacteristic().forEach(psc -> {
                if (psc.getValueType().equals(ProductOfferingConstants.ENDPOINT_URL_TYPE)) {
                    getCharValue(psc.getProductSpecCharacteristicValue())
                            .ifPresent(newDataserviceVO::dcatColonEndpointURL);

                } else if (psc.getValueType().equals(ProductOfferingConstants.ENDPOINT_DESCRIPTION_TYPE)) {
                    getCharValue(psc.getProductSpecCharacteristicValue())
                            .ifPresent(newDataserviceVO::dcatColonEndpointDescription);
                }
            });
        }
    }

    private void setRelatedParty(ProductSpecificationVO spec, NewDataserviceVO newDataserviceVO) {
        if (spec.getRelatedParty() != null && !spec.getRelatedParty().isEmpty()) {
            spec.getRelatedParty().stream()
                    .filter(rp -> rp.getRole().equals(OWNER_ROLE))
                    .map(RelatedPartyVO::getId)
                    .findFirst()
                    .ifPresent(newDataserviceVO::dctColonCreator);
        }
    }

    private Mono<HttpResponse<?>> handleCatalogEntries(List<String> targetCatalogs, List<String> currentCatalogs, ProductOfferingVO productOfferingVO) {
        List<String> newCatalogs = new ArrayList<>();
        List<String> updateCatalogs = new ArrayList<>();
        targetCatalogs.stream().forEach(targetCatalog -> {
            if (currentCatalogs.contains(targetCatalog)) {
                updateCatalogs.add(targetCatalog);
            } else {
                newCatalogs.add(targetCatalog);
            }
        });
        List<String> deleteCatalogs = currentCatalogs.stream().filter(currentCatalog -> !targetCatalogs.contains(currentCatalog)).toList();

        List<Mono<HttpResponse<?>>> offeringMonos = new ArrayList<>();
        offeringMonos.add(
                Mono.zipDelayError(deleteCatalogs.stream()
                        .map(catalogId -> rainbowCatalogApiClient.deleteDataserviceInCatalog(catalogId, productOfferingVO.getId()))
                        .toList(), r -> HttpResponse.accepted()));

        if (!newCatalogs.isEmpty() || !updateCatalogs.isEmpty()) {
            Mono<NewDataserviceVO> newDataservice = prepareNewDataservice(productOfferingVO);
            offeringMonos.add(newDataservice.flatMap(dataserviceVO -> {
                List<Mono<HttpResponse<?>>> rainbowResponses = new ArrayList<>();
                if (!newCatalogs.isEmpty()) {
                    rainbowResponses.add(createDataservice(dataserviceVO, newCatalogs));
                }
                if (!updateCatalogs.isEmpty()) {
                    rainbowResponses.add(updateDataservice(dataserviceVO, updateCatalogs));
                }
                return Mono.zipDelayError(rainbowResponses, r -> HttpResponse.accepted());
            }));
        }
        return Mono.zipDelayError(offeringMonos, r -> HttpResponse.accepted());
    }

    private Mono<HttpResponse<?>> updateDataservice(NewDataserviceVO dataserviceVO, List<String> catalogs) {
        return Mono.zip(catalogs.stream()
                        .map(id -> rainbowCatalogApiClient
                                .updateDataserviceInCatalog(id, dataserviceVO.getAtId(), dataserviceVO)
                                .onErrorMap(t ->
                                        new IllegalArgumentException("Was not able to update dataservice %s in %s".formatted(dataserviceVO, id), t)))
                        .toList(),
                responses -> Arrays.stream(responses)
                        .map(HttpResponse.class::cast)
                        .filter(resp -> resp.getStatus() != HttpStatus.ACCEPTED)
                        .findAny()
                        .map(r -> HttpResponse.status(HttpStatus.BAD_GATEWAY))
                        .orElse(HttpResponse.ok()));

    }

    private Mono<NewDataserviceVO> prepareNewDataservice(ProductOfferingVO productOfferingVO) {
        NewDataserviceVO newDataserviceVO = new NewDataserviceVO().atId(productOfferingVO.getId());
        return getSpecForOffering(productOfferingVO)
                .map(spec -> {
                    newDataserviceVO.dctColonTitle(spec.getName());
                    setRelatedParty(spec, newDataserviceVO);
                    setEndpoint(spec, newDataserviceVO);
                    return newDataserviceVO;
                });
    }

    private Mono<ProductSpecificationVO> getSpecForOffering(ProductOfferingVO productOfferingVO) {
        return productSpecificationApiClient.retrieveProductSpecification(productOfferingVO.getProductSpecification().getId(), null)
                .map(HttpResponse::body);
    }

    private Mono<List<String>> getCatalogsForProductOffering(ProductOfferingVO productOfferingVO) {
        List<String> categoryIds = productOfferingVO.getCategory().stream().map(CategoryRefVO::getId).toList();

        return rainbowCatalogApiClient.getCatalogs()
                .map(HttpResponse::body)
                .flatMap(catalogList ->
                        Mono.zip(catalogList.stream()
                                        .map(CatalogVO::getAtId)
                                        .map(id -> catalogApiClient.retrieveCatalog(id, null)
                                                .map(HttpResponse::body)
                                                .filter(cvo -> cvo.getCategory().stream()
                                                        .map(CategoryRefVO::getId)
                                                        .anyMatch(categoryIds::contains)
                                                )
                                                .map(org.fiware.iam.tmforum.productcatalog.model.CatalogVO::getId)
                                                // prevent empty monos, because they will terminate the zip
                                                .defaultIfEmpty(EMPTY_STRING_MARKER)
                                        )
                                        .toList(),
                                ids -> Arrays.stream(ids)
                                        .filter(String.class::isInstance)
                                        .map(String.class::cast)
                                        .filter(id -> !id.equals(EMPTY_STRING_MARKER))
                                        .toList())
                );
    }


    private Mono<HttpResponse<?>> createDataservice(NewDataserviceVO dataserviceVO, List<String> catalogs) {
        return Mono.zip(catalogs.stream()
                        .map(id -> rainbowCatalogApiClient
                                .createDataserviceInCatalog(id, dataserviceVO)
                                .onErrorMap(t ->
                                        new IllegalArgumentException("Was not able to create dataservice %s".formatted(dataserviceVO), t)))
                        .toList(),
                responses -> Arrays.stream(responses)
                        .map(HttpResponse.class::cast)
                        .filter(resp -> resp.getStatus() != HttpStatus.CREATED)
                        .findAny()
                        .map(r -> HttpResponse.status(HttpStatus.BAD_GATEWAY))
                        .orElse(HttpResponse.ok()));

    }
}
