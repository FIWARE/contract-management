package org.fiware.iam.dataspace;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.management.endpoint.annotation.Read;
import lombok.RequiredArgsConstructor;
import org.fiware.iam.configuration.AdoptionPolicy;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.til.HeldEntry;
import org.fiware.iam.til.TilCapabilities;
import org.fiware.iam.til.TilCapability;
import org.fiware.iam.til.model.ScopeTypeVO;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Internal {@code GET :9090/participants-removed}: per removal publisher, its policy state and the latest outcome of each
 * participant it removed. Unauthenticated, so it relies on the management port being cluster-internal (see
 * {@link ManagementPortGuard}); it shows which issuers this participant trusts under which scopes.
 */
@RequiredArgsConstructor
@Endpoint(id = RemovedParticipantsEndpoint.ID, defaultSensitive = false)
@Requires(condition = DataSpaceEventsProperties.ParticipantRemovedCondition.class)
public class RemovedParticipantsEndpoint {

    static final String ID = "participants-removed";

    private final DataSpaceEventsProperties properties;
    private final RemovedParticipantsReport report;
    private final TilCapabilities capabilities;
    private final ParticipantReconciler reconciler;

    /**
     * The configured removal publishers in configuration order, each with its participants seen most recently first.
     */
    @Read
    public List<PublisherReport> report() {
        TilState til = tilState();
        AdoptionPolicy adoption = properties.adoption();
        Set<String> tripped = reconciler.trippedBreakers().keySet();
        return properties.removalPublishers().stream()
                .map(publisher -> new PublisherReport(publisher.getId(),
                        publisher.getOnParticipantRemoved().effectiveMode().tagValue(),
                        til,
                        publisher.getOnParticipantRemoved().isAdoptUnattributed() ? AdoptionState.of(adoption) : AdoptionState.OFF,
                        tripped.contains(publisher.getId()) ? BreakerState.TRIPPED : BreakerState.CLEAR,
                        report.entries(publisher.getId()).reversed().stream().map(ParticipantReport::of).toList()))
                .toList();
    }

    // the TIL is shared by all publishers, so its least usable required capability decides for every one of them
    private TilState tilState() {
        Map<TilCapability, TilCapability.State> states = capabilities.state();
        return capabilities.required().stream()
                .map(capability -> TilState.of(states.getOrDefault(capability, TilCapability.State.UNVERIFIED)))
                .max(Comparator.naturalOrder())
                .orElse(TilState.SUPPORTED);
    }

    /**
     * One removal publisher: its own effective mode (without overrides), the shared TIL state, and its participants.
     */
    @Introspected
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PublisherReport(String publisher, String mode, TilState til, AdoptionState adoption,
                                  BreakerState breaker, List<ParticipantReport> participants) {
    }

    /**
     * Latest outcome for one participant. held, attributable and unattributedCount are null if the entry was not read;
     * an empty held with unattributedCount 0 means nothing is held, which counts as attributable (nothing left to explain).
     */
    @Introspected
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ParticipantReport(String id, String trigger,
                                    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastSeen, String mode,
                                    String action, boolean applied, @Nullable Boolean attributable, @Nullable List<HeldGrant> held,
                                    @Nullable Integer unattributedCount) {

        static ParticipantReport of(RemovedParticipantsReport.Entry entry) {
            RemovalOutcome outcome = entry.outcome();
            HeldEntry held = outcome.held();
            return new ParticipantReport(outcome.did(), outcome.trigger().logValue(), entry.lastSeen(), outcome.modeTag(),
                    outcome.action().tagValue(), outcome.applied(), entry.attributable(),
                    held == null ? null : held.grants().stream().map(HeldGrant::of).toList(),
                    held == null ? null : held.unattributedCount());
        }
    }

    /**
     * Credentials still held under one scope; scopeType null is an untyped (order) grant.
     */
    @Introspected
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HeldGrant(String scope, @Nullable ScopeTypeVO scopeType, int credentialCount) {

        static HeldGrant of(HeldEntry.Grant grant) {
            return new HeldGrant(grant.scope(), grant.type(), grant.count());
        }
    }

    /**
     * Usability of the TIL for this feature, declared from most to least usable.
     */
    public enum TilState {
        SUPPORTED,
        PENDING,
        UNVERIFIED,
        TOO_OLD;

        static TilState of(TilCapability.State state) {
            return switch (state) {
                case SUPPORTED -> SUPPORTED;
                case PENDING -> PENDING;
                case UNVERIFIED -> UNVERIFIED;
                case TOO_OLD -> TOO_OLD;
            };
        }

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * Adoption as it applies to one publisher: OFF unless the publisher asks for it.
     */
    public enum AdoptionState {
        ALLOWED,
        REFUSED,
        OFF;

        static AdoptionState of(AdoptionPolicy policy) {
            return switch (policy.state()) {
                case ALLOWED -> ALLOWED;
                case REFUSED -> REFUSED;
                case OFF -> OFF;
            };
        }

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The publisher's mass-removal breaker.
     */
    public enum BreakerState {
        TRIPPED,
        CLEAR;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
