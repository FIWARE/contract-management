package org.fiware.iam.tmforum.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.RainbowMapper;
import org.fiware.iam.tmforum.productcatalog.api.CategoryApiClient;
import org.fiware.iam.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.iam.tmforum.productcatalog.model.*;
import org.fiware.iam.tmforum.servicecatalog.api.ServiceCatalogApiClient;
import org.fiware.rainbow.api.CatalogApiClient;
import org.fiware.rainbow.model.CatalogVO;
import org.fiware.rainbow.model.NewDataserviceVO;
import reactor.core.publisher.Mono;

import java.util.*;

@RequiredArgsConstructor
@Singleton
@Slf4j
public class ProductOfferingEventHandler implements EventHandler {


	private static final String CREATE_EVENT = "ProductOfferingCreateEvent";
	private static final String DELETE_EVENT = "ProductOfferingDeleteEvent";
	private static final String STATE_CHANGE_EVENT = "ProductOfferingStateChangeEvent";

	private static final List<String> SUPPORTED_EVENT_TYPES = List.of(CREATE_EVENT, DELETE_EVENT, STATE_CHANGE_EVENT);

	private final CatalogApiClient rainbowCatalogApiClient;
	private final ProductSpecificationApiClient productSpecificationApiClient;
	private final CategoryApiClient categoryApiClient;
	private final org.fiware.iam.tmforum.productcatalog.api.CatalogApiClient catalogApiClient;
	private final ObjectMapper objectMapper;
	private final RainbowMapper rainbowMapper;

	@Override
	public boolean isEventTypeSupported(String eventType) {
		return SUPPORTED_EVENT_TYPES.contains(eventType);
	}

	@Override
	public Mono<HttpResponse<?>> handleEvent(String eventType, Map<String, Object> event) {
		return switch (eventType) {
			case CREATE_EVENT -> handleOfferingCreation(event);
			case STATE_CHANGE_EVENT -> throw new UnsupportedOperationException();
			case DELETE_EVENT -> handleOfferingDeletion(event);
			default -> throw new IllegalArgumentException("Even type %s is not supported.".formatted(eventType));
		};
	}

	private Mono<HttpResponse<?>> handleOfferingCreation(Map<String, Object> event) {
		ProductOfferingCreateEventVO productOfferingCreateEventVO = objectMapper.convertValue(event, ProductOfferingCreateEventVO.class);
		ProductOfferingVO productOfferingVO = Optional.ofNullable(productOfferingCreateEventVO.getEvent())
				.map(ProductOfferingCreateEventPayloadVO::getProductOffering)
				.orElseThrow(() -> new IllegalArgumentException("The event does not contain a product offering."));
		NewDataserviceVO newDataserviceVO = new NewDataserviceVO();
		if (productOfferingVO.getCategory() == null || productOfferingVO.getCategory().isEmpty()) {
			throw new IllegalArgumentException("Product offering does not have a category.");
		}
		productSpecificationApiClient.retrieveProductSpecification(productOfferingVO.getProductSpecification().getId(), null)
				.map(HttpResponse::body)
				.map(spec -> {
					newDataserviceVO
							.dctColonTitle(spec.getName());
					setRelatedParty(spec, newDataserviceVO);
					setEndpoint(spec, newDataserviceVO);
					return newDataserviceVO;
				});

		rainbowCatalogApiClient.getCatalogs()
				.map(HttpResponse::body)
				.flatMap(catalogList -> {
					catalogList.stream()
							.map(CatalogVO::getAtId)
							.map(id -> catalogApiClient.retrieveCatalog(id, null))


				})


				.map(nds -> {
					Mono.zip(productOfferingVO.getCategory()
									.stream()
									.map(category ->
											categoryApiClient.retrieveCategory(category.getId(), null)
													.map(categoryVOHttpResponse -> {
														if (categoryVOHttpResponse.getStatus() == HttpStatus.OK) {
															return categoryVOHttpResponse.body();
														}
														return null;
													})
													.filter(Objects::nonNull)
									).toList(), c1 -> Arrays.stream(c1).toList())
							.map(categoryList -> {
								rainbowCatalogApiClient.getCatalogs()
										.map(HttpResponse::body)
										.map(cl -> cl.stream().)
							})


				})


		return Mono.empty();
	}

	private static void setEndpoint(ProductSpecificationVO spec, NewDataserviceVO newDataserviceVO) {
		if (spec.getProductSpecCharacteristic() != null && !spec.getProductSpecCharacteristic().isEmpty()) {
			spec.getProductSpecCharacteristic()
					.forEach(psc -> {
						if (psc.getValueType().equals("endpointUrl")) {
							psc.getProductSpecCharacteristicValue()
									.stream()
									.filter(CharacteristicValueSpecificationVO::getIsDefault)
									.findFirst()
									.map(CharacteristicValueSpecificationVO::getValue)
									.map(v -> {
										if (v instanceof String stringValue) {
											return stringValue;
										}
										return null;
									})
									.filter(Objects::nonNull)
									.ifPresent(newDataserviceVO::dcatColonEndpointURL);

						} else if (psc.getValueType().equals("endpointDescription")) {
							psc.getProductSpecCharacteristicValue()
									.stream()
									.filter(CharacteristicValueSpecificationVO::getIsDefault)
									.findFirst()
									.map(CharacteristicValueSpecificationVO::getValue)
									.map(v -> {
										if (v instanceof String stringValue) {
											return stringValue;
										}
										return null;
									})
									.filter(Objects::nonNull)
									.ifPresent(newDataserviceVO::dcatColonEndpointDescription);
						}
					});
		}
	}

	private static void setRelatedParty(ProductSpecificationVO spec, NewDataserviceVO newDataserviceVO) {
		if (spec.getRelatedParty() != null && !spec.getRelatedParty().isEmpty()) {
			spec.getRelatedParty()
					.stream()
					.filter(rp -> rp.getRole().equals("Owner"))
					.map(RelatedPartyVO::getId)
					.findFirst()
					.ifPresent(newDataserviceVO::dctColonCreator);
		}
	}

	private


	private Mono<HttpResponse<?>> handleOfferingDeletion(Map<String, Object> event) {
		return Mono.empty();
	}
}
