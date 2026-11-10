package org.fiware.iam.til;

import org.fiware.iam.til.model.CredentialsVO;

import java.util.List;

/**
 * One consistent read of an issuer: what it holds, and every credential behind it, whatever grants it.
 */
public record IssuerContent(HeldEntry held, List<CredentialsVO> credentials) {

    public IssuerContent {
        credentials = List.copyOf(credentials);
    }
}
