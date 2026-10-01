package org.fiware.iam.pap;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.PapException;
import org.fiware.iam.http.HttpResponses;
import org.fiware.iam.odrl.pap.api.PolicyApiClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Responsible for handling connections with the PAP.
 */
@Requires(condition = GeneralProperties.PapCondition.class)
@Singleton
@RequiredArgsConstructor
@Slf4j
public class PAPAdapter {

	private static final String TYPE_KEY = "@type";
	private static final String ID_KEY = "@id";
	private static final String UID_KEY = "odrl:uid";
	private static final String LOGICAL_CONSTRAINT_TYPE = "odrl:LogicalConstraint";
	private static final String PARTY_COLLECTION_TYPE = "odrl:PartyCollection";
	private static final String AND_KEY = "odrl:and";
	private static final String REFINEMENT_KEY = "odrl:refinement";
	private static final String ASSIGNEE_KEY = "odrl:assignee";
	private static final String PERMISSION_KEY = "odrl:permission";
	private static final String LEFT_OPERAND_KEY = "odrl:leftOperand";
	private static final String RIGHT_OPERAND_KEY = "odrl:rightOperand";
	private static final String OPERATOR_KEY = "odrl:operator";
	private static final String EQ_OPERATOR = "odrl:eq";
	private static final String VC_CURRENT_PARTY_OPERAND = "vc:currentParty";

	private static final String ID_TEMPLATE = "%s-%s";

	private final PolicyApiClient papClient;

	/**
	 * Creates the given policy for the given customer (added as assignee) in the ODRL-PAP. Since this becomes a concrete instantiation of the policy,
	 * its ID will be updated to include the product-order it originates from
	 */
	public Mono<Boolean> createPolicy(String customer, String orderId, Map<String, Object> policy) {
		// deferred, so that an invalid policy (thrown while preparing it) is signalled through the returned Mono like any other error
		return Mono.defer(() -> {
			Map<String, Object> finalPolicy = addAssignee(customer, updatePolicyId(orderId, policy));
			String uid = (String) finalPolicy.get(UID_KEY);
			return papClient.createPolicy(finalPolicy)
					.onErrorMap(HttpClientResponseException.class, e -> new PapException(FailureReason.PAP_REJECTED_POLICY,
							"The PAP rejected policy %s for assignee %s.".formatted(uid, customer), e))
					.map(response -> {
						log.debug("The PAP answered the creation of policy {} for assignee {} with {}.", uid, customer, response.code());
						return HttpResponses.isSuccess(response);
					});
		});
	}

	public Mono<Boolean> deletePolicy(String orderId, Map<String, Object> policy) {
		// deferred, so that an invalid policy (thrown while building its id) is signalled through the returned Mono like any other error
		return Mono.defer(() -> {
			String fullId = buildFullId(orderId, policy);
			return papClient.deletePolicyByUid(fullId)
					.onErrorMap(HttpClientResponseException.class, e -> new PapException(FailureReason.PAP_REJECTED_POLICY,
							"The PAP could not delete policy %s.".formatted(fullId), e))
					.map(response -> {
						log.debug("The PAP answered the deletion of policy {} with {}.", fullId, response.code());
						return HttpResponses.isSuccess(response);
					});
		});
	}

	private String buildFullId(String orderId, Map<String, Object> policy) {
		return String.format(ID_TEMPLATE, getPolicyId(policy), orderId);
	}

	private Map<String, Object> updatePolicyId(String orderId, Map<String, Object> policy) {
		policy.put(UID_KEY, buildFullId(orderId, policy));
		log.debug("Policy uid is now {}", policy.get(UID_KEY));
		return policy;
	}

	private String getPolicyId(Map<String, Object> policy) {
		if (policy.containsKey(UID_KEY) && policy.get(UID_KEY) instanceof String idString) {
			return idString;
		} else {
			throw invalidPolicy(FailureReason.POLICY_MISSING_UID,
					"The policy has no string %s, it only contains %s.".formatted(UID_KEY, policy.keySet()));
		}
	}

	private Map<String, Object> addAssignee(String customer, Map<String, Object> policy) {
		Map<String, Object> permission = getPermission(policy);
		Optional<Map.Entry<String, Object>> optionalAssignee = permission.entrySet()
				.stream()
				.filter(e -> e.getKey().equals(ASSIGNEE_KEY))
				.findFirst();
		if (optionalAssignee.isEmpty()) {
			permission.put(ASSIGNEE_KEY, customer);
		} else {
			permission.put(ASSIGNEE_KEY, addToAssignees(customer, optionalAssignee.get()));
		}
		policy.put(PERMISSION_KEY, permission);
		log.debug("Policy to be created at the PAP: {}", policy);
		return policy;
	}

	private Map<String, Object> getPermission(Map<String, Object> policy) {
		Object permissionObject = policy.get(PERMISSION_KEY);
		if (permissionObject instanceof Map permissionMap) {
			return permissionMap;
		}
		throw invalidPolicy(FailureReason.POLICY_MISSING_PERMISSION,
				"Policy %s has no %s object, but %s.".formatted(policy.get(UID_KEY), PERMISSION_KEY, permissionObject));
	}

	private Map<String, Object> addToAssignees(String customer, Map.Entry<String, Object> assignee) {
		Object assigneeValue = assignee.getValue();
		Map<String, Object> customerConstraint = getIdConstraint(customer);
		Map<String, Object> idConstraint = Map.of();
		if (assigneeValue instanceof String assigneeId) {
			idConstraint = getIdConstraint(assigneeId);
		} else if (assigneeValue instanceof Map valueMap) {
			idConstraint = getOriginalConstraint(valueMap);
		}
		return Map.of(TYPE_KEY, PARTY_COLLECTION_TYPE, REFINEMENT_KEY, getAndConstraint(customerConstraint, idConstraint));
	}

	private Map<String, Object> getOriginalConstraint(Map originalMap) {
		if (originalMap.containsKey(ID_KEY) && originalMap.get(ID_KEY) instanceof String idString) {
			return getIdConstraint(idString);
		} else if (originalMap.containsKey(ID_KEY) && originalMap.get(ID_KEY) instanceof Map<?, ?> idMap) {
			if (idMap.containsKey(ID_KEY) && idMap.get(ID_KEY) instanceof String idString) {
				return getIdConstraint(idString);
			}
		} else if (originalMap.containsKey(REFINEMENT_KEY) && originalMap.get(REFINEMENT_KEY) instanceof Map refinementMap) {
			return refinementMap;
		}
		throw invalidPolicy(FailureReason.POLICY_INVALID_ASSIGNEE,
				"The %s of the policy is neither an id, an object with @id nor a refinement: %s.".formatted(ASSIGNEE_KEY, originalMap));
	}

	/**
	 * An invalid policy is a fault of whoever provided it (a product specification or a remote contract management),
	 * not of the PAP - it is answered with 400, like any other invalid argument.
	 */
	private static IllegalArgumentException invalidPolicy(FailureReason reason, String message) {
		return new IllegalArgumentException(reason.format(message));
	}

	private Map<String, Object> getIdConstraint(String id) {
		return Map.of(LEFT_OPERAND_KEY, VC_CURRENT_PARTY_OPERAND, OPERATOR_KEY, EQ_OPERATOR, RIGHT_OPERAND_KEY, id);
	}

	private Map<String, Object> getAndConstraint(Map<String, Object> customerConstraint, Map<String, Object> originalConstraint) {
		return Map.of(TYPE_KEY, LOGICAL_CONSTRAINT_TYPE, AND_KEY, List.of(customerConstraint, originalConstraint));
	}
}
