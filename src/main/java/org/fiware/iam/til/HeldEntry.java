package org.fiware.iam.til;

import io.micronaut.core.annotation.Nullable;
import org.fiware.iam.til.model.ScopeTypeVO;

import java.util.List;
import java.util.Optional;

/**
 * What the trusted-issuers-list holds for one issuer: its grants, and the number of credentials no scope attributes.
 */
public record HeldEntry(String did, List<Grant> grants, int unattributedCount) {

    public HeldEntry {
        grants = List.copyOf(grants);
    }

    /**
     * The grant of the publisher's data-space membership, if held.
     */
    public Optional<Grant> membershipOf(String publisherId) {
        return grants.stream()
                .filter(Grant::isMembership)
                .filter(grant -> grant.scope().equals(publisherId))
                .findFirst();
    }

    /**
     * Data-space memberships granted by any other publisher.
     */
    public List<Grant> otherMemberships(String publisherId) {
        return grants.stream()
                .filter(Grant::isMembership)
                .filter(grant -> !grant.scope().equals(publisherId))
                .toList();
    }

    /**
     * The untyped grants, written by product orders.
     */
    public List<Grant> orderGrants() {
        return grants.stream().filter(Grant::isOrderGrant).toList();
    }

    /**
     * True if grants explain everything held. A mixed entry is not: its unattributed credentials can never be cleaned up.
     */
    public boolean isAttributable() {
        return !grants.isEmpty() && unattributedCount == 0;
    }

    /**
     * True if the entry holds nothing but unattributed credentials, so an announcement or adoption can claim it as a whole.
     */
    public boolean isUnattributedOnly() {
        return grants.isEmpty() && unattributedCount > 0;
    }

    /**
     * True if the counts fit an interrupted adoption: the publisher's membership is the only grant, next to as many
     * unattributed credentials. Only {@link IssuerContent#holdsAdoptionCopyOf} can tell from the credentials.
     */
    public boolean mayHoldAdoptionCopyOf(String publisherId) {
        return grants.size() == 1 && unattributedCount > 0
                && membershipOf(publisherId).filter(grant -> grant.count() == unattributedCount).isPresent();
    }

    /**
     * True if the issuer exists without any credential.
     */
    public boolean isEmpty() {
        return grants.isEmpty() && unattributedCount == 0;
    }

    /**
     * Credentials an issuer holds under one (scope, type) pair. A null type is an untyped (order) grant.
     */
    public record Grant(String scope, @Nullable ScopeTypeVO type, int count) {

        public boolean isMembership() {
            return type == ScopeTypeVO.DATASPACE_MEMBERSHIP;
        }

        public boolean isOrderGrant() {
            return type == null;
        }
    }
}
