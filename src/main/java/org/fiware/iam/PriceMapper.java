package org.fiware.iam;

import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.productcatalog.api.CatalogApiClient;
import org.fiware.iam.tmforum.productcatalog.api.ProductOfferingPriceApiClient;
import org.fiware.iam.tmforum.productcatalog.model.ProductOfferingPriceVO;
import org.fiware.iam.tmforum.quote.model.MoneyVO;
import org.fiware.iam.tmforum.quote.model.QuotePriceVO;
import org.fiware.rainbow.model.ObligationVO;
import reactor.core.publisher.Mono;

import java.util.*;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class PriceMapper {

	private final ProductOfferingPriceApiClient productOfferingPriceApiClient;

	public static final String PAYMENT_ACTION = "odrl:use";
	private static final String PAY_AMOUNT_OPERATOR = "odrl:payAmount";
	private static final String ODRL_OPERATOR = "odrl:operator";
	private static final String ODRL_EQ = "odrl:eq";
	private static final String ODRL_RIGHT_OPERAND = "odrl:rightOperand";
	private static final String ODRL_LEFT_OPERAND = "odrl:leftOperand";
	private static final String ODRL_UNIT = "odrl:unit";
	private static final String ODRL_ELAPSED_TIME = "odrl:elapsedTime";
	private static final String VALUE_KEY = "@value";
	private static final String TYPE_KEY = "@type";
	private static final String DECIMAL_TYPE = "xsd:decimal";
	private static final String DURATION_TYPE = "xsd:duration";


	private static final String PRICE_TYPE_RECURRING = "recurring";
	private static final String PRICE_TYPE_ONE_TIME = "oneTime";

	public Mono<List<Object>> toObligationConstraints(QuotePriceVO priceVO) {

		List<Object> constraints = new ArrayList<>();

		if (priceVO.getPrice() != null) {
			MoneyVO moneyVO = priceVO.getPrice().getTaxIncludedAmount();
			constraints.add(getPaymentConstraint(moneyVO.getValue(), moneyVO.getUnit()));
		}

		if (priceVO.getPriceAlteration() != null && !priceVO.getPriceAlteration().isEmpty()) {
			log.warn("Price alteration not supported at the moment.");
		}
		if (priceVO.getProductOfferingPrice() != null) {
			return productOfferingPriceApiClient
					.retrieveProductOfferingPrice(priceVO.getProductOfferingPrice().getId(), null)
					.map(HttpResponse::body)
					.map(pop -> {
						List<Object> popConstraint = new ArrayList<>();
						getPeriod(pop).ifPresent(popConstraint::add);
						popConstraint.add(getAmount(pop));
						return popConstraint;
					})
					.map(pcs -> {
						pcs.addAll(constraints);
						return pcs;
					})
					.onErrorMap(t -> new TMForumException(String.format("Was not able to retrieve the offeringPrice %s.", priceVO.getProductOfferingPrice().getId()), t));
		}

		return Mono.just(constraints);
	}

	private Map<String, Object> getAmount(ProductOfferingPriceVO pop) {
		return getPaymentConstraint(pop.getPrice().getValue(), pop.getPrice().getUnit());
	}

	private Optional<Map<String, Object>> getPeriod(ProductOfferingPriceVO pop) {
		return switch (pop.getPriceType()) {
			case PRICE_TYPE_RECURRING -> Optional.of(Map.of(ODRL_LEFT_OPERAND, ODRL_ELAPSED_TIME,
					ODRL_OPERATOR, ODRL_EQ,
					ODRL_RIGHT_OPERAND, Map.of(TYPE_KEY, DURATION_TYPE, VALUE_KEY, getChargePeriod(pop))));
			case PRICE_TYPE_ONE_TIME -> Optional.empty();
			default ->
					throw new TMForumException(String.format("Price type is not supported: %s.", pop.getPriceType()));
		};
	}

	private String getChargePeriod(ProductOfferingPriceVO priceVO) {
		String typeIndicator = switch (priceVO.getRecurringChargePeriodType()) {
			case "monthly", "month" -> "M";
			case "weekly", "week" -> "W";
			default ->
					throw new TMForumException(String.format("Charge period type is not supported: %s.", priceVO.getRecurringChargePeriodType()));
		};

		return String.format("P%s%s", priceVO.getRecurringChargePeriodLength(), typeIndicator);
	}

	private Map<String, Object> getPaymentConstraint(Float amount, String unit) {
		return Map.of(ODRL_LEFT_OPERAND, PAY_AMOUNT_OPERATOR,
				ODRL_OPERATOR, ODRL_EQ,
				ODRL_RIGHT_OPERAND, Map.of(VALUE_KEY, amount.toString(), TYPE_KEY, DECIMAL_TYPE));
	}
}
