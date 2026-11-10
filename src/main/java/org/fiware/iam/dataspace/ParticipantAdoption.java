package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.til.HeldEntry;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.til.model.ScopeTypeVO;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adopts an entry of nothing but unattributed credentials into the publisher's data space: copies them into its
 * membership grant, then revokes the unattributed originals. A failure or a concurrent change leaves a duplicate, never a loss.
 */
@Slf4j
@Singleton
@Requires(condition = DataSpaceEventsProperties.ParticipantRemovedCondition.class)
public class ParticipantAdoption {

    private final TrustedIssuersListAdapter adapter;
    private final ParticipantRemoval removal;
    private final RemovedParticipantsReport report;

    public ParticipantAdoption(TrustedIssuersListAdapter adapter, ParticipantRemoval removal, RemovedParticipantsReport report) {
        this.adapter = adapter;
        this.removal = removal;
        this.report = report;
    }

    /**
     * What an adoption attempt did. Only ADOPTED and DUPLICATE_LEFT wrote to the list.
     */
    public enum Result {
        ADOPTED,
        /**
         * The membership was granted, but the entry changed before the originals were revoked; both copies are kept.
         */
        DUPLICATE_LEFT,
        /**
         * The entry holds grants, so it is not the publisher's alone (or cannot be split).
         */
        NOT_ADOPTABLE,
        /**
         * The entry is gone or changed while it was read; nothing written, worth another try.
         */
        SKIPPED
    }

    /**
     * Adopt the participant's entry if it holds nothing but unattributed credentials. Fails with the failing call; the
     * failure is neither logged nor counted, that is left to the caller.
     */
    public Mono<Result> adopt(String publisherId, String did, String modeTag) {
        return Mono.defer(() -> adapter.heldEntry(did)).flatMap(held -> {
            if (held.isEmpty()) {
                return Mono.just(Result.SKIPPED);
            }
            if (!held.get().isUnattributedOnly()) {
                if (held.get().unattributedCount() > 0) {
                    log.info("Participant {} listed by {} is not adopted: its {} unattributed credentials sit next to grants "
                            + "and cannot be split from them.", did, publisherId, held.get().unattributedCount());
                }
                return Mono.just(Result.NOT_ADOPTABLE);
            }
            return adapter.credentials(did).flatMap(credentials -> credentials.isPresent()
                    && credentials.get().size() == held.get().unattributedCount()
                    ? grant(publisherId, did, modeTag, credentials.get())
                    : Mono.just(Result.SKIPPED));
        });
    }

    // the membership is written before the originals are revoked, so an interruption leaves a duplicate, never a loss
    private Mono<Result> grant(String publisherId, String did, String modeTag, List<CredentialsVO> copied) {
        return adapter.grantMembership(did, publisherId, copied)
                .then(adapter.heldEntry(did))
                .flatMap(held -> adapter.credentials(did).map(credentials -> isDuplicated(held, credentials, publisherId, copied)))
                .flatMap(duplicated -> {
                    if (!duplicated) {
                        log.warn("Participant {} listed by {}: its entry changed while it was adopted, so its unattributed "
                                + "credentials are kept next to the new membership grant; resolve the duplicate manually.",
                                did, publisherId);
                        return Mono.just(Result.DUPLICATE_LEFT);
                    }
                    return adapter.revokeUnattributed(did).then(Mono.fromSupplier(() -> adopted(publisherId, did, modeTag, copied)));
                });
    }

    private Result adopted(String publisherId, String did, String modeTag, List<CredentialsVO> copied) {
        HeldEntry held = new HeldEntry(did, List.of(new HeldEntry.Grant(publisherId, ScopeTypeVO.DATASPACE_MEMBERSHIP,
                copied.size())), 0);
        log.info("Participant {} adopted into the data space of {}: its {} unattributed credentials are now its membership grant.",
                did, publisherId, copied.size());
        report.record(new RemovalOutcome(publisherId, did, RemovalTrigger.RECONCILIATION, modeTag, RemovalAction.ADOPTED,
                true, held));
        removal.count(publisherId, modeTag, RemovalAction.ADOPTED);
        return Result.ADOPTED;
    }

    // exactly the publisher's new grant next to the unchanged originals: the issuer holds the copied credentials twice
    private static boolean isDuplicated(Optional<HeldEntry> held, Optional<List<CredentialsVO>> credentials,
                                        String publisherId, List<CredentialsVO> copied) {
        if (held.isEmpty() || credentials.isEmpty()) {
            return false;
        }
        List<HeldEntry.Grant> expectedGrants = List.of(new HeldEntry.Grant(publisherId, ScopeTypeVO.DATASPACE_MEMBERSHIP,
                copied.size()));
        List<CredentialsVO> twice = new ArrayList<>(copied);
        twice.addAll(copied);
        return held.get().grants().equals(expectedGrants)
                && held.get().unattributedCount() == copied.size()
                && multiset(credentials.get()).equals(multiset(twice));
    }

    private static Map<CredentialsVO, Integer> multiset(List<CredentialsVO> credentials) {
        Map<CredentialsVO, Integer> counts = new HashMap<>();
        credentials.forEach(credential -> counts.merge(credential, 1, Integer::sum));
        return counts;
    }
}
