package org.fiware.iam.dataspace;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.context.ServerRequestContext;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.events.api.EventsApi;
import org.fiware.iam.events.model.DataSpaceEventVO;
import org.fiware.iam.exception.VerifierUnavailableException;
import org.fiware.iam.logging.DownstreamError;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Receiving endpoint of data space events: authorizes, deduplicates and dispatches them to the {@link DataSpaceEventHandler}s.
 * Every request is counted in {@value #METRIC_NAME}, tagged with the event type (if known) and the outcome.
 */
@Slf4j
@Controller("${general.basepath:/}")
@Requires(condition = DataSpaceEventsProperties.ListenerCondition.class)
public class EventListener implements EventsApi {

    static final String METRIC_NAME = "dataspace.events.received";
    static final String TYPE_TAG = "type";
    static final String OUTCOME_TAG = "outcome";
    // types no handler supports share one tag value, so callers cannot create unbounded series
    static final String OTHER_TYPE = "other";
    static final String DUPLICATE = "duplicate";
    static final String IN_FLIGHT = "in_flight";
    static final String INVALID = "invalid";
    static final String HANDLER_FAILED = "handler_failed";
    static final String VERIFIER_UNAVAILABLE = "verifier_unavailable";
    static final Duration RETRY_AFTER = Duration.ofSeconds(5);

    private final List<DataSpaceEventHandler> handlers;
    private final BearerVerifier bearerVerifier;
    private final EventAuthorizer authorizer;
    private final EventDeduplicator deduplicator;
    private final MeterRegistry meterRegistry;

    public EventListener(List<DataSpaceEventHandler> handlers, BearerVerifier bearerVerifier, EventAuthorizer authorizer,
                         EventDeduplicator deduplicator, Optional<MeterRegistry> meterRegistry) {
        this.handlers = handlers;
        this.bearerVerifier = bearerVerifier;
        this.authorizer = authorizer;
        this.deduplicator = deduplicator;
        // without micronaut.metrics.enabled there is no registry; an empty composite counts nothing
        this.meterRegistry = meterRegistry.orElseGet(CompositeMeterRegistry::new);
    }

    @Override
    public Mono<HttpResponse<Object>> receiveEvent(DataSpaceEventVO event) {
        String authorization = ServerRequestContext.currentRequest()
                .flatMap(request -> request.getHeaders().getAuthorization())
                .orElse(null);
        return receive(authorization, event);
    }

    @Override
    public Mono<HttpResponse<Object>> validateWebhook(@Nullable String webHookRequestOrigin) {
        // the CloudEvents webhook validation handshake is not offered yet
        return Mono.just(HttpResponse.status(HttpStatus.NOT_IMPLEMENTED));
    }

    Mono<HttpResponse<Object>> receive(@Nullable String authorization, DataSpaceEventVO event) {
        String metricType = isKnownType(event.getType()) ? event.getType() : OTHER_TYPE;
        // the JWKS fetch blocks, so it must not run on the event loop
        return Mono.fromCallable(() -> bearerVerifier.participantIdOf(authorization))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(participantId -> process(participantId, event, metricType))
                .onErrorResume(VerifierUnavailableException.class, e -> {
                    log.warn("Data space event {} from {} of type {} cannot be verified: {}",
                            event.getId(), event.getSource(), event.getType(), DownstreamError.reason(e), e);
                    return retryLater(metricType, VERIFIER_UNAVAILABLE);
                });
    }

    private Mono<HttpResponse<Object>> process(Optional<String> participantId, DataSpaceEventVO event, String metricType) {
        EventDecision decision = authorizer.authorize(participantId, event, this::isKnownType);
        if (decision.isRejected()) {
            log.warn("Rejected data space event {} from {} of type {}: {}.",
                    event.getId(), event.getSource(), event.getType(), decision.getOutcome());
            return respond(decision.getStatus(), metricType, decision.getOutcome());
        }
        if (!isComplete(event)) {
            log.warn("Rejected data space event {} from {} of type {}: specversion, id, source and type are required.",
                    event.getId(), event.getSource(), event.getType());
            return respond(HttpStatus.BAD_REQUEST, metricType, INVALID);
        }
        if (decision == EventDecision.UNKNOWN_TYPE) {
            log.debug("Data space event {} from {} has the unsupported type {}, dropped.", event.getId(), event.getSource(), event.getType());
            return respond(decision.getStatus(), metricType, decision.getOutcome());
        }
        EventDeduplicator.Key key = new EventDeduplicator.Key(event.getSource(), event.getId());
        return switch (deduplicator.begin(key)) {
            case DONE -> respond(HttpStatus.ACCEPTED, metricType, DUPLICATE);
            case IN_FLIGHT -> retryLater(metricType, IN_FLIGHT);
            case NEW -> decision == EventDecision.SELF ? skipSelf(key, event, metricType) : dispatch(key, event, metricType);
        };
    }

    private Mono<HttpResponse<Object>> skipSelf(EventDeduplicator.Key key, DataSpaceEventVO event, String metricType) {
        log.info("Data space event {} from {} is about this participant, not acted on.", event.getId(), event.getSource());
        deduplicator.complete(key);
        return respond(EventDecision.SELF.getStatus(), metricType, EventDecision.SELF.getOutcome());
    }

    private Mono<HttpResponse<Object>> dispatch(EventDeduplicator.Key key, DataSpaceEventVO event, String metricType) {
        // settled before the response is emitted, so a retry after the answer never sees the event in flight
        return Flux.fromIterable(handlers)
                .filter(handler -> handler.isEventTypeSupported(event.getType()))
                .concatMap(handler -> handler.handleEvent(event))
                .then()
                .doOnSuccess(ignored -> deduplicator.complete(key))
                .doOnError(e -> deduplicator.forget(key))
                .doOnCancel(() -> deduplicator.forget(key))
                .then(respond(EventDecision.ACCEPT.getStatus(), metricType, EventDecision.ACCEPT.getOutcome()))
                .onErrorResume(e -> {
                    log.warn("Data space event {} from {} of type {} could not be handled: {}",
                            event.getId(), event.getSource(), event.getType(), DownstreamError.reason(e), e);
                    return respond(HttpStatus.INTERNAL_SERVER_ERROR, metricType, HANDLER_FAILED);
                });
    }

    private Mono<HttpResponse<Object>> retryLater(String metricType, String outcome) {
        return Mono.fromSupplier(() -> counted(HttpStatus.SERVICE_UNAVAILABLE, metricType, outcome)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER.toSeconds())));
    }

    private Mono<HttpResponse<Object>> respond(HttpStatus status, String metricType, String outcome) {
        return Mono.fromSupplier(() -> counted(status, metricType, outcome));
    }

    private MutableHttpResponse<Object> counted(HttpStatus status, String metricType, String outcome) {
        meterRegistry.counter(METRIC_NAME, TYPE_TAG, metricType, OUTCOME_TAG, outcome).increment();
        return HttpResponse.status(status);
    }

    private boolean isKnownType(@Nullable String type) {
        return type != null && handlers.stream().anyMatch(handler -> handler.isEventTypeSupported(type));
    }

    private static boolean isComplete(DataSpaceEventVO event) {
        return event.getSpecversion() != null && !isBlank(event.getId()) && !isBlank(event.getSource()) && !isBlank(event.getType());
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
