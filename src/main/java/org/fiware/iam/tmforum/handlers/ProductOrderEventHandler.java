package org.fiware.iam.tmforum.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.handlers.ProductOrderHandler;
import org.fiware.iam.tmforum.productorder.model.*;
import reactor.core.publisher.Mono;

import java.util.*;
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


    private static final String CUSTOMER_ROLE = "Customer";

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
                .map(rpl -> getCustomer(rpl).orElseThrow(() -> new IllegalArgumentException("Exactly one ordering related party is expected.")))
                .map(RelatedPartyVO::getId)
                .findAny()
                .orElseThrow(() -> new IllegalArgumentException("The ProductOrder-Event does not include a valid organization id."));

        return switch (eventType) {
            case CREATE_EVENT -> handelCreateEvent(orgId, event);
            case STATE_CHANGE_EVENT -> handelStateChangeEvent(orgId, event);
            case DELETE_EVENT -> handelDeleteEvent(orgId, event);
            default -> throw new IllegalArgumentException("Invalid event type received.");
        };

    }

    private Optional<RelatedPartyVO> getCustomer(List<RelatedPartyVO> relatedPartyVOS) {
        if (relatedPartyVOS == null || relatedPartyVOS.isEmpty()) {
            return Optional.empty();
        }
        if (relatedPartyVOS.size() == 1) {
            String role = relatedPartyVOS.getFirst().getRole();
            if (role == null || role.equals(CUSTOMER_ROLE)) {
                return Optional.of(relatedPartyVOS.getFirst());
            }
        }
        return relatedPartyVOS.stream()
                .filter(relatedPartyVO -> relatedPartyVO.getRole() != null)
                .filter(relatedPartyVO -> relatedPartyVO.getRole().equals(CUSTOMER_ROLE))
                .findFirst();
    }

    private Mono<HttpResponse<?>> handelCreateEvent(String organizationId, Map<String, Object> event) {
        ProductOrderCreateEventVO productOrderCreateEventVO = objectMapper.convertValue(event, ProductOrderCreateEventVO.class);

        ProductOrderVO productOrderVO = Optional.ofNullable(productOrderCreateEventVO.getEvent())
                .map(ProductOrderCreateEventPayloadVO::getProductOrder)
                .orElseThrow(() -> new IllegalArgumentException("The event does not contain a product order."));

        if (isNotRejected(productOrderVO) && containsQuote(productOrderVO)) {
            List<Mono<HttpResponse<?>>> responses = productOrderHandlers.stream()
                    .map(handler -> handler.handleProductOrderNegotiation(organizationId, productOrderVO))
                    .toList();

            return zipToResponse(responses);
        }

        boolean isCompleted = isCompleted(productOrderVO);
        if (!isCompleted) {
            log.debug("The received event is not in state completed.");
            return Mono.just(HttpResponse.noContent());
        }

        List<Mono<HttpResponse<?>>> responses = productOrderHandlers.stream()
                .map(handler -> handler.handleProductOrderComplete(organizationId, productOrderVO))
                .toList();

        return zipToResponse(responses);
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
            log.debug("Product order is completed.");

            List<Mono<HttpResponse<?>>> responses = productOrderHandlers.stream()
                    .map(handler -> handler.handleProductOrderComplete(organizationId, productOrderVO)
                            .doOnNext(r -> log.debug("Handler {} responded {}", handler.getClass().getName(), r)))
                    .toList();

            return zipToResponse(responses);
        } else {
            return handleStopEvent(organizationId, event);
        }
    }


    private Mono<HttpResponse<?>> handleStopEvent(String organizationId, Map<String, Object> event) {
        ProductOrderStateChangeEventVO productOrderStateChangeEventVO = objectMapper.convertValue(event, ProductOrderStateChangeEventVO.class);
        ProductOrderVO productOrderVO = Optional.ofNullable(productOrderStateChangeEventVO.getEvent())
                .map(ProductOrderStateChangeEventPayloadVO::getProductOrder)
                .orElseThrow(() -> new IllegalArgumentException("The event does not contain a product order."));


        List<Mono<HttpResponse<?>>> responses = productOrderHandlers.stream()
                .map(handler -> handler.handleProductOrderStop(organizationId, productOrderVO))
                .toList();

        return zipToResponse(responses);
    }

    private Mono<HttpResponse<?>> handelDeleteEvent(String organizationId, Map<String, Object> event) {
        ProductOrderDeleteEventVO productOrderDeleteEventVO = objectMapper.convertValue(event, ProductOrderDeleteEventVO.class);
        ProductOrderVO productOrderVO = Optional.ofNullable(productOrderDeleteEventVO.getEvent())
                .map(ProductOrderDeleteEventPayloadVO::getProductOrder)
                .orElseThrow(() -> new IllegalArgumentException("The event does not contain a product order."));

        List<Mono<HttpResponse<?>>> responses = productOrderHandlers.stream()
                .map(handler -> handler.handleProductOrderStop(organizationId, productOrderVO))
                .toList();

        return zipToResponse(responses);
    }


    private boolean containsQuote(ProductOrderVO productOrderVO) {
        return productOrderVO.getQuote() != null && !productOrderVO.getQuote().isEmpty();
    }


}
