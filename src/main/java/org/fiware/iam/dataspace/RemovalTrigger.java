package org.fiware.iam.dataspace;

import java.util.Locale;

/**
 * Where a participant's removal came from: a publisher's event, or the reconciliation sweep against its registry.
 */
public enum RemovalTrigger {
    EVENT,
    RECONCILIATION;

    public String logValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
