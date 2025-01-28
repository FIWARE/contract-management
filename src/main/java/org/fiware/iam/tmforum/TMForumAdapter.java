package org.fiware.iam.tmforum;

import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.agreement.api.AgreementApiClient;
import org.fiware.iam.tmforum.agreement.model.*;
import org.fiware.iam.tmforum.productorder.api.ProductOrderApiClient;
import org.fiware.iam.tmforum.productorder.model.AgreementRefVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderUpdateVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Adapter to handle communication with TMForum APIs.
 */
@Singleton
@RequiredArgsConstructor
@Slf4j
public class TMForumAdapter {

	public static final String DATA_SPACE_PROTOCOL_AGREEMENT_ID = "Data-Space-Protocol-Agreement-Id";

	private final ProductOrderApiClient productOrderApiClient;
	private final AgreementApiClient agreementApiClient;

	/**
	 * Create a TMForum Agreement and connect it with product order its coming from.
	 */
	public Mono<String> createAgreement(String productOrderId, String productOfferingId, String agreementId, List<RelatedPartyTmfVO> relatedParties) {
		AgreementItemTmfVO agreementItemTmfVO = new AgreementItemTmfVO()
				.addProductItem(
						new ProductRefTmfVO()
								.id(productOrderId))
				.addProductOfferingItem(
						new ProductOfferingRefTmfVO()
								.id(productOfferingId));
		CharacteristicTmfVO characteristicTmfVO = new CharacteristicTmfVO()
				.name(DATA_SPACE_PROTOCOL_AGREEMENT_ID)
				.value(agreementId);
		AgreementCreateTmfVO agreementCreateTmfVO = new AgreementCreateTmfVO()
				.characteristic(List.of(characteristicTmfVO))
				.engagedParty(relatedParties)
				// prevent empty refs
				.agreementSpecification(null)
				.addAgreementItemItem(agreementItemTmfVO);

		return agreementApiClient
				.createAgreement(agreementCreateTmfVO)
				.onErrorMap(t -> {
					throw new TMForumException("Was not able to create agreement", t);
				})
				.map(HttpResponse::body)
				.map(AgreementTmfVO::getId);
	}

	/**
	 * Add the id of agreements(from rainbow) to the given product order
	 */
	public Mono<HttpResponse<ProductOrderVO>> addAgreementToOrder(String productOrderId, List<String> agreementIds) {
		List<AgreementRefVO> agreementRefVOS = agreementIds.stream()
				.map(id -> new AgreementRefVO().id(id))
				.toList();
		ProductOrderUpdateVO productOrderUpdateVO = new ProductOrderUpdateVO().agreement(agreementRefVOS);
		return productOrderApiClient
				.patchProductOrder(productOrderId, productOrderUpdateVO)
				.onErrorMap(t -> new TMForumException("Was not able to update the product order"));
	}

}
