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
    private final PublisherRegistryLookup registryLookup;

    public ParticipantAdoption(TrustedIssuersListAdapter adapter, ParticipantRemoval removal,
                               PublisherRegistryLookup registryLookup) {
        this.adapter = adapter;
        this.removal = removal;
        this.registryLookup = registryLookup;
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
         * The originals were removed while the entry was adopted and the publisher no longer lists the participant, so the
         * grant that re-created the issuer was deleted again.
         */
        WITHDRAWN,
        /**
         * The originals were removed while the entry was adopted, but the publisher still lists the participant, so the
         * grant is kept (a migration by another component, or an operator's deletion that it undoes); not counted.
         */
        GRANT_KEPT,
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
    public Mono<Result> adopt(String publisherId, String registryUrl, String did, String modeTag) {
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
                    .then(Mono.defer(() -> complete(publisherId, registryUrl, did, modeTag, copied, false)));
        });
    }

    /**
     * Finish an adoption whose membership grant of the copied credentials may have been written: revoke the originals if
     * the issuer holds exactly both copies, after cutting the grant back to the originals still left. SKIPPED if it holds
     * no membership of the publisher, so a listed entry is adopted anew; {@code registryUrl} decides a withdrawal.
     * Fails with {@link AdoptionIncompleteException}; {@code revokeAttempted} carries over from its last one.
     */
    public Mono<Result> complete(String publisherId, String registryUrl, String did, String modeTag,
                                 List<CredentialsVO> copied, boolean revokeAttempted) {
        return Mono.defer(() -> adapter.content(did)).flatMap(found -> {
            if (found.isEmpty() || found.get().held().membershipOf(publisherId).isEmpty()) {
                return Mono.just(Result.SKIPPED);
            }
            if (holds(found.get(), publisherId, copied, 1)) {
                return revokeAttempted
                        // the revoke went through although its answer got lost
                        ? Mono.just(adopted(publisherId, did, modeTag, copied))
                        : withdraw(publisherId, registryUrl, did, modeTag, copied);
            }
            Optional<List<CredentialsVO>> remaining = stillUnattributed(found.get(), publisherId, copied);
            if (!revokeAttempted && remaining.filter(left -> !left.isEmpty() && left.size() < copied.size()).isPresent()) {
                return narrow(publisherId, registryUrl, did, modeTag, copied, remaining.get());
            }
            if (!holds(found.get(), publisherId, copied, 2)) {
                log.warn("Participant {} listed by {}: its entry changed while it was adopted, so its unattributed "
                                + "credentials are kept next to the new membership grant, which holds the copied credentials {}; "
                                + "resolve the duplicate manually, the grant may hold credentials removed since.",
                        did, publisherId, types(copied));
                return Mono.just(Result.DUPLICATE_LEFT);
            }
            return adapter.revokeUnattributed(did)
                    .onErrorMap(e -> new AdoptionIncompleteException(publisherId, did, copied, true, e))
                    .then(Mono.fromSupplier(() -> adopted(publisherId, did, modeTag, copied)));
        }).onErrorMap(e -> !(e instanceof AdoptionIncompleteException),
                e -> new AdoptionIncompleteException(publisherId, did, copied, revokeAttempted, e));
    }

    // the originals vanished before their revoke was sent: a removal deleted the issuer between the read and the grant,
    // which then re-created it, or another component moved them into the membership itself. Only the registry tells them
    // apart; a lookup failure leaves the entry to the next completion
    private Mono<Result> withdraw(String publisherId, String registryUrl, String did, String modeTag,
                                  List<CredentialsVO> copied) {
        return registryLookup.isListed(registryUrl, did).flatMap(listed -> listed
                ? Mono.just(grantKept(publisherId, did))
                : adapter.deleteIssuer(did).then(Mono.fromSupplier(() -> {
                    log.info("Participant {} is not adopted: its unattributed credentials were removed while it was adopted "
                            + "and {} no longer lists it, so the new membership grant is withdrawn.", did, publisherId);
                    return Result.WITHDRAWN;
                })));
    }

    // some originals were removed between the read and the grant, which restored them: the grant is cut back to what is
    // still unattributed. A failed re-grant keeps `copied`, so its next completion narrows again; one written with its
    // answer lost no longer matches `copied` and is left as a duplicate
    private Mono<Result> narrow(String publisherId, String registryUrl, String did, String modeTag,
                                List<CredentialsVO> copied, List<CredentialsVO> remaining) {
        log.info("Participant {} listed by {}: {} of its unattributed credentials were removed while it was adopted, so they "
                + "are dropped from the new membership grant.", did, publisherId, copied.size() - remaining.size());
        return adapter.grantMembership(did, publisherId, remaining)
                .onErrorMap(e -> new AdoptionIncompleteException(publisherId, did, copied, false, e))
                .then(Mono.defer(() -> complete(publisherId, registryUrl, did, modeTag, remaining, false)));
    }

    // indistinguishable at the TIL from a migration, so it is only flagged for the operator, not counted as an adoption
    private static Result grantKept(String publisherId, String did) {
        log.warn("Participant {}: its unattributed credentials disappeared while it was adopted; the membership grant written "
                + "by contract-management is kept because {} still lists the participant. Check whether that is intended: "
                + "if the issuer was deleted on purpose, delete it again.", did, publisherId);
        return Result.GRANT_KEPT;
    }

    private Result adopted(String publisherId, String did, String modeTag, List<CredentialsVO> copied) {
        log.info("Participant {} adopted into the data space of {}: its {} unattributed credentials are now its membership grant.",
                did, publisherId, copied.size());
        removal.count(publisherId, modeTag, RemovalAction.ADOPTED);
        return Result.ADOPTED;
    }

    // the credentials besides the copies, if the publisher's grant of the copies is the only grant and they are part of the
    // copies: then they are what is still unattributed of them
    private static Optional<List<CredentialsVO>> stillUnattributed(IssuerContent content, String publisherId,
                                                                   List<CredentialsVO> copied) {
        if (!content.held().grants().equals(grantOf(publisherId, copied))) {
            return Optional.empty();
        }
        Map<CredentialKey, Long> rest = new HashMap<>(CredentialKey.multiset(content.credentials()));
        for (CredentialsVO credential : copied) {
            if (rest.merge(CredentialKey.of(credential), -1L, Long::sum) < 0) {
                return Optional.empty();
            }
        }
        List<CredentialsVO> remaining = new ArrayList<>();
        for (CredentialsVO credential : copied) {
            CredentialKey key = CredentialKey.of(credential);
            if (rest.get(key) > 0) {
                rest.merge(key, -1L, Long::sum);
                remaining.add(credential);
            }
        }
        return rest.values().stream().allMatch(count -> count == 0) ? Optional.of(remaining) : Optional.empty();
    }

    private static List<HeldEntry.Grant> grantOf(String publisherId, List<CredentialsVO> copied) {
        return List.of(new HeldEntry.Grant(publisherId, ScopeTypeVO.DATASPACE_MEMBERSHIP, copied.size()));
    }

    private static List<String> types(List<CredentialsVO> credentials) {
        return credentials.stream().map(CredentialsVO::getCredentialsType).toList();
    }

    // the publisher's grant of the copies is the only grant, and the issuer holds the copied credentials `copies` times over
    private static boolean holds(IssuerContent content, String publisherId, List<CredentialsVO> copied, int copies) {
        List<HeldEntry.Grant> expectedGrants = grantOf(publisherId, copied);
        Map<CredentialKey, Long> expected = CredentialKey.multiset(copied);
        expected.replaceAll((credential, count) -> count * copies);
        return content.held().grants().equals(expectedGrants) && CredentialKey.multiset(content.credentials()).equals(expected);
    }
}
