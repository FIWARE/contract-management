package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.exception.AdoptionIncompleteException;
import org.fiware.iam.til.CredentialKey;
import org.fiware.iam.til.HeldEntry;
import org.fiware.iam.til.IssuerContent;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.til.model.ScopeTypeVO;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

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

    public ParticipantAdoption(TrustedIssuersListAdapter adapter, ParticipantRemoval removal) {
        this.adapter = adapter;
        this.removal = removal;
    }

    /**
     * What an adoption attempt did.
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
         * The originals were removed while the entry was adopted, so the grant that re-created the issuer was deleted again.
         */
        WITHDRAWN,
        /**
         * Nothing left to complete: the entry is gone, or holds no membership of the publisher (never written, or revoked
         * by a removal since); a listed entry is adopted anew.
         */
        SKIPPED
    }

    /**
     * Adopt the participant's entry if it holds nothing but unattributed credentials. A failure from the grant on is an
     * {@link AdoptionIncompleteException}, to be finished by {@link #complete}; neither is logged here nor counted.
     */
    public Mono<Result> adopt(String publisherId, String did, String modeTag) {
        return Mono.defer(() -> adapter.content(did)).flatMap(found -> {
            if (found.isEmpty()) {
                return Mono.just(Result.SKIPPED);
            }
            HeldEntry held = found.get().held();
            if (!held.isUnattributedOnly()) {
                if (held.unattributedCount() > 0) {
                    log.info("Participant {} listed by {} is not adopted: its {} unattributed credentials sit next to grants "
                            + "and cannot be split from them.", did, publisherId, held.unattributedCount());
                }
                return Mono.just(Result.NOT_ADOPTABLE);
            }
            List<CredentialsVO> copied = found.get().credentials();
            // the membership is written before the originals are revoked, so an interruption leaves a duplicate, never a loss;
            // a failed grant may still have been written, so it is completed like a failed revoke
            return adapter.grantMembership(did, publisherId, copied)
                    .onErrorMap(e -> new AdoptionIncompleteException(publisherId, did, copied, false, e))
                    .then(Mono.defer(() -> complete(publisherId, did, modeTag, copied, false)));
        });
    }

    /**
     * Finish an adoption whose membership grant of the copied credentials may have been written: revoke the originals if
     * the issuer holds exactly both copies. SKIPPED if it holds no membership of the publisher, so a listed entry is
     * adopted anew. Fails with {@link AdoptionIncompleteException}; {@code revokeAttempted} carries over from its last one.
     */
    public Mono<Result> complete(String publisherId, String did, String modeTag, List<CredentialsVO> copied,
                                 boolean revokeAttempted) {
        return Mono.defer(() -> adapter.content(did)).flatMap(found -> {
            if (found.isEmpty() || found.get().held().membershipOf(publisherId).isEmpty()) {
                return Mono.just(Result.SKIPPED);
            }
            if (holds(found.get(), publisherId, copied, 1)) {
                return revokeAttempted
                        // the revoke went through although its answer got lost
                        ? Mono.just(adopted(publisherId, did, modeTag, copied))
                        : withdraw(publisherId, did);
            }
            if (!holds(found.get(), publisherId, copied, 2)) {
                log.warn("Participant {} listed by {}: its entry changed while it was adopted, so its unattributed "
                                + "credentials are kept next to the new membership grant; resolve the duplicate manually.",
                        did, publisherId);
                return Mono.just(Result.DUPLICATE_LEFT);
            }
            return adapter.revokeUnattributed(did)
                    .onErrorMap(e -> new AdoptionIncompleteException(publisherId, did, copied, true, e))
                    .then(Mono.fromSupplier(() -> adopted(publisherId, did, modeTag, copied)));
        }).onErrorMap(e -> !(e instanceof AdoptionIncompleteException),
                e -> new AdoptionIncompleteException(publisherId, did, copied, revokeAttempted, e));
    }

    // the originals vanished before their revoke was sent, e.g. a removal deleted the issuer between the read and the
    // grant, which then re-created it: the grant alone is left, so the issuer is deleted rather than trusted again
    private Mono<Result> withdraw(String publisherId, String did) {
        return adapter.deleteIssuer(did).then(Mono.fromSupplier(() -> {
            log.info("Participant {} listed by {} is not adopted: its unattributed credentials were removed while it was "
                    + "adopted, so the new membership grant is withdrawn.", did, publisherId);
            return Result.WITHDRAWN;
        }));
    }

    private Result adopted(String publisherId, String did, String modeTag, List<CredentialsVO> copied) {
        log.info("Participant {} adopted into the data space of {}: its {} unattributed credentials are now its membership grant.",
                did, publisherId, copied.size());
        removal.count(publisherId, modeTag, RemovalAction.ADOPTED);
        return Result.ADOPTED;
    }

    // the publisher's grant of the copies is the only grant, and the issuer holds the copied credentials `copies` times over
    private static boolean holds(IssuerContent content, String publisherId, List<CredentialsVO> copied, int copies) {
        List<HeldEntry.Grant> expectedGrants = List.of(new HeldEntry.Grant(publisherId, ScopeTypeVO.DATASPACE_MEMBERSHIP,
                copied.size()));
        Map<CredentialKey, Long> expected = CredentialKey.multiset(copied);
        expected.replaceAll((credential, count) -> count * copies);
        return content.held().grants().equals(expectedGrants) && CredentialKey.multiset(content.credentials()).equals(expected);
    }
}
