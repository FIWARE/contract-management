package org.fiware.iam.dataspace;

import io.micronaut.core.annotation.Nullable;
import org.fiware.iam.configuration.DataSpaceEventsProperties.Publisher;
import org.fiware.iam.configuration.DataSpaceEventsProperties.SubscriberSettings;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of the effective values a subscription at one publisher is registered with.
 * The configuration beans are rebound in place on refresh, so this record is the only trace of what was sent.
 */
public record SubscriptionTarget(String publisherId, String subscriptionsUrl, List<String> types, @Nullable String origin,
                                 @Nullable String clientId, Set<String> scopes, String subscriberId, String sink,
                                 String protocolClientId, List<String> protocolScopes) {

    public SubscriptionTarget {
        types = copy(types);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        protocolScopes = copy(protocolScopes);
    }

    /**
     * Resolves the publisher's credential overrides; a blank client id or empty scopes fall back to {@code subscriber.*}.
     */
    public static SubscriptionTarget of(Publisher publisher, SubscriberSettings settings, String ownId) {
        String clientId = isBlank(publisher.getClientId()) ? settings.getClientId() : publisher.getClientId();
        List<String> scopes = isEmpty(publisher.getScopes()) ? settings.getScopes() : publisher.getScopes();
        SubscriberSettings.ProtocolSettings protocolSettings = settings.getProtocolSettings();
        return new SubscriptionTarget(publisher.getId(), publisher.getSubscriptionsUrl(), publisher.getTypes(),
                publisher.getOrigin(), clientId, scopes == null ? Set.of() : Set.copyOf(scopes), ownId, settings.getSink(),
                protocolSettings.getClientId(), protocolSettings.getScopes());
    }

    /**
     * Whether the publisher keys a registration with other the same way (subscriberId, sink, at the same URL), so it renews in place.
     */
    public boolean sameKeyAs(SubscriptionTarget other) {
        return Objects.equals(subscriptionsUrl, other.subscriptionsUrl) && Objects.equals(subscriberId, other.subscriberId)
                && Objects.equals(sink, other.sink);
    }

    private static List<String> copy(@Nullable List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static boolean isEmpty(@Nullable List<String> values) {
        return values == null || values.isEmpty();
    }
}
