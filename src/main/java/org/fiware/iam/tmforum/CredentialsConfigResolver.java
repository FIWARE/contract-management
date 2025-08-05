package org.fiware.iam.tmforum;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.tmforum.productcatalog.api.CatalogApiClient;
import org.fiware.iam.tmforum.productcatalog.api.ProductOfferingApiClient;
import org.fiware.iam.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.iam.tmforum.productcatalog.model.*;
import org.fiware.iam.tmforum.productorder.model.OrderItemActionTypeVO;
import org.fiware.iam.tmforum.productorder.model.ProductOfferingRefVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderItemVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
@Slf4j
@RequiredArgsConstructor
public class CredentialsConfigResolver {

	private String CREDENTIALS_CONFIG_KEY = "credentialsConfiguration";

	private ProductOfferingApiClient productOfferingApiClient;
	private ProductSpecificationApiClient productSpecificationApiClient;
	private ObjectMapper objectMapper;

	public Mono<List<CredentialsVO>> getCredentialsConfig(ProductOrderVO productOrder) {
		List<Mono<List<CredentialsVO>>> credentialsVOMonoList = productOrder.getProductOrderItem()
				.stream()
				.filter(poi -> poi.getAction() == OrderItemActionTypeVO.ADD || poi.getAction() == OrderItemActionTypeVO.MODIFY)
				.map(ProductOrderItemVO::getProductOffering)
				.map(ProductOfferingRefVO::getId)
				.map(poId -> productOfferingApiClient
						.retrieveProductOffering(poId, null)
						.map(HttpResponse::body)
						.map(ProductOfferingVO::getProductSpecification)
						.map(ProductSpecificationRefVO::getId)
						.flatMap(specId -> productSpecificationApiClient.retrieveProductSpecification(specId, null))
						.map(HttpResponse::body)
						.map(ProductSpecificationVO::getProductSpecCharacteristic)
						.map(this::getCredentialsConfigFromPSC))
				.toList();
		return Mono.zip(credentialsVOMonoList, results -> Stream.of(results).map(r -> (List<CredentialsVO>) r).flatMap(List::stream).toList());
	}


	private List<CredentialsVO> getCredentialsConfigFromPSC(List<ProductSpecificationCharacteristicVO> pscList) {
		return pscList.stream()
				.filter(psc -> psc.getName().equals(CREDENTIALS_CONFIG_KEY))
				.findFirst()
				.map(productSpecificationCharacteristicVO -> productSpecificationCharacteristicVO
						.getProductSpecCharacteristicValue()
						.stream()
						.map(CharacteristicValueSpecificationVO::getValue)
						.map(value -> {
							try {
								return objectMapper.convertValue(value, new TypeReference<List<CredentialsVO>>() {
								});
							} catch (IllegalArgumentException iae) {
								log.warn("The characteristic value is invalid.", iae);
								return null;
							}
						})
						.filter(Objects::nonNull)
						.flatMap(List::stream)
						.toList()).orElseGet(List::of);
	}
}
