package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.AdoptionPolicy;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.DataSpaceEventsProperties.ModeOverride;
import org.fiware.iam.configuration.DataSpaceEventsProperties.OnParticipantRemoved;
import org.fiware.iam.configuration.DataSpaceEventsProperties.ParticipantRemovedSettings;
import org.fiware.iam.configuration.DataSpaceEventsProperties.Publisher;
import org.fiware.iam.configuration.DataSpaceEventsProperties.TirWatcherSettings;
import org.fiware.iam.configuration.PublisherEntryCheck;
import org.fiware.iam.til.TilCapabilities;
import org.fiware.iam.til.TilCapability;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fails startup for an invalid participant.removed policy, and for a trusted-issuers-list too old for the configured writes.
 * A report-only configuration and an undecided (unreachable, empty) list only degrade; their state is re-probed later.
 */
@Slf4j
@Context
@Requires(condition = DataSpaceEventsProperties.ParticipantRemovedCondition.class)
public class ParticipantRemovedStartupGuard {

    static final String PREFIX = "dataspace.events.";
    static final String SETTINGS_PREFIX = PREFIX + "participant-removed.";
    static final String PAGE_SIZE_KEY = SETTINGS_PREFIX + "page-size";
    static final String MAX_PAGES_KEY = SETTINGS_PREFIX + "max-pages";
    static final String INITIAL_DELAY_KEY = SETTINGS_PREFIX + "reconcile.initial-delay";
    static final String INTERVAL_KEY = SETTINGS_PREFIX + "reconcile.interval";
    static final String NO_REGISTRY_URL_CONSEQUENCE =
            "without it there is no sweep, so a failed or dropped removal event is never completed";
    private static final Set<String> REGISTRY_URL_SCHEMES = Set.of("http", "https");

    @Inject
    public ParticipantRemovedStartupGuard(DataSpaceEventsProperties properties, TilCapabilities capabilities,
                                          Environment environment) {
        this(properties, capabilities,
                environment.getProperty(PublisherEntryCheck.PUBLISHERS_KEY, List.class).orElse(null));
    }

    // rawEntries are the unconverted publisher entries, so that messages name the configured index
    ParticipantRemovedStartupGuard(DataSpaceEventsProperties properties, TilCapabilities capabilities,
                                   @Nullable List<?> rawEntries) {
        checkSettings(properties.getParticipantRemoved());
        List<Publisher> publishers = properties.getPublishers();
        int[] rawIndexOf = PublisherEntryCheck.rawIndices(rawEntries, publishers);
        for (Publisher publisher : properties.removalPublishers()) {
            checkPolicy(policyKey(rawIndexOf[indexOf(publishers, publisher)]), publisher);
        }
        checkAdoption(properties);
        warnKeptAndOverridden(properties.removalPublishers());
        checkCapabilities(properties, capabilities.probeRequired().block());
    }

    private static void checkSettings(ParticipantRemovedSettings settings) {
        requireEffective(PAGE_SIZE_KEY, settings.getPageSize(), settings.effectivePageSize(),
                "between %s and %s".formatted(TirWatcherSettings.MIN_PAGE_SIZE, TirWatcherSettings.MAX_PAGE_SIZE));
        requireEffective(MAX_PAGES_KEY, settings.getMaxPages(), settings.effectiveMaxPages(),
                "at least " + ParticipantRemovedSettings.MIN_MAX_PAGES);
        requireEffective(INITIAL_DELAY_KEY, settings.getReconcile().getInitialDelay(),
                settings.getReconcile().effectiveInitialDelay(), "a positive duration");
        requireEffective(INTERVAL_KEY, settings.getReconcile().getInterval(), settings.getReconcile().effectiveInterval(),
                "a positive duration");
    }

    private static void checkPolicy(String key, Publisher publisher) {
        OnParticipantRemoved policy = publisher.getOnParticipantRemoved();
        List<ModeOverride> overrides = policy.getOverrides() == null ? List.of() : policy.getOverrides();
        for (int index = 0; index < overrides.size(); index++) {
            ModeOverride override = overrides.get(index);
            String overrideKey = "%s.overrides[%s]".formatted(key, index);
            if (override == null || override.getId() == null || override.getId().isBlank()) {
                throw new ConfigurationException("%s.id must name a participant, but is blank.".formatted(overrideKey));
            }
            if (override.getMode() == null) {
                throw new ConfigurationException("%s.mode must be report, dataspace, granted or full, but is not set (id %s)."
                        .formatted(overrideKey, override.getId()));
            }
        }
        requireEffective(key + ".max-removals-per-run", policy.getMaxRemovalsPerRun(), policy.effectiveMaxRemovalsPerRun(),
                "at least " + OnParticipantRemoved.MIN_MAX_REMOVALS_PER_RUN);
        requireEffective(key + ".max-adoptions-per-run", policy.getMaxAdoptionsPerRun(), policy.effectiveMaxAdoptionsPerRun(),
                "at least " + OnParticipantRemoved.MIN_MAX_ADOPTIONS_PER_RUN);
        String registryUrl = policy.getRegistryUrl();
        if (registryUrl == null || registryUrl.isBlank()) {
            if (policy.configuresWrites()) {
                throw new ConfigurationException("%s of %s configures a writing mode and requires registry-url: %s."
                        .formatted(key, publisher.getId(), NO_REGISTRY_URL_CONSEQUENCE));
            }
            return;
        }
        if (!isAbsoluteHttpUrl(registryUrl)) {
            throw new ConfigurationException("%s.registry-url must be an absolute http(s) URL, but is %s."
                    .formatted(key, registryUrl));
        }
    }

    private static void checkAdoption(DataSpaceEventsProperties properties) {
        AdoptionPolicy adoption = properties.adoption();
        if (adoption.state() != AdoptionPolicy.State.REFUSED) {
            return;
        }
        List<String> asking = properties.removalPublishers().stream()
                .filter(publisher -> publisher.getOnParticipantRemoved().isAdoptUnattributed())
                .map(Publisher::getId)
                .toList();
        if (adoption.reason() == AdoptionPolicy.RefusalReason.NO_REGISTRY_URL) {
            throw new ConfigurationException("adopt-unattributed requires registry-url: adoption lists %s's participants from it."
                    .formatted(String.join(", ", asking)));
        }
        log.warn("adopt-unattributed of {} is ignored, no unattributed entry is adopted: {}.", asking, adoption.reason().logText());
    }

    private static void warnKeptAndOverridden(List<Publisher> publishers) {
        List<String> kept = publishers.stream()
                .flatMap(publisher -> nullToEmpty(publisher.getOnParticipantRemoved().getKeep()).stream())
                .toList();
        publishers.forEach(publisher -> nullToEmpty(publisher.getOnParticipantRemoved().getOverrides()).stream()
                .map(ModeOverride::getId)
                .filter(kept::contains)
                .distinct()
                .forEach(did -> log.warn("{} is on a keep list and has an override at {}; keep wins, the override never applies.",
                        did, publisher.getId())));
    }

    private static void checkCapabilities(DataSpaceEventsProperties properties,
                                          @Nullable Map<TilCapability, TilCapability.State> states) {
        if (states == null) {
            return;
        }
        List<String> tooOld = new ArrayList<>();
        states.forEach((capability, state) -> {
            switch (state) {
                case TOO_OLD -> tooOld.add("%s (%s)".formatted(capability.getId(), ticketOf(capability)));
                case UNVERIFIED -> log.warn("The trusted-issuers-list could not be checked for {}; participant.removed "
                        + "events are answered 502 and no sweep runs until a later probe succeeds.", capability.getId());
                case PENDING -> log.info("The trusted-issuers-list is empty, so {} is checked once it holds an issuer.",
                        capability.getId());
                case SUPPORTED -> {
                }
            }
        });
        if (tooOld.isEmpty()) {
            return;
        }
        if (properties.removalWritesConfigured()) {
            throw new ConfigurationException(("The trusted-issuers-list is too old for %s: it lacks %s. Upgrade it, or set "
                    + "every on-participant-removed mode and override to report and disable adopt-unattributed.")
                    .formatted(writesConfigured(properties), String.join(", ", tooOld)));
        }
        // TilCapabilities already logged the ERROR for the missing capability; this names the consequence only
        log.warn("Participant removals are only reported, without what the removed participants hold, and no "
                + "reconciliation runs until the trusted-issuers-list supports {}.", String.join(", ", tooOld));
    }

    // what makes the configuration write, named for the start-up failure
    private static String writesConfigured(DataSpaceEventsProperties properties) {
        List<String> writes = new ArrayList<>();
        for (Publisher publisher : properties.removalPublishers()) {
            OnParticipantRemoved policy = publisher.getOnParticipantRemoved();
            if (policy.getMode() != null && policy.getMode().writes()) {
                writes.add("mode %s of %s".formatted(policy.getMode().tagValue(), publisher.getId()));
            }
            nullToEmpty(policy.getOverrides()).stream()
                    .filter(override -> override.getMode() != null && override.getMode().writes())
                    .forEach(override -> writes.add("override %s for %s of %s".formatted(override.getMode().tagValue(),
                            override.getId(), publisher.getId())));
        }
        if (properties.adoption().isAllowed()) {
            writes.add("adopt-unattributed");
        }
        return String.join(", ", writes);
    }

    private static String ticketOf(TilCapability capability) {
        return switch (capability) {
            case TYPED_SCOPES -> "TIL-1";
            case SCOPES_ENDPOINT -> "TIL-2";
            case UNSCOPED -> "TIL-3";
        };
    }

    private static String policyKey(int index) {
        return "%s[%s].%s".formatted(PublisherEntryCheck.PUBLISHERS_KEY, index, PublisherEntryCheck.POLICY_KEY);
    }

    // by identity: equal entries (Lombok equals) repeating an id would otherwise share the first one's index
    private static int indexOf(List<Publisher> publishers, Publisher publisher) {
        for (int index = 0; index < publishers.size(); index++) {
            if (publishers.get(index) == publisher) {
                return index;
            }
        }
        throw new IllegalStateException("A removal publisher is not among the publishers: " + publisher.getId());
    }

    private static boolean isAbsoluteHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            return uri.getScheme() != null && REGISTRY_URL_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static <T> List<T> nullToEmpty(@Nullable List<T> list) {
        return list == null ? List.of() : list;
    }

    // a refresh clamps such a value; at startup it is a configuration mistake
    private static void requireEffective(String key, @Nullable Object configured, Object effective, String range) {
        if (!Objects.equals(configured, effective)) {
            throw new ConfigurationException("%s must be %s, but is %s.".formatted(key, range, configured));
        }
    }
}
