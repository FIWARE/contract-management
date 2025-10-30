package org.fiware.iam.tmforum.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.handlers.QuoteHandler;
import org.fiware.iam.tmforum.quote.model.QuoteCreateEventVO;
import org.fiware.iam.tmforum.quote.model.QuoteDeleteEventVO;
import org.fiware.iam.tmforum.quote.model.QuoteStateChangeEventVO;
import org.fiware.iam.tmforum.quote.model.QuoteVO;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Handle all incoming events in connection to Quote
 */
@Requires(condition = GeneralProperties.TmForumCondition.class)
@RequiredArgsConstructor
@Singleton
@Slf4j
public class QuoteEventHandler implements TMForumEventHandler {


    private static final String CREATE_EVENT = "QuoteCreateEvent";
    private static final String DELETE_EVENT = "QuoteDeleteEvent";
    private static final String STATE_CHANGE_EVENT = "QuoteStateChangeEvent";
    private static final String ATTRIBUTE_CHANGE_EVENT = "QuoteAttributeValueChangeEvent";

    private static final List<String> SUPPORTED_EVENT_TYPES = List.of(CREATE_EVENT, DELETE_EVENT, STATE_CHANGE_EVENT, ATTRIBUTE_CHANGE_EVENT);

    private final ObjectMapper objectMapper;

    private final List<QuoteHandler> quoteHandlers;


    @Override
    public boolean isEventTypeSupported(String eventType) {
        return SUPPORTED_EVENT_TYPES.contains(eventType);
    }

    @Override
    public Mono<HttpResponse<?>> handleEvent(String eventType, Map<String, Object> event) {
        return switch (eventType) {
            case CREATE_EVENT -> handleQuoteCreation(event);
            case STATE_CHANGE_EVENT, ATTRIBUTE_CHANGE_EVENT -> handleQuoteStateChange(event);
            case DELETE_EVENT -> handleQuoteDeletion(event);
            default -> throw new IllegalArgumentException("Event type %s is not supported.".formatted(eventType));
        };
    }

    private Mono<HttpResponse<?>> handleQuoteCreation(Map<String, Object> event) {
        QuoteCreateEventVO quoteCreateEventVO = objectMapper.convertValue(event, QuoteCreateEventVO.class);
        QuoteVO quoteVO = quoteCreateEventVO.getEvent()
                .getQuote();

        List<Mono<HttpResponse<?>>> responses = quoteHandlers.stream()
                .map(handler -> handler.handleQuoteCreation(quoteVO))
                .toList();

        return zipToResponse(responses);
    }


    private Mono<HttpResponse<?>> handleQuoteStateChange(Map<String, Object> event) {
        QuoteStateChangeEventVO quoteStateChangeEventVO = objectMapper.convertValue(event, QuoteStateChangeEventVO.class);
        QuoteVO quoteVO = quoteStateChangeEventVO.getEvent()
                .getQuote();

        List<Mono<HttpResponse<?>>> responses = quoteHandlers.stream()
                .map(handler -> handler
                        .handleQuoteStateChange(quoteVO)
                        .doOnNext(r -> log.debug("Handler {} responded {}", handler.getClass().getName(), r)))
                .toList();

        return zipToResponse(responses);
    }

    private Mono<HttpResponse<?>> handleQuoteDeletion(Map<String, Object> event) {
        QuoteDeleteEventVO quoteDeleteEventVO = objectMapper.convertValue(event, QuoteDeleteEventVO.class);
        QuoteVO quoteVO = quoteDeleteEventVO.getEvent()
                .getQuote();

        List<Mono<HttpResponse<?>>> responses = quoteHandlers.stream()
                .map(handler -> handler.handleQuoteDeletion(quoteVO))
                .toList();

        return zipToResponse(responses);
    }

}
