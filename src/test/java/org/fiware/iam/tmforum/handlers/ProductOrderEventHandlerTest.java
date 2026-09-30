package org.fiware.iam.tmforum.handlers;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TrustedIssuersException;
import org.fiware.iam.handlers.ProductOrderHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ProductOrderEventHandlerTest {

	private static final String ORDER_ID = "urn:ngsi-ld:product-order:the-order";
	private static final String CUSTOMER_ID = "urn:ngsi-ld:organization:the-consumer";
	private static final Map<String, Object> COMPLETED_EVENT = Map.of(
			"eventType", "ProductOrderStateChangeEvent",
			"event", Map.of("productOrder", Map.of(
					"id", ORDER_ID,
					"state", "completed",
					"relatedParty", List.of(Map.of("id", CUSTOMER_ID)))));

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
	private final Logger logger = (Logger) LoggerFactory.getLogger(ProductOrderEventHandler.class);

	private ProductOrderHandler pap;
	private ProductOrderHandler til;
	private ProductOrderEventHandler eventHandler;

	@BeforeEach
	public void prepare() {
		pap = namedHandler("pap");
		til = namedHandler("til");
		ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		eventHandler = new ProductOrderEventHandler(objectMapper, List.of(pap, til));
		logs.start();
		logger.addAppender(logs);
	}

	@AfterEach
	public void cleanUp() {
		logger.detachAppender(logs);
	}

	@Test
	public void aSuccessfulCompletionIsSummarizedPerHandler() {
		when(pap.handleProductOrderComplete(any(), any())).thenReturn(Mono.just(HttpResponse.ok()));
		when(til.handleProductOrderComplete(any(), any())).thenReturn(Mono.empty());

		HttpResponse<?> response = eventHandler.handleEvent("ProductOrderStateChangeEvent", COMPLETED_EVENT).block();

		assertEquals(HttpStatus.NO_CONTENT, response.getStatus());
		ILoggingEvent summary = lastLog();
		assertEquals(Level.INFO, summary.getLevel());
		assertTrue(summary.getFormattedMessage().contains(ORDER_ID), "The order has to be named.");
		assertTrue(summary.getFormattedMessage().contains("pap=OK, til=SKIPPED"), summary.getFormattedMessage());
	}

	@Test
	public void theFailingHandlerAndItsReasonAreNamed() {
		when(pap.handleProductOrderComplete(any(), any())).thenReturn(Mono.just(HttpResponse.ok()));
		when(til.handleProductOrderComplete(any(), any())).thenReturn(Mono.error(
				new TrustedIssuersException(FailureReason.TIL_REJECTED_ISSUER, "The trusted-issuers-list did not allow issuer did:web:x.")));

		assertThrows(TrustedIssuersException.class,
				() -> eventHandler.handleEvent("ProductOrderStateChangeEvent", COMPLETED_EVENT).block(),
				"The failure has to be propagated, so the notification is answered with an error.");

		ILoggingEvent summary = lastLog();
		assertEquals(Level.WARN, summary.getLevel());
		assertTrue(summary.getFormattedMessage().contains(ORDER_ID));
		assertTrue(summary.getFormattedMessage().contains("pap=OK"), summary.getFormattedMessage());
		assertTrue(summary.getFormattedMessage().contains(
				"til=FAILED([til_rejected_issuer] The trusted-issuers-list did not allow issuer did:web:x.)"), summary.getFormattedMessage());
		verify(pap).handleProductOrderComplete(any(), any());
	}

	@Test
	public void aNonSuccessResponseIsABadGateway() {
		when(pap.handleProductOrderComplete(any(), any())).thenReturn(Mono.just(HttpResponse.status(HttpStatus.BAD_GATEWAY)));
		when(til.handleProductOrderComplete(any(), any())).thenReturn(Mono.just(HttpResponse.ok()));

		HttpResponse<?> response = eventHandler.handleEvent("ProductOrderStateChangeEvent", COMPLETED_EVENT).block();

		assertEquals(HttpStatus.BAD_GATEWAY, response.getStatus());
		assertTrue(lastLog().getFormattedMessage().contains("pap=FAILED(status=502), til=OK"), lastLog().getFormattedMessage());
	}

	private ILoggingEvent lastLog() {
		return logs.list.getLast();
	}

	private static ProductOrderHandler namedHandler(String name) {
		ProductOrderHandler handler = mock(ProductOrderHandler.class);
		when(handler.getName()).thenReturn(name);
		return handler;
	}
}
