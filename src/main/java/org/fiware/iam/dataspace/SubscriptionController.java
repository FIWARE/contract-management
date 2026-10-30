package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.context.ServerRequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.events.api.SubscriptionsApi;
import org.fiware.iam.events.model.HttpProtocolSettingsVO;
import org.fiware.iam.events.model.SubscriptionErrorVO;
import org.fiware.iam.events.model.SubscriptionRequestVO;
import org.fiware.iam.events.model.SubscriptionVO;
import org.fiware.iam.exception.SubscriptionLimitException;
import org.fiware.iam.exception.VerifierUnavailableException;
import org.fiware.iam.exception.WebhookHandshakeException;
import org.fiware.iam.logging.DownstreamError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lets authenticated participants subscribe their webhook to data space events; a subscription is only activated after its
 * sink passed the webhook handshake. Refusals carry generic reasons only, so the API cannot probe hosts reachable from CM.
 */
@Slf4j
@Controller("${general.basepath:/}")
@Requires(condition = DataSpaceEventsProperties.PublisherCondition.class)
@RequiredArgsConstructor
public class SubscriptionController implements SubscriptionsApi {

    static final String HANDSHAKE_FAILED = "handshake failed";
    static final String MISSING_SUBSCRIBER_ID = "subscriberId is required";
    static final String INVALID_SINK = "invalid sink";
    static final String INVALID_TYPES = "types must not be empty";
    static final String UNSUPPORTED_PROTOCOL = "unsupported protocol";
    static final String MISSING_CLIENT_ID = "protocolsettings.clientId is required";
    private static final String HTTPS = "https";
    private static final String HTTP = "http";

    private final BearerVerifier bearerVerifier;
    private final WebhookHandshake handshake;
    private final SubscriptionRegistry registry;
    private final DataSpaceEventsProperties properties;

    @Override
    public Mono<HttpResponse<SubscriptionVO>> createSubscription(SubscriptionRequestVO subscriptionRequestVO) {
        return create(currentAuthorization(), subscriptionRequestVO);
    }

    @Override
    public Mono<HttpResponse<Object>> deleteSubscription(String subscriptionId) {
        return delete(currentAuthorization(), subscriptionId);
    }

    Mono<HttpResponse<SubscriptionVO>> create(@Nullable String authorization, SubscriptionRequestVO request) {
        return participantIdOf(authorization)
                .flatMap(participantId -> create(participantId, request))
                .onErrorResume(VerifierUnavailableException.class, e -> {
                    log.warn("Subscription of {} to {} cannot be verified: {}",
                            request.getSubscriberId(), request.getSink(), DownstreamError.reason(e), e);
                    return Mono.just(retryLater());
                });
    }

    Mono<HttpResponse<Object>> delete(@Nullable String authorization, String subscriptionId) {
        return participantIdOf(authorization)
                .<HttpResponse<Object>>map(participantId -> {
                    if (participantId.isEmpty()) {
                        log.info("Refused removal of subscription {}: no valid bearer token.", subscriptionId);
                        return HttpResponse.status(HttpStatus.UNAUTHORIZED);
                    }
                    if (!registry.remove(subscriptionId, participantId.get())) {
                        log.info("Refused removal of subscription {} by {}: no such active subscription of it.",
                                subscriptionId, participantId.get());
                        return HttpResponse.status(HttpStatus.NOT_FOUND);
                    }
                    log.info("Subscription {} of {} removed by its subscriber.", subscriptionId, participantId.get());
                    return HttpResponse.noContent();
                })
                .onErrorResume(VerifierUnavailableException.class, e -> {
                    log.warn("Removal of subscription {} cannot be verified: {}", subscriptionId, DownstreamError.reason(e), e);
                    return Mono.just(retryLater());
                });
    }

    private Mono<HttpResponse<SubscriptionVO>> create(Optional<String> participantId, SubscriptionRequestVO request) {
        String subscriberId = request.getSubscriberId();
        URI sink = request.getSink();
        if (participantId.isEmpty()) {
            return refuse(HttpStatus.UNAUTHORIZED, subscriberId, sink, "no valid bearer token");
        }
        if (subscriberId == null || subscriberId.isBlank()) {
            return refuse(HttpStatus.BAD_REQUEST, subscriberId, sink, MISSING_SUBSCRIBER_ID);
        }
        if (!participantId.get().equals(subscriberId)) {
            return refuse(HttpStatus.FORBIDDEN, subscriberId, sink, "the bearer token identifies " + participantId.get());
        }
        Optional<String> invalid = invalidReason(request);
        if (invalid.isPresent()) {
            return refuse(HttpStatus.BAD_REQUEST, subscriberId, sink, invalid.get());
        }
        String origin = properties.getPublisher().getOrigin();
        if (origin == null || origin.isBlank()) {
            log.error("Refused subscription of {} to {}: dataspace.events.publisher.origin is not configured.", subscriberId, sink);
            return Mono.just(HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE));
        }
        try {
            registry.checkCapacity(subscriberId, sink);
        } catch (SubscriptionLimitException e) {
            return refuse(HttpStatus.CONFLICT, subscriberId, sink, e.getMessage());
        }
        return handshake.validate(sink)
                .map(allowedRate -> register(request, allowedRate.orElse(null)))
                .onErrorResume(WebhookHandshakeException.class, e -> {
                    // the cause stays in the log: answering it would let a subscriber probe internal hosts and ports
                    log.info("Refused subscription of {} to {}: {}{}", subscriberId, sink, DownstreamError.reason(e), causeOf(e));
                    return Mono.just(badRequest(HANDSHAKE_FAILED));
                })
                // a concurrent registration may have taken the last slot during the handshake
                .onErrorResume(SubscriptionLimitException.class,
                        e -> refuse(HttpStatus.CONFLICT, subscriberId, sink, e.getMessage()));
    }

    private HttpResponse<SubscriptionVO> register(SubscriptionRequestVO request, @Nullable Integer allowedRate) {
        HttpProtocolSettingsVO settings = request.getProtocolsettings();
        Set<String> scopes = Optional.ofNullable(settings.getScopes()).stream()
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Subscription subscription = registry.register(request.getSubscriberId(), request.getSink(), request.getTypes(),
                settings.getClientId(), scopes, allowedRate);
        log.info("Subscription {} of {} to {} active until {} for types {}, allowed rate {}.", subscription.id(),
                subscription.subscriberId(), subscription.sink(), subscription.expiresAt(), subscription.types(), allowedRate);
        return HttpResponse.created(toVO(subscription));
    }

    private Optional<String> invalidReason(SubscriptionRequestVO request) {
        if (!isAllowedSink(request.getSink())) {
            return Optional.of(INVALID_SINK);
        }
        List<String> types = request.getTypes();
        if (types == null || types.isEmpty() || types.stream().anyMatch(type -> type == null || type.isBlank())) {
            return Optional.of(INVALID_TYPES);
        }
        if (request.getProtocol() != SubscriptionRequestVO.Protocol.HTTP) {
            return Optional.of(UNSUPPORTED_PROTOCOL);
        }
        HttpProtocolSettingsVO settings = request.getProtocolsettings();
        if (settings == null || settings.getClientId() == null || settings.getClientId().isBlank()) {
            return Optional.of(MISSING_CLIENT_ID);
        }
        return Optional.empty();
    }

    private boolean isAllowedSink(@Nullable URI sink) {
        if (sink == null || !sink.isAbsolute() || sink.isOpaque() || sink.getHost() == null) {
            return false;
        }
        return HTTPS.equalsIgnoreCase(sink.getScheme())
                || (HTTP.equalsIgnoreCase(sink.getScheme()) && properties.getPublisher().isAllowInsecureSinks());
    }

    private Mono<Optional<String>> participantIdOf(@Nullable String authorization) {
        // the JWKS fetch blocks, so it must not run on the event loop
        return Mono.fromCallable(() -> bearerVerifier.participantIdOf(authorization))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static <T> Mono<HttpResponse<T>> refuse(HttpStatus status, @Nullable String subscriberId, @Nullable URI sink,
                                                    String reason) {
        log.info("Refused subscription of {} to {}: {}", subscriberId, sink, reason);
        return Mono.just(status == HttpStatus.BAD_REQUEST ? badRequest(reason) : HttpResponse.status(status));
    }

    // the generated API types every response as a Subscription; the error body is serialized by its runtime type
    @SuppressWarnings("unchecked")
    private static <T> HttpResponse<T> badRequest(String reason) {
        return (HttpResponse<T>) (HttpResponse<?>) HttpResponse.badRequest(new SubscriptionErrorVO().reason(reason));
    }

    private static <T> HttpResponse<T> retryLater() {
        return HttpResponse.<T>status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(EventListener.RETRY_AFTER.toSeconds()));
    }

    // the handshake exception only names the failure; a transport cause (refused, timed out) is what an operator needs
    private static String causeOf(WebhookHandshakeException e) {
        Throwable cause = e.getCause();
        return cause == null || DownstreamError.httpError(e).isPresent() ? "" : " (" + cause + ")";
    }

    private static SubscriptionVO toVO(Subscription subscription) {
        return new SubscriptionVO()
                .id(subscription.id())
                .subscriberId(subscription.subscriberId())
                .sink(subscription.sink())
                .types(new ArrayList<>(subscription.types()))
                .protocol(SubscriptionVO.Protocol.HTTP)
                .protocolsettings(new HttpProtocolSettingsVO()
                        .clientId(subscription.clientId())
                        .scopes(new ArrayList<>(subscription.scopes())))
                .expiresAt(subscription.expiresAt());
    }

    @Nullable
    private static String currentAuthorization() {
        return ServerRequestContext.currentRequest()
                .flatMap(request -> request.getHeaders().getAuthorization())
                .orElse(null);
    }
}
