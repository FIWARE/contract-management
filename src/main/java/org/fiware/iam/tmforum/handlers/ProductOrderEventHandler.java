package org.fiware.iam.tmforum.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.handlers.OrderAction;
import org.fiware.iam.handlers.ProductOrderHandler;
import org.fiware.iam.http.HttpResponses;
import org.fiware.iam.logging.DownstreamError;
import org.fiware.iam.tmforum.productorder.model.*;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
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
            return runHandlers(OrderAction.NEGOTIATION, organizationId, productOrderVO,
                    handler -> handler.handleProductOrderNegotiation(organizationId, productOrderVO));
        }

        if (!isCompleted(productOrderVO)) {
            log.debug("Order {} was created in state {}; nothing to do before it is completed.",
                    productOrderVO.getId(), productOrderVO.getState());
            return Mono.just(HttpResponse.noContent());
        }

        return runHandlers(OrderAction.COMPLETION, organizationId, productOrderVO,
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
            return runHandlers(OrderAction.COMPLETION, organizationId, productOrderVO,
                    handler -> handler.handleProductOrderComplete(organizationId, productOrderVO));
        }
        return runHandlers(OrderAction.STOP, organizationId, productOrderVO,
                handler -> handler.handleProductOrderStop(organizationId, productOrderVO));
    }

    private Mono<HttpResponse<?>> handelDeleteEvent(String organizationId, Map<String, Object> event) {
        ProductOrderDeleteEventVO productOrderDeleteEventVO = objectMapper.convertValue(event, ProductOrderDeleteEventVO.class);
        ProductOrderVO productOrderVO = Optional.ofNullable(productOrderDeleteEventVO.getEvent())
                .map(ProductOrderDeleteEventPayloadVO::getProductOrder)
                .orElseThrow(() -> new IllegalArgumentException("The event does not contain a product order."));

        return runHandlers(OrderAction.DELETION, organizationId, productOrderVO,
                handler -> handler.handleProductOrderStop(organizationId, productOrderVO));
    }

    /**
     * Run all handlers for the order. A failing handler is logged once, with its name and the reason, and
     * answers with a bad gateway - no matter whether it failed with an error or with a non-2xx response.
     * One final line tells whether the order was handled completely.
     */
    private Mono<HttpResponse<?>> runHandlers(OrderAction action, String organizationId, ProductOrderVO productOrderVO,
                                              Function<ProductOrderHandler, Mono<HttpResponse<?>>> handlerCall) {
        String orderId = productOrderVO.getId();
        log.debug("Order {}: handling {} (state {}) for customer {} with handlers {}.", orderId, action,
                productOrderVO.getState(), organizationId, productOrderHandlers.stream().map(ProductOrderHandler::getName).toList());

        return Mono.defer(() -> {
            List<String> failedHandlers = new CopyOnWriteArrayList<>();
            List<Mono<HttpResponse<?>>> responses = productOrderHandlers.stream()
                    // deferred, so that a handler throwing while assembling its Mono fails alone instead of all handlers
                    .map(handler -> Mono.defer(() -> handlerCall.apply(handler))
                            .doOnNext(response -> {
                                if (!HttpResponses.isSuccess(response)) {
                                    // the handler logged the reason itself
                                    failedHandlers.add(handler.getName());
                                }
                            })
                            .onErrorResume(t -> {
                                failedHandlers.add(handler.getName());
                                log.warn("Order {}: {} failed in handler {}: {}", orderId, action, handler.getName(),
                                        DownstreamError.reason(t), t);
                                return Mono.just(HttpResponse.status(HttpStatus.BAD_GATEWAY));
                            }))
                    .toList();
            return zipToResponse(responses)
                    .doOnNext(response -> {
                        if (failedHandlers.isEmpty()) {
                            log.info("Order {}: {} succeeded for customer {}.", orderId, action, organizationId);
                        } else {
                            log.warn("Order {}: {} failed for customer {} in the handlers {}.", orderId, action, organizationId, failedHandlers);
                        }
                    });
        });
    }

    private boolean containsQuote(ProductOrderVO productOrderVO) {
        return productOrderVO.getQuote() != null && !productOrderVO.getQuote().isEmpty();
    }


}
