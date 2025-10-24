package org.fiware.iam.tmforum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.dsp.OfferingParameters;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.agreement.api.AgreementApiClient;
import org.fiware.iam.tmforum.agreement.model.*;
import org.fiware.iam.tmforum.productcatalog.api.ProductOfferingApiClient;
import org.fiware.iam.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.iam.tmforum.productcatalog.model.*;
import org.fiware.iam.tmforum.productorder.api.ProductOrderApiClient;
import org.fiware.iam.tmforum.productorder.model.AgreementRefVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderUpdateVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import org.fiware.iam.tmforum.quote.api.QuoteApiClient;
import org.fiware.iam.tmforum.quote.model.QuoteUpdateVO;
import org.fiware.iam.tmforum.quote.model.QuoteVO;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Adapter to handle communication with TMForum APIs.
 */
@Requires(condition = GeneralProperties.TmForumCondition.class)
@Singleton
@RequiredArgsConstructor
@Slf4j
public class TMForumAdapter {

    public static final String DATA_SPACE_PROTOCOL_AGREEMENT_ID = "Data-Space-Protocol-Agreement-Id";
    public static final String CONSUMER_ROLE = "Consumer";

    private final ObjectMapper objectMapper;

    private final OrganizationResolver organizationResolver;
    private final ProductOrderApiClient productOrderApiClient;
    private final ProductOfferingApiClient productOfferingApiClient;
    private final ProductSpecificationApiClient productSpecificationApiClient;
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
                    log.warn("Was not able to create aggreement", t);
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
                .onErrorMap(t -> {
                    log.warn("Was not able to update the product order", t);
                    throw new TMForumException("Was not able to update the product order");
                });
    }

    /**
     * Update the externalId of a quote.
     */
    public Mono<QuoteVO> updateExternalId(QuoteVO quoteVO, String externalId) {
        QuoteUpdateVO quoteUpdateVO = objectMapper.convertValue(quoteVO.externalId(externalId), QuoteUpdateVO.class);
        // remove the quote object
        quoteUpdateVO.setUnknownProperties("id", null);
        quoteUpdateVO.setUnknownProperties("href", null);
        quoteUpdateVO.setUnknownProperties("quoteDate", null);

        return quoteApiClient.patchQuote(quoteVO.getId(), quoteUpdateVO)
                .onErrorMap(t -> {
                    log.warn("Was not able to update the quote", t);
                    throw new TMForumException(String.format("Was not able to update the quote %s.", quoteVO.getId()), t);
                })
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

    public Mono<ProductSpecificationVO> getSpecFromOfferRef(String refId) {
        return productOfferingApiClient.retrieveProductOffering(refId, null)
                .onErrorMap(t -> new TMForumException(String.format("Was not able to retrieve offering %s", refId), t))
                .map(HttpResponse::body)
                .map(ProductOfferingVO::getProductSpecification)
                .map(ProductSpecificationRefVO::getId)
                .flatMap(id -> productSpecificationApiClient.retrieveProductSpecification(id, null))
                .onErrorMap(t -> new TMForumException(String.format("Was not able to retrieve specification for offering %s", refId), t))
                .map(HttpResponse::body);
    }


    public Mono<String> getConsumerDid(QuoteVO quoteVO) {
        return organizationResolver.getDID(getConsumerIdFromQuote(quoteVO));
    }


    public String getConsumerIdFromQuote(QuoteVO quoteVO) {
        if (quoteVO.getRelatedParty() == null || quoteVO.getRelatedParty().isEmpty()) {
            throw new TMForumException(String.format("Quote %s does not have valid consumer.", quoteVO.getId()));
        }
        return quoteVO.getRelatedParty()
                .stream()
                .filter(rp -> rp.getRole().equals(CONSUMER_ROLE))
                .findFirst()
                .map(org.fiware.iam.tmforum.quote.model.RelatedPartyVO::getId)
                .orElseThrow(() -> new TMForumException(String.format("Quote %s does not have valid consumer.", quoteVO.getId())));
    }

    public Mono<OfferingParameters> getOfferingParameters(String offeringId) {
        return getSpecFromOfferRef(offeringId)
                .map(spec -> fromProductSpecChars(spec.getProductSpecCharacteristic()));
    }

    private OfferingParameters fromProductSpecChars(List<ProductSpecificationCharacteristicVO> specChars) {
        String target = "";
        String action = "";

        for (ProductSpecificationCharacteristicVO specChar : specChars) {
            if (specChar.getValueType().equals(ProductOfferingConstants.ENDPOINT_URL_TYPE)) {
                target = specChar.getProductSpecCharacteristicValue()
                        .stream()
                        .filter(CharacteristicValueSpecificationVO::getIsDefault)
                        .map(CharacteristicValueSpecificationVO::getValue)
                        .map(String.class::cast)
                        .findAny()
                        .orElseThrow(() -> new TMForumException("Was not able to retrieve endpoint from spec."));
                continue;
            }
            if (specChar.getValueType().equals(ProductOfferingConstants.ALLOWED_ACTION_TYPE)) {
                action = specChar.getProductSpecCharacteristicValue()
                        .stream()
                        .filter(CharacteristicValueSpecificationVO::getIsDefault)
                        .map(CharacteristicValueSpecificationVO::getValue)
                        .map(String.class::cast)
                        .findAny()
                        .orElseThrow(() -> new TMForumException("Was not able to retrieve action from spec."));
            }
        }
        if (action.isEmpty() || target.isEmpty()) {
            throw new TMForumException(String.format("Was not able to get valid action %s and/or target %s.", action, target));
        }
        return new OfferingParameters(target, action);
    }


}
