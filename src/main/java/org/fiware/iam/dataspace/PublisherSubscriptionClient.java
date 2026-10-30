package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.uri.UriBuilder;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.events.model.HttpProtocolSettingsVO;
import org.fiware.iam.events.model.SubscriptionRequestVO;
import org.fiware.iam.events.model.SubscriptionVO;
import org.fiware.iam.exception.EventSubscriptionException;
import org.fiware.iam.http.AuthHandler;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

import static org.fiware.iam.http.Oid4VpAuthHandler.CLIENT_ID_ATTRIBUTE;
import static org.fiware.iam.http.Oid4VpAuthHandler.SCOPE_ATTRIBUTE;

/**
 * Client of a publisher's CM-2 subscriptions API; registers and removes our webhook sink, authenticated via OID4VP.
 * Everything it sends is taken from the {@link SubscriptionTarget}, never from the (refreshable) properties.
 */
@Singleton
@Requires(condition = DataSpaceEventsProperties.SubscriberCondition.class)
public class PublisherSubscriptionClient {

    // bounds a whole exchange including the OID4VP token fetch a 401 triggers, which no client timeout covers
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    // the subscriptions URL is chosen by the operator, not by a participant, so the default client is used
    private final HttpClient httpClient;
    // only use authentication if a handler is provided
    private final Optional<AuthHandler> authHandler;
    private final Duration requestTimeout;

    @Inject
    public PublisherSubscriptionClient(HttpClient httpClient, Optional<AuthHandler> authHandler) {
        this(httpClient, authHandler, REQUEST_TIMEOUT);
    }

    PublisherSubscriptionClient(HttpClient httpClient, Optional<AuthHandler> authHandler, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.authHandler = authHandler;
        this.requestTimeout = requestTimeout;
    }

    /**
     * Outcome of an accepted registration; {@code conflict} marks a 409, which carries no subscription (id and expiry null).
     */
    public record Registration(@Nullable String id, @Nullable Instant expiresAt, boolean conflict) {

        public static final Registration CONFLICT = new Registration(null, null, true);

        /**
         * A 2xx answer; the publisher may omit the id or the expiry.
         */
        public static Registration registered(@Nullable String id, @Nullable Instant expiresAt) {
            return new Registration(id, expiresAt, false);
        }
    }

    /**
     * Creates or renews our subscription. Fails with an {@link EventSubscriptionException} unless the publisher answered 2xx or 409.
     */
    public Mono<Registration> subscribe(SubscriptionTarget target) {
        return Mono.defer(() -> exchange(target, HttpRequest.POST(target.subscriptionsUrl(), requestOf(target)), SubscriptionVO.class))
                .map(response -> registrationOf(target, response))
                .onErrorResume(e -> hasStatus(e, HttpStatus.CONFLICT), e -> Mono.just(Registration.CONFLICT))
                .onErrorMap(e -> failure(target, target.subscriptionsUrl(), "registration of " + target.sink() + " failed", e));
    }

    /**
     * Removes our subscription; a 404 counts as success, because the subscription is gone either way.
     */
    public Mono<Void> unsubscribe(SubscriptionTarget target, String subscriptionId) {
        String message = "removal of subscription " + subscriptionId + " failed";
        return Mono.defer(() -> {
                    String url = UriBuilder.of(target.subscriptionsUrl()).path(subscriptionId).build().toString();
                    return exchange(target, HttpRequest.DELETE(url), String.class)
                            .then()
                            .onErrorResume(e -> hasStatus(e, HttpStatus.NOT_FOUND), e -> Mono.empty())
                            .onErrorMap(e -> failure(target, url, message, e));
                })
                // only reached when the URL itself cannot be built
                .onErrorMap(e -> failure(target, target.subscriptionsUrl(), message, e));
    }

    // a non-2xx answer may be returned (first call inside executeWithAuth) or thrown; both end up as the thrown shape
    private Mono<HttpResponse> exchange(SubscriptionTarget target, MutableHttpRequest<?> request, Class<?> bodyType) {
        if (target.clientId() != null) {
            request.setAttribute(CLIENT_ID_ATTRIBUTE, target.clientId());
        }
        request.setAttribute(SCOPE_ATTRIBUTE, target.scopes());
        // the response is handed off the event loop, because the handler starts the (possibly blocking) token fetch on
        // the thread that delivers it; every registration starts unauthenticated, so that happens on every 401
        Function<MutableHttpRequest<?>, Mono<HttpResponse>> send = req -> Mono.<HttpResponse>from(httpClient.exchange(req, bodyType))
                .publishOn(Schedulers.boundedElastic());
        return authHandler.map(handler -> handler.executeWithAuth(request, send))
                .orElseGet(() -> send.apply(request))
                // a timeout is a transport error, so the subscriber's backoff retries it instead of waiting forever
                .timeout(requestTimeout)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("No response from " + request.getUri())))
                .flatMap(response -> DownstreamStatus.isSuccess(response.code()) ? Mono.just(response)
                        : Mono.error(new HttpClientResponseException("Publisher answered " + response.code(), response)));
    }

    private static SubscriptionRequestVO requestOf(SubscriptionTarget target) {
        return new SubscriptionRequestVO()
                .subscriberId(target.subscriberId())
                .sink(URI.create(target.sink()))
                .types(target.types())
                .protocol(SubscriptionRequestVO.Protocol.HTTP)
                .protocolsettings(new HttpProtocolSettingsVO()
                        .clientId(target.protocolClientId())
                        .scopes(target.protocolScopes()));
    }

    private static Registration registrationOf(SubscriptionTarget target, HttpResponse<?> response) {
        SubscriptionVO subscription = response.getBody(SubscriptionVO.class)
                .orElseThrow(() -> new EventSubscriptionException(target.publisherId(), target.subscriptionsUrl(),
                        "answered " + response.code() + " without a subscription", response.code(), null));
        return Registration.registered(subscription.getId(), subscription.getExpiresAt());
    }

    private static boolean hasStatus(Throwable e, HttpStatus status) {
        return DownstreamStatus.statusOf(e).filter(code -> code == status.getCode()).isPresent();
    }

    private static EventSubscriptionException failure(SubscriptionTarget target, String url, String message, Throwable e) {
        if (e instanceof EventSubscriptionException subscriptionException) {
            return subscriptionException;
        }
        return new EventSubscriptionException(target.publisherId(), url, message, DownstreamStatus.statusOf(e).orElse(null), e);
    }
}
