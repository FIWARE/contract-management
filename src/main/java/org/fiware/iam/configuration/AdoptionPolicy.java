package org.fiware.iam.configuration;

import io.micronaut.core.annotation.Nullable;

/**
 * Whether unattributed TIL entries may be adopted into the single removal publisher's data space; reason set only when REFUSED.
 */
public record AdoptionPolicy(State state, @Nullable RefusalReason reason) {

    public static final AdoptionPolicy OFF = new AdoptionPolicy(State.OFF, null);
    public static final AdoptionPolicy ALLOWED = new AdoptionPolicy(State.ALLOWED, null);

    public static AdoptionPolicy refused(RefusalReason reason) {
        return new AdoptionPolicy(State.REFUSED, reason);
    }

    public boolean isAllowed() {
        return state == State.ALLOWED;
    }

    /**
     * OFF: no removal publisher asks for adoption. REFUSED: one asks, but the configuration does not permit it.
     */
    public enum State {
        ALLOWED,
        REFUSED,
        OFF
    }

    /**
     * Why adoption is refused, checked in declaration order: the first failing check is reported.
     */
    public enum RefusalReason {
        MULTIPLE_PUBLISHERS("adoption requires exactly one publisher of participant.removed"),
        NO_DATASPACE_ID("adoption requires dataspace.events.dataspace-id"),
        NO_REGISTRY_URL("adoption requires the publisher's registry-url to list its participants");

        private final String logText;

        RefusalReason(String logText) {
            this.logText = logText;
        }

        public String logText() {
            return logText;
        }
    }
}
