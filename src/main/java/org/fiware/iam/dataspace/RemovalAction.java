package org.fiware.iam.dataspace;

import java.util.Locale;

/**
 * What the handling of a removed participant did; the bounded values of the metric's action tag.
 */
public enum RemovalAction {
    /**
     * Nothing written: report mode, or the entry holds nothing attributable to the publisher.
     */
    REPORTED,
    /**
     * Nothing read or written: the identifier is on a keep list or is the own id.
     */
    KEPT,
    NOT_HELD,
    /**
     * The publisher's registry lists the identifier again, so the removal is stale.
     */
    STILL_LISTED,
    REVOKED_MEMBERSHIP,
    REVOKED_GRANTED,
    DELETED_ISSUER,
    ADOPTED,
    ADOPTION_REFUSED,
    FAILED,
    BREAKER;

    public String tagValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
