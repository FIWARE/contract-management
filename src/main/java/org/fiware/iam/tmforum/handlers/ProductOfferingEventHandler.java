package org.fiware.iam.tmforum.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.handlers.ProductOfferingHandler;
import org.fiware.iam.tmforum.productcatalog.model.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handle all incoming events in connection to ProductOfferings
 */
@Requires(condition = GeneralProperties.TmForumCondition.class)
@RequiredArgsConstructor
@Singleton
@Slf4j
public class ProductOfferingEventHandler implements TMForumEventHandler {


    private static final String CREATE_EVENT = "ProductOfferingCreateEvent";
    private static final String DELETE_EVENT = "ProductOfferingDeleteEvent";
    private static final String STATE_CHANGE_EVENT = "ProductOfferingStateChangeEvent";

    private static final List<String> SUPPORTED_EVENT_TYPES = List.of(CREATE_EVENT, DELETE_EVENT, STATE_CHANGE_EVENT);


    private final ObjectMapper objectMapper;
    private final List<ProductOfferingHandler> productOfferingHandlers;

    @Override
    public boolean isEventTypeSupported(String eventType) {
        return SUPPORTED_EVENT_TYPES.contains(eventType);
    }

    @Override
    public Mono<HttpResponse<?>> handleEvent(String eventType, Map<String, Object> event) {
        return switch (eventType) {
            case CREATE_EVENT -> handleOfferingCreation(event);
            case STATE_CHANGE_EVENT -> handleOfferingStateChange(event);
            case DELETE_EVENT -> handleOfferingDeletion(event);
            default -> throw new IllegalArgumentException("Even type %s is not supported.".formatted(eventType));
        };
    }

    private Mono<HttpResponse<?>> handleOfferingCreation(Map<String, Object> event) {
        ProductOfferingCreateEventVO productOfferingCreateEventVO = objectMapper.convertValue(event, ProductOfferingCreateEventVO.class);
        ProductOfferingVO productOfferingVO = Optional.ofNullable(productOfferingCreateEventVO.getEvent())
                .map(ProductOfferingCreateEventPayloadVO::getProductOffering)
                .orElseThrow(() -> new IllegalArgumentException("The event does not contain a product offering."));

        if (productOfferingVO.getCategory() == null || productOfferingVO.getCategory().isEmpty()) {
            throw new IllegalArgumentException("Product offering does not have a category.");
        }

        List<Mono<HttpResponse<?>>> responses = productOfferingHandlers.stream()
                .map(handler -> handler.handleOfferingCreation(productOfferingVO))
                .toList();

        return zipToResponse(responses);

    }

    private Mono<HttpResponse<?>> handleOfferingStateChange(Map<String, Object> event) {
        ProductOfferingStateChangeEventVO productOfferingStateChangeEventVO = objectMapper.convertValue(event, ProductOfferingStateChangeEventVO.class);
        ProductOfferingVO productOfferingVO = Optional.ofNullable(productOfferingStateChangeEventVO.getEvent())
                .map(ProductOfferingStateChangeEventPayloadVO::getProductOffering)
                .orElseThrow(() -> new IllegalArgumentException("The event does not contain a product offering."));

        if (productOfferingVO.getCategory() == null || productOfferingVO.getCategory().isEmpty()) {
            // if no category is included, the offering is not part of a catalog anymore
            return deleteOffering(productOfferingVO);
        }

        List<Mono<HttpResponse<?>>> responses = productOfferingHandlers.stream()
                .map(handler -> handler.handleOfferingStateChange(productOfferingVO))
                .toList();

        return zipToResponse(responses);
    }


    private Mono<HttpResponse<?>> handleOfferingDeletion(Map<String, Object> event) {

        ProductOfferingDeleteEventVO productOfferingDeleteEventVO = objectMapper.convertValue(event, ProductOfferingDeleteEventVO.class);
        ProductOfferingVO productOfferingVO = Optional.ofNullable(productOfferingDeleteEventVO.getEvent())
                .map(ProductOfferingDeleteEventPayloadVO::getProductOffering)
                .orElseThrow(() -> new IllegalArgumentException("The event does not contain a product offering."));

        return deleteOffering(productOfferingVO);

    }

    private Mono<HttpResponse<?>> deleteOffering(ProductOfferingVO productOfferingVO) {
        List<Mono<HttpResponse<?>>> responses = productOfferingHandlers.stream()
                .map(handler -> handler.handleOfferingDeletion(productOfferingVO))
                .toList();

        return zipToResponse(responses);
    }

}
