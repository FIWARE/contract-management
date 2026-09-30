package org.fiware.iam.tmforum.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.handlers.ProductOrderHandler;
import org.fiware.iam.logging.DownstreamError;
import org.fiware.iam.tmforum.productorder.model.*;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * Handle all incoming events in connection to ProductOrder
 */
@Requires(condition = GeneralProperties.TmForumCondition.class)
@RequiredArgsConstructor
@Singleton
@Slf4j
public class ProductOrderEventHandler implements TMForumEventHandler {

    private static final String CREATE_EVENT = "ProductOrderCreateEvent";
    private static final String DELETE_EVENT = "ProductOrderDeleteEvent";
    private static final String STATE_CHANGE_EVENT = "ProductOrderStateChangeEvent";
    private static final List<String> SUPPORTED_EVENT_TYPES = List.of(CREATE_EVENT, DELETE_EVENT, STATE_CHANGE_EVENT);

    @Value("${general.productOrder.customerRole:Customer}")
    private String CUSTOMER_ROLE;

    private final ObjectMapper objectMapper;

    private final List<ProductOrderHandler> productOrderHandlers;


    @Override
    public boolean isEventTypeSupported(String eventType) {
        return SUPPORTED_EVENT_TYPES.contains(eventType);
    }

    @Override
    public Mono<HttpResponse<?>> handleEvent(String eventType, Map<String, Object> event) {

        String orgId = Stream
                .ofNullable(event)
                .map(rawEvent -> objectMapper.convertValue(rawEvent, ProductOrderCreateEventVO.class))
                .map(ProductOrderCreateEventVO::getEvent)
                .map(ProductOrderCreateEventPayloadVO::getProductOrder)
                .map(ProductOrderVO::getRelatedParty)
                .filter(Objects::nonNull)
                .map(rpl -> getCustomer(rpl).orElseThrow(() -> new IllegalArgumentException(
                        "Order %s: expected exactly one related party with role '%s', but the order has the roles %s.".formatted(
                                getOrderId(event), CUSTOMER_ROLE, rpl.stream().map(RelatedPartyVO::getRole).toList()))))
                .map(RelatedPartyVO::getId)
                .findAny()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Order %s: the event does not include a related party with the customer organization.".formatted(getOrderId(event))));

        return switch (eventType) {
            case CREATE_EVENT -> handelCreateEvent(orgId, event);
            case STATE_CHANGE_EVENT -> handelStateChangeEvent(orgId, event);
            case DELETE_EVENT -> handelDeleteEvent(orgId, event);
            default -> throw new IllegalArgumentException("Invalid event type received.");
        };

    }

    private String getOrderId(Map<String, Object> event) {
        return Optional.ofNullable(event)
                .map(rawEvent -> objectMapper.convertValue(rawEvent, ProductOrderCreateEventVO.class))
                .map(ProductOrderCreateEventVO::getEvent)
                .map(ProductOrderCreateEventPayloadVO::getProductOrder)
                .map(ProductOrderVO::getId)
                .orElse("<unknown>");
    }

    private Optional<RelatedPartyVO> getCustomer(List<RelatedPartyVO> relatedPartyVOS) {
        if (relatedPartyVOS == null || relatedPartyVOS.isEmpty()) {
            return Optional.empty();
        }
        if (relatedPartyVOS.size() == 1) {
            String role = relatedPartyVOS.getFirst().getRole();
            if (role == null || role.equalsIgnoreCase(CUSTOMER_ROLE)) {
                return Optional.of(relatedPartyVOS.getFirst());
            }
        }
        return relatedPartyVOS.stream()
                .filter(relatedPartyVO -> relatedPartyVO.getRole() != null)
                .filter(relatedPartyVO -> relatedPartyVO.getRole().equalsIgnoreCase(CUSTOMER_ROLE))
                .findFirst();
    }

    private Mono<HttpResponse<?>> handelCreateEvent(String organizationId, Map<String, Object> event) {
        ProductOrderCreateEventVO productOrderCreateEventVO = objectMapper.convertValue(event, ProductOrderCreateEventVO.class);

        ProductOrderVO productOrderVO = Optional.ofNullable(productOrderCreateEventVO.getEvent())
                .map(ProductOrderCreateEventPayloadVO::getProductOrder)
                .orElseThrow(() -> new IllegalArgumentException("The event does not contain a product order."));

        if (isNotRejected(productOrderVO) && containsQuote(productOrderVO)) {
            return runHandlers("negotiation", organizationId, productOrderVO,
                    handler -> handler.handleProductOrderNegotiation(organizationId, productOrderVO));
        }

        if (!isCompleted(productOrderVO)) {
            log.debug("Order {} was created in state {}; nothing to do before it is completed.",
                    productOrderVO.getId(), productOrderVO.getState());
            return Mono.just(HttpResponse.noContent());
        }

        return runHandlers("completion", organizationId, productOrderVO,
                handler -> handler.handleProductOrderComplete(organizationId, productOrderVO));
    }

    private static boolean isCompleted(ProductOrderVO productOrderVO) {
        return Optional.ofNullable(productOrderVO.getState())
                .filter(ProductOrderStateTypeVO.COMPLETED::equals)
                .isPresent();
    }

    private static boolean isNotRejected(ProductOrderVO productOrderVO) {
        return Optional.ofNullable(productOrderVO.getState())
                .filter(state -> state == ProductOrderStateTypeVO.REJECTED)
                .isEmpty();
    }

    private Mono<HttpResponse<?>> handelStateChangeEvent(String organizationId, Map<String, Object> event) {
        ProductOrderStateChangeEventVO productOrderStateChangeEventVO = objectMapper.convertValue(event, ProductOrderStateChangeEventVO.class);
        ProductOrderVO productOrderVO = Optional.ofNullable(productOrderStateChangeEventVO.getEvent())
                .map(ProductOrderStateChangeEventPayloadVO::getProductOrder)
                .orElseThrow(() -> new IllegalArgumentException("The event does not contain a product order."));

        if (isCompleted(productOrderVO)) {
            return runHandlers("completion", organizationId, productOrderVO,
                    handler -> handler.handleProductOrderComplete(organizationId, productOrderVO));
        }
        return runHandlers("stop (state %s)".formatted(productOrderVO.getState()), organizationId, productOrderVO,
                handler -> handler.handleProductOrderStop(organizationId, productOrderVO));
    }

    private Mono<HttpResponse<?>> handelDeleteEvent(String organizationId, Map<String, Object> event) {
        ProductOrderDeleteEventVO productOrderDeleteEventVO = objectMapper.convertValue(event, ProductOrderDeleteEventVO.class);
        ProductOrderVO productOrderVO = Optional.ofNullable(productOrderDeleteEventVO.getEvent())
                .map(ProductOrderDeleteEventPayloadVO::getProductOrder)
                .orElseThrow(() -> new IllegalArgumentException("The event does not contain a product order."));

        return runHandlers("stop (deleted)", organizationId, productOrderVO,
                handler -> handler.handleProductOrderStop(organizationId, productOrderVO));
    }

    /**
     * Run all handlers for the order and write one line telling what each of them did. The response is
     * a success only if all handlers succeeded; the first error of a handler is propagated, a non-2xx
     * response of a handler becomes a bad gateway.
     */
    private Mono<HttpResponse<?>> runHandlers(String action, String organizationId, ProductOrderVO productOrderVO,
                                              Function<ProductOrderHandler, Mono<HttpResponse<?>>> handlerCall) {
        String orderId = productOrderVO.getId();
        if (productOrderHandlers.isEmpty()) {
            log.info("Order {}: {} for customer {}, but no handler is enabled.", orderId, action, organizationId);
            return Mono.just(HttpResponse.noContent());
        }
        log.info("Order {}: handling {} for customer {} with handlers {}.", orderId, action, organizationId,
                productOrderHandlers.stream().map(ProductOrderHandler::getName).toList());

        List<Mono<HandlerOutcome>> outcomes = productOrderHandlers.stream()
                .map(handler -> Mono.defer(() -> handlerCall.apply(handler))
                        .map(response -> HandlerOutcome.of(handler.getName(), response))
                        .defaultIfEmpty(HandlerOutcome.skipped(handler.getName()))
                        .onErrorResume(t -> Mono.just(HandlerOutcome.failed(handler.getName(), t))))
                .toList();

        return Mono.zip(outcomes, results -> Arrays.stream(results).map(HandlerOutcome.class::cast).toList())
                .flatMap(results -> {
                    String summary = results.stream().map(HandlerOutcome::toString).collect(Collectors.joining(", "));
                    if (results.stream().anyMatch(HandlerOutcome::isFailure)) {
                        log.warn("Order {}: {} failed for customer {} - {}", orderId, action, organizationId, summary);
                    } else {
                        log.info("Order {}: {} succeeded for customer {} - {}", orderId, action, organizationId, summary);
                    }
                    Optional<Throwable> firstError = results.stream()
                            .map(HandlerOutcome::error)
                            .filter(Objects::nonNull)
                            .findFirst();
                    if (firstError.isPresent()) {
                        return Mono.error(firstError.get());
                    }
                    return Mono.just(results.stream()
                            .filter(HandlerOutcome::isFailure)
                            .findFirst()
                            .<HttpResponse<?>>map(outcome -> HttpResponse.status(HttpStatus.BAD_GATEWAY).body(outcome.response().body()))
                            .orElse(HttpResponse.noContent()));
                });
    }

    /**
     * What a single handler did with the order.
     */
    private record HandlerOutcome(String handler, @Nullable HttpResponse<?> response, @Nullable Throwable error) {

        static HandlerOutcome of(String handler, HttpResponse<?> response) {
            return new HandlerOutcome(handler, response, null);
        }

        static HandlerOutcome skipped(String handler) {
            return new HandlerOutcome(handler, null, null);
        }

        static HandlerOutcome failed(String handler, Throwable error) {
            return new HandlerOutcome(handler, null, error);
        }

        boolean isFailure() {
            return error != null || (response != null && !isSuccess(response));
        }

        private static boolean isSuccess(HttpResponse<?> response) {
            return response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300;
        }

        @Override
        public String toString() {
            if (error != null) {
                return "%s=FAILED(%s)".formatted(handler, DownstreamError.describe(error));
            }
            if (response == null) {
                return "%s=SKIPPED".formatted(handler);
            }
            if (isSuccess(response)) {
                return "%s=OK".formatted(handler);
            }
            return "%s=FAILED(status=%s)".formatted(handler, response.getStatus().getCode());
        }
    }

    private boolean containsQuote(ProductOrderVO productOrderVO) {
        return productOrderVO.getQuote() != null && !productOrderVO.getQuote().isEmpty();
    }


}
