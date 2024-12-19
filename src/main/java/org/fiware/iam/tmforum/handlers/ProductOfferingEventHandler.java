package org.fiware.iam.tmforum.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.RainbowMapper;
import org.fiware.iam.tmforum.productcatalog.api.CategoryApiClient;
import org.fiware.iam.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.iam.tmforum.productcatalog.model.*;
import org.fiware.rainbow.api.CatalogApiClient;
import org.fiware.rainbow.model.CatalogVO;
import org.fiware.rainbow.model.DataServiceVO;
import org.fiware.rainbow.model.NewDataserviceVO;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Function;

@RequiredArgsConstructor
@Singleton
@Slf4j
public class ProductOfferingEventHandler implements EventHandler {


	private static final String CREATE_EVENT = "ProductOfferingCreateEvent";
	private static final String DELETE_EVENT = "ProductOfferingDeleteEvent";
	private static final String STATE_CHANGE_EVENT = "ProductOfferingStateChangeEvent";

	private static final List<String> SUPPORTED_EVENT_TYPES = List.of(CREATE_EVENT, DELETE_EVENT, STATE_CHANGE_EVENT);

	public static final String EMPTY_STRING_MARKER = "Empty";
	public static final String OWNER_ROLE = "Owner";
	public static final String ENDPOINT_URL_TYPE = "endpointUrl";
	public static final String ENDPOINT_DESCRIPTION_TYPE = "endpointDescription";

	private final CatalogApiClient rainbowCatalogApiClient;
	private final ProductSpecificationApiClient productSpecificationApiClient;
	private final org.fiware.iam.tmforum.productcatalog.api.CatalogApiClient catalogApiClient;
	private final ObjectMapper objectMapper;

	@Override
	public boolean isEventTypeSupported(String eventType) {
		return SUPPORTED_EVENT_TYPES.contains(eventType);
	}

	@Override
	public Mono<HttpResponse<?>> handleEvent(String eventType, Map<String, Object> event) {
		return switch (eventType) {
			case CREATE_EVENT -> handleOfferingCreation(event);
			case STATE_CHANGE_EVENT -> handleOfferingStateChange(event);
			case DELETE_EVENT -> handleOfferingDeletion(event);
			default -> throw new IllegalArgumentException("Even type %s is not supported.".formatted(eventType));
		};
	}

	private Mono<HttpResponse<?>> handleOfferingCreation(Map<String, Object> event) {
		ProductOfferingCreateEventVO productOfferingCreateEventVO = objectMapper.convertValue(event, ProductOfferingCreateEventVO.class);
		ProductOfferingVO productOfferingVO = Optional.ofNullable(productOfferingCreateEventVO.getEvent())
				.map(ProductOfferingCreateEventPayloadVO::getProductOffering)
				.orElseThrow(() -> new IllegalArgumentException("The event does not contain a product offering."));

		if (productOfferingVO.getCategory() == null || productOfferingVO.getCategory().isEmpty()) {
			throw new IllegalArgumentException("Product offering does not have a category.");
		}

		Mono<NewDataserviceVO> dataserviceVOMono = prepareNewDataservice(productOfferingVO);
		Mono<List<String>> catalogsMono = getCatalogsForProductOffering(productOfferingVO);
		return Mono.zip(dataserviceVOMono, catalogsMono, (dataservice, idList) -> createDataservice(dataservice, idList)).
				flatMap(Function.identity());
	}

	private Mono<HttpResponse<?>> handleOfferingStateChange(Map<String, Object> event) {
		ProductOfferingStateChangeEventVO productOfferingStateChangeEventVO = objectMapper.convertValue(event, ProductOfferingStateChangeEventVO.class);
		ProductOfferingVO productOfferingVO = Optional.ofNullable(productOfferingStateChangeEventVO.getEvent())
				.map(ProductOfferingStateChangeEventPayloadVO::getProductOffering)
				.orElseThrow(() -> new IllegalArgumentException("The event does not contain a product offering."));

		if (productOfferingVO.getCategory() == null || productOfferingVO.getCategory().isEmpty()) {
			// if no category is included, the offering is not part of a catalog anymore
			return deleteOffering(productOfferingVO);
		}
		// find catalogs that the offering should be in
		Mono<List<String>> targetCatalogs = getCatalogsForProductOffering(productOfferingVO);

		// (rainbow) catalogs that the offering is currently included
		Mono<List<String>> currentCatalogs = rainbowCatalogApiClient.getCatalogs()
				.map(HttpResponse::body)
				.map(catalogVOS -> catalogVOS.stream().map(CatalogVO::getAtId).toList());

		return Mono.zipDelayError(targetCatalogs, currentCatalogs)
				.flatMap(tuple -> handleCatalogEntries(tuple.getT1(), tuple.getT2(), productOfferingVO));
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

	private Mono<ProductSpecificationVO> getSpecForOffering(ProductOfferingVO productOfferingVO) {
		return productSpecificationApiClient.retrieveProductSpecification(productOfferingVO.getProductSpecification().getId(), null)
				.map(HttpResponse::body);
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

	private static void setEndpoint(ProductSpecificationVO spec, NewDataserviceVO newDataserviceVO) {
		if (spec.getProductSpecCharacteristic() != null && !spec.getProductSpecCharacteristic().isEmpty()) {
			spec.getProductSpecCharacteristic().forEach(psc -> {
				if (psc.getValueType().equals(ENDPOINT_URL_TYPE)) {
					getCharValue(psc.getProductSpecCharacteristicValue())
							.ifPresent(newDataserviceVO::dcatColonEndpointURL);

				} else if (psc.getValueType().equals(ENDPOINT_DESCRIPTION_TYPE)) {
					getCharValue(psc.getProductSpecCharacteristicValue())
							.ifPresent(newDataserviceVO::dcatColonEndpointDescription);
				}
			});
		}
	}

	private static Optional<String> getCharValue(List<CharacteristicValueSpecificationVO> specs) {
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

	private static void setRelatedParty(ProductSpecificationVO spec, NewDataserviceVO newDataserviceVO) {
		if (spec.getRelatedParty() != null && !spec.getRelatedParty().isEmpty()) {
			spec.getRelatedParty().stream()
					.filter(rp -> rp.getRole().equals(OWNER_ROLE))
					.map(RelatedPartyVO::getId)
					.findFirst()
					.ifPresent(newDataserviceVO::dctColonCreator);
		}
	}


	private Mono<HttpResponse<?>> handleOfferingDeletion(Map<String, Object> event) {

		ProductOfferingDeleteEventVO productOfferingDeleteEventVO = objectMapper.convertValue(event, ProductOfferingDeleteEventVO.class);
		ProductOfferingVO productOfferingVO = Optional.ofNullable(productOfferingDeleteEventVO.getEvent())
				.map(ProductOfferingDeleteEventPayloadVO::getProductOffering)
				.orElseThrow(() -> new IllegalArgumentException("The event does not contain a product offering."));

		return deleteOffering(productOfferingVO);

	}

	private Mono<HttpResponse<?>> deleteOffering(ProductOfferingVO productOfferingVO) {
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

}
