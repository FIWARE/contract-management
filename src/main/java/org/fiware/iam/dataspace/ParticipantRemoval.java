package org.fiware.iam.dataspace;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.configuration.RemovalMode;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.ParticipantRemovalException;
import org.fiware.iam.exception.TrustedIssuersException;
import org.fiware.iam.til.HeldEntry;
import org.fiware.iam.til.TilCapabilities;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Applies one publisher's removal of a participant to the trusted-issuers-list, for events and the reconciliation alike.
 * Every decision is logged, recorded in the {@link RemovedParticipantsReport} and counted in {@value #METRIC_NAME}.
 */
@Slf4j
@Singleton
@Requires(condition = DataSpaceEventsProperties.ParticipantRemovedCondition.class)
public class ParticipantRemoval {

    static final String METRIC_NAME = "dsc.dataspace.events.participant.removed";
    static final String PUBLISHER_TAG = "publisher";
    static final String MODE_TAG = "mode";
    static final String ACTION_TAG = "action";
    private static final String UNKNOWN_HELD = "unknown";

    private final TrustedIssuersListAdapter adapter;
    private final TilCapabilities capabilities;
    private final PublisherRegistryLookup registryLookup;
    private final RemovedParticipantsReport report;
    private final DataSpaceEventsProperties properties;
    private final GeneralProperties generalProperties;
    private final MeterRegistry meterRegistry;

    public ParticipantRemoval(TrustedIssuersListAdapter adapter, TilCapabilities capabilities,
                              PublisherRegistryLookup registryLookup, RemovedParticipantsReport report,
                              DataSpaceEventsProperties properties, GeneralProperties generalProperties,
                              Optional<MeterRegistry> meterRegistry) {
        this.adapter = adapter;
        this.capabilities = capabilities;
        this.registryLookup = registryLookup;
        this.report = report;
        this.properties = properties;
        this.generalProperties = generalProperties;
        this.meterRegistry = meterRegistry.orElseGet(CompositeMeterRegistry::new);
    }

    /**
     * Decide and apply the removal. Fails with a {@link ParticipantRemovalException} whose cause is the failing call; the
     * failure is counted but not logged, that is left to the caller.
     */
    public Mono<RemovalOutcome> apply(String publisherId, String did, RemovalTrigger trigger) {
        return Mono.defer(() -> {
            String ownId = properties.ownId(generalProperties);
            Optional<RemovalMode> mode = properties.removalMode(publisherId, did, ownId);
            if (mode.isEmpty()) {
                String keptBy = did.equals(ownId) ? "it is this participant" : "it is on the keep list of " + properties.keptBy(did).orElse(publisherId);
                return Mono.just(finish(new RemovalOutcome(publisherId, did, trigger, RemovalMode.KEEP_TAG_VALUE,
                        RemovalAction.KEPT, false, null), keptBy));
            }
            RemovalMode removalMode = mode.get();
            return capabilities.requireSupported()
                    .thenReturn(true)
                    // report writes nothing, so an old list only costs it the read of what is held
                    .onErrorResume(e -> !removalMode.writes() && isTooOld(e), e -> Mono.just(false))
                    .flatMap(supported -> supported
                            ? decide(publisherId, did, trigger, removalMode)
                            : Mono.just(new RemovalOutcome(publisherId, did, trigger, removalMode.tagValue(),
                            RemovalAction.REPORTED, false, null)))
                    .map(outcome -> finish(outcome, null))
                    .onErrorMap(e -> {
                        count(publisherId, removalMode.tagValue(), RemovalAction.FAILED);
                        report.record(new RemovalOutcome(publisherId, did, trigger, removalMode.tagValue(),
                                RemovalAction.FAILED, false, null));
                        return new ParticipantRemovalException(publisherId, did, e);
                    });
        });
    }

    private Mono<RemovalOutcome> decide(String publisherId, String did, RemovalTrigger trigger, RemovalMode mode) {
        // the TIL keeps no grant timestamps, so only the registry can tell a late event about a re-admitted participant
        Mono<Boolean> listedAgain = mode.writes() && trigger == RemovalTrigger.EVENT
                ? registryLookup.isListed(registryUrl(publisherId), did)
                : Mono.just(false);
        return listedAgain.flatMap(listed -> listed
                ? Mono.just(new RemovalOutcome(publisherId, did, trigger, mode.tagValue(), RemovalAction.STILL_LISTED, false, null))
                : adapter.heldEntry(did).flatMap(held -> cleanup(publisherId, did, trigger, mode, held)));
    }

    private Mono<RemovalOutcome> cleanup(String publisherId, String did, RemovalTrigger trigger, RemovalMode mode,
                                         Optional<HeldEntry> held) {
        RemovalPlan plan = RemovalPlan.of(Optional.of(mode), held, publisherId, trigger, properties.adoption().isAllowed());
        HeldEntry before = held.orElseGet(() -> RemovalPlan.emptyEntry(did));
        if (plan.operations().isEmpty()) {
            return Mono.just(new RemovalOutcome(publisherId, did, trigger, mode.tagValue(), plan.action(), false, before));
        }
        // sequential, so the publisher's membership revoke (always last) runs only once everything before it succeeded
        Mono<HeldEntry> state = Mono.just(before);
        for (RemovalPlan.Operation operation : plan.operations()) {
            state = state.flatMap(current -> run(did, operation, current));
        }
        return state.map(after -> new RemovalOutcome(publisherId, did, trigger, mode.tagValue(), plan.action(), true, after));
    }

    private Mono<HeldEntry> run(String did, RemovalPlan.Operation operation, HeldEntry current) {
        return switch (operation) {
            case RemovalPlan.RevokeGrant revoke -> adapter.revokeGrant(did, revoke.scope(), revoke.type())
                    .thenReturn(operation.applyTo(current));
            case RemovalPlan.RevokeUnattributed ignored -> adapter.revokeUnattributed(did).thenReturn(operation.applyTo(current));
            case RemovalPlan.DeleteIssuer ignored -> adapter.deleteIssuer(did).thenReturn(RemovalPlan.emptyEntry(did));
            // re-read right before the delete, so whatever was granted since the first read survives
            case RemovalPlan.DeleteIfEmpty deleteIfEmpty -> adapter.heldEntry(did).flatMap(fresh -> {
                if (fresh.isEmpty() || deleteIfEmpty.deletes(fresh.get())) {
                    return adapter.deleteIssuer(did).thenReturn(RemovalPlan.emptyEntry(did));
                }
                return deleteIfEmpty.replaced() == null
                        ? Mono.just(fresh.get())
                        : run(did, deleteIfEmpty.replaced(), fresh.get());
            });
        };
    }

    private RemovalOutcome finish(RemovalOutcome outcome, @Nullable String reason) {
        log.info("Participant {} removed by {} (mode {}, {}): {}{}; still held: {}", outcome.did(), outcome.publisherId(),
                outcome.modeTag(), outcome.trigger().logValue(), outcome.action().tagValue(),
                reason == null ? "" : " (" + reason + ")", describe(outcome.held()));
        report.record(outcome);
        count(outcome.publisherId(), outcome.modeTag(), outcome.action());
        return outcome;
    }

    private void count(String publisherId, String modeTag, RemovalAction action) {
        meterRegistry.counter(METRIC_NAME, PUBLISHER_TAG, publisherId, MODE_TAG, modeTag, ACTION_TAG, action.tagValue())
                .increment();
    }

    private String registryUrl(String publisherId) {
        return properties.removalPublishers().stream()
                .filter(publisher -> publisher.getId().equals(publisherId))
                .findFirst()
                .map(publisher -> publisher.getOnParticipantRemoved().getRegistryUrl())
                .orElseThrow(() -> new IllegalStateException("No registry-url for the writing publisher " + publisherId));
    }

    private static boolean isTooOld(Throwable e) {
        return e instanceof TrustedIssuersException tie && tie.getReason() == FailureReason.TIL_TOO_OLD;
    }

    private static String describe(@Nullable HeldEntry held) {
        if (held == null) {
            return UNKNOWN_HELD;
        }
        String grants = held.grants().stream()
                .map(grant -> "%s (%s)".formatted(grant.scope(), grant.isOrderGrant() ? "order" : grant.type()))
                .collect(Collectors.joining(", ", "[", "]"));
        return held.unattributedCount() == 0 ? grants : grants + " + " + held.unattributedCount() + " unattributed";
    }
}
