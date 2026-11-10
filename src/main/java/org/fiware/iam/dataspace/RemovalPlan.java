package org.fiware.iam.dataspace;

import io.micronaut.core.annotation.Nullable;
import org.fiware.iam.configuration.RemovalMode;
import org.fiware.iam.til.HeldEntry;
import org.fiware.iam.til.model.ScopeTypeVO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The trusted-issuers-list writes one publisher's removal of a participant needs, in order, and the action they amount to.
 * <p>
 * The publisher's membership revoke always comes last, so an interrupted cleanup leaves an entry the next sweep finds
 * again. A plan that would leave an issuer without credentials ends with {@link DeleteIfEmpty} instead.
 */
public record RemovalPlan(RemovalAction action, List<Operation> operations) {

    static final RemovalPlan KEPT = new RemovalPlan(RemovalAction.KEPT, List.of());
    static final RemovalPlan NOT_HELD = new RemovalPlan(RemovalAction.NOT_HELD, List.of());
    static final RemovalPlan REPORTED = new RemovalPlan(RemovalAction.REPORTED, List.of());

    public RemovalPlan {
        operations = List.copyOf(operations);
    }

    /**
     * Plan the removal of the entry as read; an empty mode means keep. An entry of nothing but unattributed credentials is
     * revoked only for an event while adoption is allowed: the announcement then attributes it to the publisher. So is an
     * unattributed copy of the publisher's membership ({@code adoptionCopy}, see {@code IssuerContent#holdsAdoptionCopyOf}).
     */
    public static RemovalPlan of(Optional<RemovalMode> mode, Optional<HeldEntry> held, String publisherId,
                                 RemovalTrigger trigger, boolean adoptionAllowed, boolean adoptionCopy) {
        if (mode.isEmpty()) {
            return KEPT;
        }
        if (held.isEmpty()) {
            return NOT_HELD;
        }
        return switch (mode.get()) {
            case REPORT -> REPORTED;
            case FULL -> new RemovalPlan(RemovalAction.DELETED_ISSUER, List.of(new DeleteIssuer()));
            case DATASPACE, GRANTED -> cleanup(mode.get(), held.get(), publisherId,
                    trigger == RemovalTrigger.EVENT && adoptionAllowed, adoptionAllowed && adoptionCopy);
        };
    }

    /**
     * What the entry holds once every operation ran as planned.
     */
    public HeldEntry remainingAfter(HeldEntry entry) {
        HeldEntry remaining = entry;
        for (Operation operation : operations) {
            remaining = operation.applyTo(remaining);
        }
        return remaining;
    }

    private static RemovalPlan cleanup(RemovalMode mode, HeldEntry entry, String publisherId, boolean claimsUnattributedOnly,
                                       boolean adoptionCopy) {
        RemovalAction action = mode == RemovalMode.GRANTED ? RemovalAction.REVOKED_GRANTED : RemovalAction.REVOKED_MEMBERSHIP;
        if (entry.isEmpty()) {
            return new RemovalPlan(action, List.of(new DeleteIfEmpty(null)));
        }
        Optional<HeldEntry.Grant> membership = entry.membershipOf(publisherId);
        List<Operation> operations = new ArrayList<>();
        // order grants carry no data space: they belong to the publisher only through an entry it alone admitted
        if (mode == RemovalMode.GRANTED && membership.isPresent() && entry.otherMemberships(publisherId).isEmpty()) {
            entry.orderGrants().forEach(grant -> operations.add(new RevokeGrant(grant.scope(), grant.type())));
        }
        // an interrupted adoption's copy would otherwise keep the participant trusted once its membership is gone
        if (claimsUnattributedOnly && entry.isUnattributedOnly() || adoptionCopy && entry.mayHoldAdoptionCopyOf(publisherId)) {
            operations.add(new RevokeUnattributed());
        }
        membership.ifPresent(grant -> operations.add(new RevokeGrant(grant.scope(), grant.type())));
        if (operations.isEmpty()) {
            return REPORTED;
        }
        RemovalPlan plan = new RemovalPlan(action, operations);
        if (!plan.remainingAfter(entry).isEmpty()) {
            return plan;
        }
        int last = operations.size() - 1;
        operations.set(last, new DeleteIfEmpty(operations.get(last)));
        return new RemovalPlan(action, operations);
    }

    /**
     * One write to the trusted-issuers-list.
     */
    public sealed interface Operation permits RevokeGrant, RevokeUnattributed, DeleteIssuer, DeleteIfEmpty {

        /**
         * What the entry holds after this write.
         */
        HeldEntry applyTo(HeldEntry entry);
    }

    /**
     * Revoke exactly one (scope, type) grant.
     */
    public record RevokeGrant(String scope, @Nullable ScopeTypeVO type) implements Operation {

        @Override
        public HeldEntry applyTo(HeldEntry entry) {
            return new HeldEntry(entry.did(), entry.grants().stream()
                    .filter(grant -> !(grant.scope().equals(scope) && Objects.equals(grant.type(), type)))
                    .toList(), entry.unattributedCount());
        }
    }

    /**
     * Revoke the credentials no scope attributes.
     */
    public record RevokeUnattributed() implements Operation {

        @Override
        public HeldEntry applyTo(HeldEntry entry) {
            return new HeldEntry(entry.did(), entry.grants(), 0);
        }
    }

    /**
     * Delete the issuer with everything it holds.
     */
    public record DeleteIssuer() implements Operation {

        @Override
        public HeldEntry applyTo(HeldEntry entry) {
            return emptyEntry(entry.did());
        }
    }

    /**
     * Delete the issuer if, after the replaced write, nothing would remain on a fresh read; otherwise run the replaced
     * write. A null replaced write stands for an entry that was already empty, which is then left alone if it gained anything.
     */
    public record DeleteIfEmpty(@Nullable Operation replaced) implements Operation {

        @Override
        public HeldEntry applyTo(HeldEntry entry) {
            return deletes(entry) ? emptyEntry(entry.did()) : withoutReplaced(entry);
        }

        /**
         * The write to run instead of the delete, or empty if none may run against the fresh entry any more. Revoking
         * unattributed credentials claimed an entry holding nothing else, so it must not touch one that gained a grant.
         */
        public Optional<Operation> fallback(HeldEntry fresh) {
            if (replaced instanceof RevokeUnattributed && !fresh.isUnattributedOnly()) {
                return Optional.empty();
            }
            return Optional.ofNullable(replaced);
        }

        /**
         * True if the issuer, as freshly read, is to be deleted.
         */
        public boolean deletes(HeldEntry fresh) {
            return withoutReplaced(fresh).isEmpty();
        }

        private HeldEntry withoutReplaced(HeldEntry entry) {
            return replaced == null ? entry : replaced.applyTo(entry);
        }
    }

    /**
     * The entry of an issuer the list does not hold (any more).
     */
    static HeldEntry emptyEntry(String did) {
        return new HeldEntry(did, List.of(), 0);
    }
}
