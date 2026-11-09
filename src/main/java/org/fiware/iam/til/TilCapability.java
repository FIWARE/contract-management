package org.fiware.iam.til;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A trusted-issuers-list feature the participant-removed handling depends on; the list has no version endpoint, so each
 * one is decided by a side-effect-free probe.
 */
@Getter
@RequiredArgsConstructor
public enum TilCapability {

    /**
     * Typed scopes and the typed list filter (TIL-1).
     */
    TYPED_SCOPES("typed-scopes"),
    /**
     * {@code GET /issuer/{did}/scopes} (TIL-2).
     */
    SCOPES_ENDPOINT("scopes-endpoint"),
    /**
     * Addressing the unscoped credentials of an issuer (TIL-3).
     */
    UNSCOPED("unscoped");

    private final String id;

    /**
     * What a probe found out about a capability.
     */
    public enum State {
        SUPPORTED,
        /**
         * The list answered definitively without the capability; final until a restart or refresh.
         */
        TOO_OLD,
        /**
         * Not decided yet (no answer, or one that is no version signal); re-probed.
         */
        UNVERIFIED,
        /**
         * Only for {@link #SCOPES_ENDPOINT} on an empty list, which offers no issuer to probe with; does not block, re-probed.
         */
        PENDING
    }
}
