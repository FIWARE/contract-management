package org.fiware.iam.dataspace;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.BeanInstantiationException;
import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.fiware.iam.dataspace.TirWatcherStartupGuard.CONFIRMATIONS_KEY;
import static org.fiware.iam.dataspace.TirWatcherStartupGuard.ENABLED_KEY;
import static org.fiware.iam.dataspace.TirWatcherStartupGuard.INITIAL_DELAY_KEY;
import static org.fiware.iam.dataspace.TirWatcherStartupGuard.INTERVAL_KEY;
import static org.fiware.iam.dataspace.TirWatcherStartupGuard.MAX_PAGES_KEY;
import static org.fiware.iam.dataspace.TirWatcherStartupGuard.MAX_REMOVALS_PER_CYCLE_KEY;
import static org.fiware.iam.dataspace.TirWatcherStartupGuard.OWN_ID_KEY;
import static org.fiware.iam.dataspace.TirWatcherStartupGuard.PAGE_SIZE_KEY;
import static org.fiware.iam.dataspace.TirWatcherStartupGuard.PUBLISHER_ENABLED_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that an enabled trust anchor watcher only starts with the publisher, an own id and in-range settings.
 */
class TirWatcherStartupGuardTest {

    private static final String OWN_ID = "did:web:trust-anchor.org";
    private static final String GENERAL_DID_KEY = "general.did";

    static Stream<Arguments> startingConfigurations() {
        return Stream.of(
                Arguments.of("watcher, publisher and own id", config(true, true, true), true),
                Arguments.of("own id from general.did", with(without(config(true, true, true), OWN_ID_KEY), GENERAL_DID_KEY, OWN_ID), true),
                Arguments.of("max-removals-per-cycle 0", with(config(true, true, true), MAX_REMOVALS_PER_CYCLE_KEY, "0"), true),
                Arguments.of("watcher off, publisher on", config(false, true, true), false),
                Arguments.of("watcher off, publisher off", config(false, false, true), false),
                Arguments.of("watcher off, no own id", config(false, false, false), false));
    }

    static Stream<Arguments> failingConfigurations() {
        return Stream.of(
                Arguments.of("publisher off", config(true, false, true), PUBLISHER_ENABLED_KEY),
                Arguments.of("publisher off, no own id", config(true, false, false), PUBLISHER_ENABLED_KEY),
                Arguments.of("no own id", config(true, true, false), OWN_ID_KEY),
                Arguments.of("blank own id", with(config(true, true, true), OWN_ID_KEY, " "), OWN_ID_KEY),
                Arguments.of("page-size 0", invalid(PAGE_SIZE_KEY, "0"), PAGE_SIZE_KEY),
                Arguments.of("page-size 101", invalid(PAGE_SIZE_KEY, "101"), PAGE_SIZE_KEY),
                Arguments.of("max-pages 0", invalid(MAX_PAGES_KEY, "0"), MAX_PAGES_KEY),
                Arguments.of("confirmations 0", invalid(CONFIRMATIONS_KEY, "0"), CONFIRMATIONS_KEY),
                Arguments.of("max-removals-per-cycle -1", invalid(MAX_REMOVALS_PER_CYCLE_KEY, "-1"), MAX_REMOVALS_PER_CYCLE_KEY),
                Arguments.of("interval PT0S", invalid(INTERVAL_KEY, "PT0S"), INTERVAL_KEY),
                Arguments.of("interval negative", invalid(INTERVAL_KEY, "-PT1M"), INTERVAL_KEY),
                Arguments.of("initial-delay PT0S", invalid(INITIAL_DELAY_KEY, "PT0S"), INITIAL_DELAY_KEY),
                Arguments.of("initial-delay negative", invalid(INITIAL_DELAY_KEY, "-PT1M"), INITIAL_DELAY_KEY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startingConfigurations")
    void starts(String description, Map<String, Object> config, boolean guardExpected) {
        try (ApplicationContext context = run(config)) {
            assertTrue(context.isRunning());
            assertEquals(guardExpected, context.containsBean(TirWatcherStartupGuard.class));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failingConfigurations")
    void failsStartup(String description, Map<String, Object> config, String offendingKey) {
        BeanInstantiationException exception = assertThrows(BeanInstantiationException.class, () -> run(config).close());
        ConfigurationException cause = assertInstanceOf(ConfigurationException.class, rootCause(exception));
        assertTrue(cause.getMessage().contains(offendingKey), cause.getMessage());
    }

    private static Map<String, Object> config(boolean watcherEnabled, boolean publisherEnabled, boolean ownIdSet) {
        Map<String, Object> config = new HashMap<>(Map.of(
                ENABLED_KEY, String.valueOf(watcherEnabled),
                PUBLISHER_ENABLED_KEY, String.valueOf(publisherEnabled)));
        if (ownIdSet) {
            config.put(OWN_ID_KEY, OWN_ID);
        }
        return config;
    }

    private static Map<String, Object> invalid(String key, String value) {
        return with(config(true, true, true), key, value);
    }

    private static Map<String, Object> with(Map<String, Object> config, String key, Object value) {
        Map<String, Object> changed = new HashMap<>(config);
        changed.put(key, value);
        return changed;
    }

    private static Map<String, Object> without(Map<String, Object> config, String key) {
        Map<String, Object> changed = new HashMap<>(config);
        changed.remove(key);
        return changed;
    }

    private static ApplicationContext run(Map<String, Object> config) {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "notification.enabled", false,
                "general.enable-rainbow", false,
                "micronaut.server.port", 8080,
                "endpoints.all.port", 9090,
                "dataspace.events.publisher.origin", "cm.trust-anchor.org"));
        properties.putAll(config);
        // without the test application.yaml, which enables further features
        return ApplicationContext.builder()
                .enableDefaultPropertySources(false)
                .properties(properties)
                .start();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
