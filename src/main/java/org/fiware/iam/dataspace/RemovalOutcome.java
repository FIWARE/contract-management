package org.fiware.iam.dataspace;

import io.micronaut.core.annotation.Nullable;
import org.fiware.iam.til.HeldEntry;

/**
 * How one publisher's removal of a participant was handled. The mode tag is a lower-case mode or "keep"; held is what the
 * trusted-issuers-list holds afterwards (or as read, if nothing was applied), null if it was not read.
 */
public record RemovalOutcome(String publisherId, String did, RemovalTrigger trigger, String modeTag,
                             RemovalAction action, boolean applied, @Nullable HeldEntry held) {
}
