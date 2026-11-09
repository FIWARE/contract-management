package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.DataSpaceEventsProperties.TirWatcherSettings;
import org.fiware.iam.configuration.GeneralProperties;

import java.util.Objects;

/**
 * Fails startup of a trust anchor watcher that could never announce a removal or whose settings are out of range.
 * Conditioned on the watcher flag alone: the watcher's own condition also needs the publisher, which is checked here.
 */
@Context
@Requires(property = TirWatcherStartupGuard.ENABLED_KEY, value = "true")
public class TirWatcherStartupGuard {

    static final String PREFIX = "dataspace.events.tir-watcher.";
    static final String ENABLED_KEY = PREFIX + "enabled";
    static final String PUBLISHER_ENABLED_KEY = "dataspace.events.publisher.enabled";
    static final String OWN_ID_KEY = "dataspace.events.own-participant-id";
    static final String INITIAL_DELAY_KEY = PREFIX + "initial-delay";
    static final String INTERVAL_KEY = PREFIX + "interval";
    static final String PAGE_SIZE_KEY = PREFIX + "page-size";
    static final String MAX_PAGES_KEY = PREFIX + "max-pages";
    static final String CONFIRMATIONS_KEY = PREFIX + "confirmations";
    static final String MAX_REMOVALS_PER_CYCLE_KEY = PREFIX + "max-removals-per-cycle";

    public TirWatcherStartupGuard(DataSpaceEventsProperties properties, GeneralProperties generalProperties) {
        if (!properties.getPublisher().isEnabled()) {
            throw new ConfigurationException("The trust anchor registry watcher needs %s=true, since it announces removals through the publisher."
                    .formatted(PUBLISHER_ENABLED_KEY));
        }
        String ownId = properties.ownId(generalProperties);
        if (ownId == null || ownId.isBlank()) {
            throw new ConfigurationException("The trust anchor registry watcher needs %s or general.did as the source of its events."
                    .formatted(OWN_ID_KEY));
        }
        TirWatcherSettings settings = properties.getTirWatcher();
        requireEffective(INITIAL_DELAY_KEY, settings.getInitialDelay(), settings.effectiveInitialDelay(), "a positive duration");
        requireEffective(INTERVAL_KEY, settings.getInterval(), settings.effectiveInterval(), "a positive duration");
        requireEffective(PAGE_SIZE_KEY, settings.getPageSize(), settings.effectivePageSize(),
                "between %s and %s".formatted(TirWatcherSettings.MIN_PAGE_SIZE, TirWatcherSettings.MAX_PAGE_SIZE));
        requireEffective(MAX_PAGES_KEY, settings.getMaxPages(), settings.effectiveMaxPages(),
                "at least " + TirWatcherSettings.MIN_MAX_PAGES);
        requireEffective(CONFIRMATIONS_KEY, settings.getConfirmations(), settings.effectiveConfirmations(),
                "at least " + TirWatcherSettings.MIN_CONFIRMATIONS);
        requireEffective(MAX_REMOVALS_PER_CYCLE_KEY, settings.getMaxRemovalsPerCycle(), settings.effectiveMaxRemovalsPerCycle(),
                "at least " + TirWatcherSettings.MIN_MAX_REMOVALS_PER_CYCLE);
    }

    // a refresh clamps such a value; at startup it is a configuration mistake
    private static void requireEffective(String key, Object configured, Object effective, String range) {
        if (!Objects.equals(configured, effective)) {
            throw new ConfigurationException("%s must be %s, but is %s.".formatted(key, range, configured));
        }
    }
}
