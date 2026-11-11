package org.fiware.iam.dataspace;

import org.fiware.iam.configuration.RemovalMode;
import org.fiware.iam.til.HeldEntry;
import org.fiware.iam.til.model.ScopeTypeVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemovalPlanTest {

    private static final String P1 = "did:web:p1.org";
    private static final String P2 = "did:web:p2.org";
    private static final String X = "did:web:x.org";
    private static final String ORDER = "order-1";
    private static final int CREDENTIALS = 2;
    private static final int NONE = 0;
    // as many unattributed credentials as membership() holds: an interrupted adoption's copy
    private static final int COPY_OF_MEMBERSHIP = 1;

    private static final RemovalPlan.Operation REVOKE_P1 = new RemovalPlan.RevokeGrant(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP);
    private static final RemovalPlan.Operation REVOKE_ORDER = new RemovalPlan.RevokeGrant(ORDER, null);

    @ParameterizedTest(name = "{0}, {1}, adoption {2}")
    @MethodSource("modeTimesOrigin")
    void plansEachModeAndOrigin(String origin, RemovalMode mode, boolean adoptionAllowed, Optional<HeldEntry> held,
                                RemovalPlan expected) {
        assertEquals(expected, RemovalPlan.of(Optional.of(mode), held, P1, RemovalTrigger.EVENT, adoptionAllowed, false));
    }

    static Stream<Arguments> modeTimesOrigin() {
        return Stream.of(
                // origin, held, dataspace plan, granted plan; report and full follow from whether anything is held
                origin("P's membership", held(NONE, membership(P1)),
                        plan(RemovalAction.REVOKED_MEMBERSHIP, deleteIfEmpty(REVOKE_P1)),
                        plan(RemovalAction.REVOKED_GRANTED, deleteIfEmpty(REVOKE_P1))),
                origin("other publisher's membership", held(NONE, membership(P2)), RemovalPlan.REPORTED, RemovalPlan.REPORTED),
                origin("order grant only", held(NONE, order()), RemovalPlan.REPORTED, RemovalPlan.REPORTED),
                origin("mixed: P's membership and unattributed", held(CREDENTIALS, membership(P1)),
                        plan(RemovalAction.REVOKED_MEMBERSHIP, REVOKE_P1),
                        plan(RemovalAction.REVOKED_GRANTED, REVOKE_P1)),
                origin("mixed: order grant and unattributed", held(CREDENTIALS, order()), RemovalPlan.REPORTED, RemovalPlan.REPORTED),
                origin("P's membership and order grant", held(NONE, membership(P1), order()),
                        plan(RemovalAction.REVOKED_MEMBERSHIP, REVOKE_P1),
                        plan(RemovalAction.REVOKED_GRANTED, REVOKE_ORDER, deleteIfEmpty(REVOKE_P1))),
                origin("P's and other membership", held(NONE, membership(P1), membership(P2)),
                        plan(RemovalAction.REVOKED_MEMBERSHIP, REVOKE_P1),
                        plan(RemovalAction.REVOKED_GRANTED, REVOKE_P1)),
                origin("P's and other membership and order grant", held(NONE, membership(P1), membership(P2), order()),
                        plan(RemovalAction.REVOKED_MEMBERSHIP, REVOKE_P1),
                        plan(RemovalAction.REVOKED_GRANTED, REVOKE_P1)),
                origin("other membership and order grant", held(NONE, membership(P2), order()),
                        RemovalPlan.REPORTED, RemovalPlan.REPORTED),
                origin("already empty", held(NONE),
                        plan(RemovalAction.REVOKED_MEMBERSHIP, deleteIfEmpty(null)),
                        plan(RemovalAction.REVOKED_GRANTED, deleteIfEmpty(null))),
                origin("not held", Optional.empty(), RemovalPlan.NOT_HELD, RemovalPlan.NOT_HELD)
        ).flatMap(Origin::cases);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RemovalMode.class, names = {"DATASPACE", "GRANTED"})
    void unattributedOnlyEntryIsRevokedByEventWhenAdoptionAllowed(RemovalMode mode) {
        Optional<HeldEntry> unattributedOnly = held(CREDENTIALS);
        RemovalAction revoked = mode == RemovalMode.GRANTED ? RemovalAction.REVOKED_GRANTED : RemovalAction.REVOKED_MEMBERSHIP;

        assertEquals(plan(revoked, deleteIfEmpty(new RemovalPlan.RevokeUnattributed())),
                RemovalPlan.of(Optional.of(mode), unattributedOnly, P1, RemovalTrigger.EVENT, true, false));
        assertEquals(RemovalPlan.REPORTED, RemovalPlan.of(Optional.of(mode), unattributedOnly, P1, RemovalTrigger.EVENT, false, false));
        // the sweep cannot tell such an entry from an operator-managed one
        assertEquals(RemovalPlan.REPORTED,
                RemovalPlan.of(Optional.of(mode), unattributedOnly, P1, RemovalTrigger.RECONCILIATION, true, false));
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("writingModesAndTriggers")
    void adoptionCopyIsRevokedBeforeTheMembershipWhenAdoptionAllowed(RemovalMode mode, RemovalTrigger trigger) {
        Optional<HeldEntry> copy = held(COPY_OF_MEMBERSHIP, membership(P1));
        RemovalAction revoked = mode == RemovalMode.GRANTED ? RemovalAction.REVOKED_GRANTED : RemovalAction.REVOKED_MEMBERSHIP;

        assertEquals(plan(revoked, new RemovalPlan.RevokeUnattributed(), deleteIfEmpty(REVOKE_P1)),
                RemovalPlan.of(Optional.of(mode), copy, P1, trigger, true, true));
        // without adoption, the copy is left like the unattributed credentials of any mixed entry
        assertEquals(plan(revoked, REVOKE_P1), RemovalPlan.of(Optional.of(mode), copy, P1, trigger, false, true));
    }

    static Stream<Arguments> writingModesAndTriggers() {
        return Stream.of(RemovalMode.DATASPACE, RemovalMode.GRANTED).flatMap(mode -> Arrays.stream(RemovalTrigger.values())
                .map(trigger -> Arguments.of(mode, trigger)));
    }

    // that keep beats every configured mode is decided by removalMode, see ParticipantRemovalTest
    @Test
    void keepTouchesNothing() {
        assertEquals(RemovalPlan.KEPT,
                RemovalPlan.of(Optional.empty(), held(CREDENTIALS, membership(P1), order()), P1, RemovalTrigger.EVENT, true, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("removedByP1InGranted")
    void attributesOrderGrantsThroughTheOnlyMembership(String description, Optional<HeldEntry> held, RemovalPlan expected) {
        assertEquals(expected, RemovalPlan.of(Optional.of(RemovalMode.GRANTED), held, P1, RemovalTrigger.EVENT, false, false));
    }

    static Stream<Arguments> removedByP1InGranted() {
        return Stream.of(
                Arguments.of("P1 membership + order grants", held(NONE, membership(P1), order()),
                        plan(RemovalAction.REVOKED_GRANTED, REVOKE_ORDER, deleteIfEmpty(REVOKE_P1))),
                Arguments.of("P2 membership + order grants", held(NONE, membership(P2), order()), RemovalPlan.REPORTED),
                Arguments.of("P1 + P2 membership + order grants", held(NONE, membership(P1), membership(P2), order()),
                        plan(RemovalAction.REVOKED_GRANTED, REVOKE_P1)),
                Arguments.of("order grants only", held(NONE, order()), RemovalPlan.REPORTED));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RemovalMode.class, names = {"DATASPACE", "GRANTED"})
    void revokesTheMembershipLast(RemovalMode mode) {
        RemovalPlan plan = RemovalPlan.of(Optional.of(mode), held(NONE, membership(P1), order()), P1, RemovalTrigger.EVENT, false, false);

        RemovalPlan.Operation last = plan.operations().get(plan.operations().size() - 1);
        assertTrue(last.equals(REVOKE_P1) || last.equals(deleteIfEmpty(REVOKE_P1)));
    }

    @Test
    void deleteIfEmptyFallsBackToTheReplacedWriteWhenTheEntryGainedAGrant() {
        RemovalPlan.DeleteIfEmpty delete = (RemovalPlan.DeleteIfEmpty) deleteIfEmpty(REVOKE_P1);
        HeldEntry gained = held(NONE, membership(P1), membership(P2)).orElseThrow();

        assertTrue(delete.deletes(held(NONE, membership(P1)).orElseThrow()));
        assertEquals(held(NONE, membership(P2)).orElseThrow(), delete.applyTo(gained));
    }

    private static Origin origin(String name, Optional<HeldEntry> held, RemovalPlan dataspace, RemovalPlan granted) {
        return new Origin(name, held, dataspace, granted);
    }

    private static RemovalPlan plan(RemovalAction action, RemovalPlan.Operation... operations) {
        return new RemovalPlan(action, Arrays.asList(operations));
    }

    private static RemovalPlan.Operation deleteIfEmpty(RemovalPlan.Operation replaced) {
        return new RemovalPlan.DeleteIfEmpty(replaced);
    }

    private static Optional<HeldEntry> held(int unattributed, HeldEntry.Grant... grants) {
        return Optional.of(new HeldEntry(X, List.of(grants), unattributed));
    }

    private static HeldEntry.Grant membership(String publisher) {
        return new HeldEntry.Grant(publisher, ScopeTypeVO.DATASPACE_MEMBERSHIP, 1);
    }

    private static HeldEntry.Grant order() {
        return new HeldEntry.Grant(ORDER, null, 1);
    }

    private record Origin(String name, Optional<HeldEntry> held, RemovalPlan dataspace, RemovalPlan granted) {

        Stream<Arguments> cases() {
            RemovalPlan report = held.isPresent() ? RemovalPlan.REPORTED : RemovalPlan.NOT_HELD;
            RemovalPlan full = held.isPresent()
                    ? plan(RemovalAction.DELETED_ISSUER, new RemovalPlan.DeleteIssuer())
                    : RemovalPlan.NOT_HELD;
            // none of these entries is unattributed-only, so adoption changes nothing
            return Stream.of(true, false).flatMap(adoption -> Stream.of(
                    Arguments.of(name, RemovalMode.REPORT, adoption, held, report),
                    Arguments.of(name, RemovalMode.DATASPACE, adoption, held, dataspace),
                    Arguments.of(name, RemovalMode.GRANTED, adoption, held, granted),
                    Arguments.of(name, RemovalMode.FULL, adoption, held, full)));
        }
    }
}
