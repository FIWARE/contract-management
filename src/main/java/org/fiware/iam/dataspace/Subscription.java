package org.fiware.iam.dataspace;

import io.micronaut.core.annotation.Nullable;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * An activated subscription of a participant to data space events, delivered to {@code sink} until {@code expiresAt}.
 * {@code allowedRate} is the sink's announced limit in requests per minute; null means unlimited.
 */
public record Subscription(String id, String subscriberId, URI sink, List<String> types, String clientId,
                           Set<String> scopes, Instant expiresAt, @Nullable Integer allowedRate) {

    public Subscription {
        types = List.copyOf(types);
        scopes = Set.copyOf(scopes);
    }

    /**
     * The lease ends at {@code expiresAt}, exclusively.
     */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean matches(String type) {
        return types.contains(type);
    }
}
