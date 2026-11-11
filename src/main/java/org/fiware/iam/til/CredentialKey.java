package org.fiware.iam.til;

import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.til.model.TimeRangeVO;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * A credential compared regardless of the order of its claims and their values, which the list does not keep.
 */
public record CredentialKey(TimeRangeVO validFor, String credentialsType, Map<ClaimKey, Long> claims) {

    /**
     * A claim compared regardless of the order of its allowed values.
     */
    public record ClaimKey(String name, String path, Map<Object, Long> allowedValues) {
    }

    public static CredentialKey of(CredentialsVO credential) {
        return new CredentialKey(credential.getValidFor(), credential.getCredentialsType(),
                multisetOf(credential.getClaims(), claim -> new ClaimKey(claim.getName(), claim.getPath(),
                        multisetOf(claim.getAllowedValues(), Function.identity()))));
    }

    /**
     * How often each credential occurs.
     */
    public static Map<CredentialKey, Long> multiset(Collection<CredentialsVO> credentials) {
        return multisetOf(credentials, CredentialKey::of);
    }

    private static <T, K> Map<K, Long> multisetOf(Collection<T> elements, Function<T, K> key) {
        // a HashMap, as allowed values may be null
        Map<K, Long> counts = new HashMap<>();
        Optional.ofNullable(elements).orElse(List.of()).forEach(element -> counts.merge(key.apply(element), 1L, Long::sum));
        return counts;
    }
}
