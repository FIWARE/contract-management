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
import lombok.RequiredArgsConstructor;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.events.model.HttpProtocolSettingsVO;
import org.fiware.iam.events.model.SubscriptionRequestVO;
import org.fiware.iam.events.model.SubscriptionVO;
import org.fiware.iam.exception.EventSubscriptionException;
import org.fiware.iam.http.AuthHandler;
import reactor.core.publisher.Mono;

import java.net.URI;
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
@RequiredArgsConstructor
public class PublisherSubscriptionClient {

    // the subscriptions URL is chosen by the operator, not by a participant, so the default client is used
    private final HttpClient httpClient;
    // only use authentication if a handler is provided
    private final Optional<AuthHandler> authHandler;

    /**
     * Outcome of an accepted registration; both values are null for a 409, which carries no subscription.
     */
    public record Registration(@Nullable String id, @Nullable Instant expiresAt) {

        static final Registration CONFLICT = new Registration(null, null);
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
        String url = UriBuilder.of(target.subscriptionsUrl()).path(subscriptionId).build().toString();
        return Mono.defer(() -> exchange(target, HttpRequest.DELETE(url), String.class))
                .then()
                .onErrorResume(e -> hasStatus(e, HttpStatus.NOT_FOUND), e -> Mono.empty())
                .onErrorMap(e -> failure(target, url, "removal of subscription " + subscriptionId + " failed", e));
    }

    // a non-2xx answer may be returned (first call inside executeWithAuth) or thrown; both end up as the thrown shape
    private Mono<HttpResponse> exchange(SubscriptionTarget target, MutableHttpRequest<?> request, Class<?> bodyType) {
        if (target.clientId() != null) {
            request.setAttribute(CLIENT_ID_ATTRIBUTE, target.clientId());
        }
        request.setAttribute(SCOPE_ATTRIBUTE, target.scopes());
        Function<MutableHttpRequest<?>, Mono<HttpResponse>> send = req -> Mono.<HttpResponse>from(httpClient.exchange(req, bodyType));
        return authHandler.map(handler -> handler.executeWithAuth(request, send))
                .orElseGet(() -> send.apply(request))
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
        return new Registration(subscription.getId(), subscription.getExpiresAt());
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
