package org.fiware.iam.dataspace;

import jakarta.inject.Singleton;

import java.util.function.Supplier;

/**
 * Serializes configuration refreshes. {@code Environment#refreshAndDiff()} is not thread-safe: an overlapping call can
 * diff against a half-reset catalog and swallow a real change, and a RefreshEvent rebind can read that catalog too.
 */
@Singleton
public class ConfigurationRefreshLock {

    private final Object lock = new Object();

    /**
     * Runs the refresh (re-read plus RefreshEvent) while no other refresh runs. Blocks the caller until then.
     */
    public <T> T exclusively(Supplier<T> refresh) {
        synchronized (lock) {
            return refresh.get();
        }
    }
}
