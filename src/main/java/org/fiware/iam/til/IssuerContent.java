package org.fiware.iam.til;

import org.fiware.iam.til.model.CredentialsVO;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One consistent read of an issuer: what it holds, and every credential behind it, whatever grants it.
 */
public record IssuerContent(HeldEntry held, List<CredentialsVO> credentials) {

    public IssuerContent {
        credentials = List.copyOf(credentials);
    }

    /**
     * True if the publisher's membership is the only grant, of as many credentials as are unattributed, and all credentials
     * split into two identical halves, as an adoption interrupted between its grant and its revoke leaves them. The list
     * does not say which credential the scope holds, so a membership {A, A} next to unattributed {B, B} matches as well.
     */
    public boolean holdsAdoptionCopyOf(String publisherId) {
        return held.mayHoldAdoptionCopyOf(publisherId)
                && credentials.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .values().stream()
                .allMatch(count -> count % 2 == 0);
    }
}
