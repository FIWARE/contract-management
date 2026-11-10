package org.fiware.iam.til;

import org.fiware.iam.til.model.ClaimVO;
import org.fiware.iam.til.model.CredentialsVO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CredentialKeyTest {

    private static final String TYPE = "MembershipCredential";
    private static final ClaimVO ROLES = claim("roles", "READER", "WRITER");
    private static final ClaimVO REGIONS = claim("regions", "EU", "US");

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairs")
    void comparesRegardlessOfOrder(String description, CredentialsVO first, CredentialsVO second, boolean equal) {
        assertEquals(equal, CredentialKey.of(first).equals(CredentialKey.of(second)));
    }

    static Stream<Arguments> pairs() {
        return Stream.of(
                Arguments.of("same credential", credential(ROLES, REGIONS), credential(ROLES, REGIONS), true),
                Arguments.of("claims in another order", credential(ROLES, REGIONS), credential(REGIONS, ROLES), true),
                Arguments.of("allowed values in another order", credential(ROLES),
                        credential(claim("roles", "WRITER", "READER")), true),
                Arguments.of("no claims and an empty claim list", new CredentialsVO().credentialsType(TYPE),
                        credential(), true),
                Arguments.of("another allowed value", credential(ROLES), credential(claim("roles", "READER", "ADMIN")), false),
                Arguments.of("an allowed value twice", credential(ROLES),
                        credential(claim("roles", "READER", "WRITER", "WRITER")), false),
                Arguments.of("a claim twice", credential(ROLES), credential(ROLES, ROLES), false),
                Arguments.of("another type", credential(ROLES), credential(ROLES).credentialsType("OtherCredential"), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("multisets")
    void countsEachCredential(String description, List<CredentialsVO> first, List<CredentialsVO> second, boolean equal) {
        assertEquals(equal, CredentialKey.multiset(first).equals(CredentialKey.multiset(second)));
    }

    static Stream<Arguments> multisets() {
        return Stream.of(
                Arguments.of("copies in another order",
                        List.of(credential(ROLES, REGIONS), credential(ROLES)),
                        List.of(credential(ROLES), credential(REGIONS, ROLES)), true),
                Arguments.of("a copy missing",
                        List.of(credential(ROLES), credential(ROLES)), List.of(credential(ROLES)), false));
    }

    private static CredentialsVO credential(ClaimVO... claims) {
        return new CredentialsVO().credentialsType(TYPE).claims(Arrays.asList(claims));
    }

    private static ClaimVO claim(String name, Object... values) {
        return new ClaimVO().name(name).allowedValues(Arrays.asList(values));
    }
}
