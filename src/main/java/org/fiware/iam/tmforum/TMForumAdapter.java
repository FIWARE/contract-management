package org.fiware.iam.tmforum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.exception.RainbowException;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.agreement.api.AgreementApiClient;
import org.fiware.iam.tmforum.agreement.model.*;
import org.fiware.iam.tmforum.productorder.api.ProductOrderApiClient;
import org.fiware.iam.tmforum.productorder.model.AgreementRefVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderUpdateVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import org.fiware.iam.tmforum.quote.api.QuoteApiClient;
import org.fiware.iam.tmforum.quote.model.QuoteUpdateVO;
import org.fiware.iam.tmforum.quote.model.QuoteVO;
import org.fiware.rainbow.model.NegotiationProcessVO;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

/**
 * Adapter to handle communication with TMForum APIs.
 */
@Singleton
@RequiredArgsConstructor
@Slf4j
public class TMForumAdapter {

	public static final String DATA_SPACE_PROTOCOL_AGREEMENT_ID = "Data-Space-Protocol-Agreement-Id";

	private final ObjectMapper objectMapper;

	private final ProductOrderApiClient productOrderApiClient;
	private final AgreementApiClient agreementApiClient;
	private final QuoteApiClient quoteApiClient;

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
				.map(HttpResponse::body)
				.map(AgreementTmfVO::getId)
				.onErrorMap(t -> {
					throw new TMForumException("Was not able to create agreement", t);
				});
	}


	/**
	 * Add the id of agreements(from rainbow) to the given product order
	 */
	public Mono<ProductOrderVO> addAgreementToOrder(String productOrderId, List<String> agreementIds) {
		List<AgreementRefVO> agreementRefVOS = agreementIds.stream()
				.map(id -> new AgreementRefVO().id(id))
				.toList();
		ProductOrderUpdateVO productOrderUpdateVO = new ProductOrderUpdateVO().agreement(agreementRefVOS);
		return productOrderApiClient
				.patchProductOrder(productOrderId, productOrderUpdateVO)
				.map(HttpResponse::body)
				.onErrorMap(t -> new TMForumException("Was not able to update the product order"));
	}

	/**
	 * Update the externalId of a quote.
	 */
	public Mono<QuoteVO> updateExternalId(QuoteVO quoteVO, String externalId) {
		QuoteUpdateVO quoteUpdateVO = objectMapper.convertValue(quoteVO.externalId(externalId), QuoteUpdateVO.class);
		return quoteApiClient.patchQuote(quoteVO.getId(), quoteUpdateVO)
				.onErrorMap(t -> new TMForumException(String.format("Was not able to update the quote %s.", quoteVO.getId()), t))
				.map(HttpResponse::body);


	}

	/**
	 * Return the quote with the given id.
	 */
	public Mono<QuoteVO> getQuoteById(String id) {
		return quoteApiClient.retrieveQuote(id, null)
				.onErrorMap(t -> {
					throw new TMForumException(String.format("Was not able to get the quote %s.", id), t);
				})
				.map(HttpResponse::body);
	}

}
