package org.fiware.iam.dataspace;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.Ticker;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.time.Duration;

/**
 * Remembers received data space events by (source, id), so a publisher's retry is not dispatched twice.
 * Bounded by the refreshable dedup settings; a changed limit is applied to the live cache on the next begin.
 */
@Slf4j
@Singleton
public class EventDeduplicator {

    private final DataSpaceEventsProperties properties;
    private final Cache<Key, Seen> cache;
    private volatile Limits applied;

    @Inject
    public EventDeduplicator(DataSpaceEventsProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    EventDeduplicator(DataSpaceEventsProperties properties, Ticker ticker) {
        this.properties = properties;
        this.applied = Limits.of(properties.getListener().getDedup());
        this.cache = Caffeine.newBuilder()
                .maximumSize(applied.maxEntries())
                .expireAfterWrite(applied.ttl())
                .ticker(ticker)
                .build();
    }

    /**
     * Marks the event as in flight if it was not seen before; returns what was known about it before the call.
     */
    public Seen begin(Key key) {
        applyChangedLimits();
        Seen previous = cache.asMap().putIfAbsent(key, Seen.IN_FLIGHT);
        return previous == null ? Seen.NEW : previous;
    }

    /**
     * Marks an in-flight event as done. No-op if the entry is gone (e.g. evicted), so it is never re-added.
     */
    public void complete(Key key) {
        cache.asMap().replace(key, Seen.IN_FLIGHT, Seen.DONE);
    }

    /**
     * Drops an in-flight event, so the next retry is processed again.
     */
    public void forget(Key key) {
        cache.asMap().remove(key, Seen.IN_FLIGHT);
    }

    Policy<Key, Seen> policy() {
        return cache.policy();
    }

    private void applyChangedLimits() {
        Limits current = Limits.of(properties.getListener().getDedup());
        if (current.equals(applied)) {
            return;
        }
        synchronized (this) {
            if (current.equals(applied)) {
                return;
            }
            Policy<Key, Seen> policy = cache.policy();
            policy.eviction().ifPresent(eviction -> eviction.setMaximum(current.maxEntries()));
            policy.expireAfterWrite().ifPresent(expiration -> expiration.setExpiresAfter(current.ttl()));
            log.info("Data space event dedup cache now holds at most {} entries for {}.", current.maxEntries(), current.ttl());
            applied = current;
        }
    }

    /**
     * Identity of an event as defined by CloudEvents.
     */
    public record Key(String source, String id) {
    }

    /**
     * What is known about an event when it arrives.
     */
    public enum Seen {
        NEW, IN_FLIGHT, DONE
    }

    // invalid values (e.g. blanked by a refresh) fall back to the defaults instead of breaking every request
    private record Limits(int maxEntries, Duration ttl) {

        static Limits of(DataSpaceEventsProperties.Listener.Dedup dedup) {
            int maxEntries = dedup.getMaxEntries() > 0
                    ? dedup.getMaxEntries()
                    : DataSpaceEventsProperties.DEFAULT_DEDUP_MAX_ENTRIES;
            Duration ttl = dedup.getTtl() != null && dedup.getTtl().isPositive()
                    ? dedup.getTtl()
                    : DataSpaceEventsProperties.DEFAULT_DEDUP_TTL;
            return new Limits(maxEntries, ttl);
        }
    }
}
