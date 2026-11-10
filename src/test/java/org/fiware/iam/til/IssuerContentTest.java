package org.fiware.iam.til;

import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.til.model.ScopeTypeVO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IssuerContentTest {

    private static final String P1 = "did:web:p1.org";
    private static final String P2 = "did:web:p2.org";
    private static final String X = "did:web:x.org";
    private static final CredentialsVO MEMBERSHIP = new CredentialsVO().credentialsType("MembershipCredential");
    private static final CredentialsVO OPERATOR = new CredentialsVO().credentialsType("OperatorCredential");
    private static final List<CredentialsVO> COPIED = List.of(MEMBERSHIP, OPERATOR);

    @ParameterizedTest(name = "{0}")
    @MethodSource("contents")
    void recognisesTheCopyAnInterruptedAdoptionLeaves(String description, IssuerContent content, boolean copy) {
        assertEquals(copy, content.holdsAdoptionCopyOf(P1));
    }

    static Stream<Arguments> contents() {
        return Stream.of(
                Arguments.of("membership and its unattributed copy",
                        content(List.of(grant(P1, COPIED.size())), COPIED.size(), twice(COPIED)), true),
                Arguments.of("unattributed credentials of the same number but other types",
                        content(List.of(grant(P1, COPIED.size())), COPIED.size(),
                                List.of(MEMBERSHIP, OPERATOR, MEMBERSHIP, new CredentialsVO().credentialsType("Other"))), false),
                Arguments.of("more unattributed credentials than the membership holds",
                        content(List.of(grant(P1, 1)), COPIED.size(), List.of(MEMBERSHIP, OPERATOR, OPERATOR)), false),
                Arguments.of("another publisher's membership",
                        content(List.of(grant(P2, COPIED.size())), COPIED.size(), twice(COPIED)), false),
                Arguments.of("another grant next to the membership",
                        content(List.of(grant(P1, COPIED.size()), new HeldEntry.Grant("order-1", null, 0)), COPIED.size(),
                                twice(COPIED)), false),
                Arguments.of("membership only", content(List.of(grant(P1, COPIED.size())), 0, COPIED), false));
    }

    private static IssuerContent content(List<HeldEntry.Grant> grants, int unattributed, List<CredentialsVO> credentials) {
        return new IssuerContent(new HeldEntry(X, grants, unattributed), credentials);
    }

    private static HeldEntry.Grant grant(String publisher, int count) {
        return new HeldEntry.Grant(publisher, ScopeTypeVO.DATASPACE_MEMBERSHIP, count);
    }

    private static List<CredentialsVO> twice(List<CredentialsVO> credentials) {
        return Stream.concat(credentials.stream(), credentials.stream()).toList();
    }
}
