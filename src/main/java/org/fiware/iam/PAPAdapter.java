package org.fiware.iam;

import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.odrl.pap.api.DefaultApiClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Responsible for handling connections with the PAP.
 */
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

	private final DefaultApiClient papClient;

	/**
	 * Creates the given policy for the given customer (added as assignee) in the ODRL-PAP. Since this becomes a concrete instantiation of the policy,
	 * its ID will be updated to include the product-order it originates from
	 */
	public Mono<Boolean> createPolicy(String customer, String orderId, Map<String, Object> policy) {
		policy = updatePolicyId(orderId, policy);
		return papClient.createPolicy(addAssignee(customer, policy)).map(HttpResponse::code).map(code -> code >= 200 && code < 300);
	}

	public Mono<Boolean> deletePolicy(String orderId, Map<String, Object> policy) {
		String fullId = buildFullId(orderId, policy);
		return papClient.deletePolicyByUid(fullId).map(HttpResponse::code).map(code -> code >= 200 && code < 300);
	}

	private String buildFullId(String orderId, Map<String, Object> policy) {
		return String.format(ID_TEMPLATE, getPolicyId(policy), orderId);
	}

	private Map<String, Object> updatePolicyId(String orderId, Map<String, Object> policy) {
		policy.put(UID_KEY, buildFullId(orderId, policy));
		return policy;
	}

	private String getPolicyId(Map<String, Object> policy) {
		if (policy.containsKey(UID_KEY) && policy.get(UID_KEY) instanceof String idString) {
			return idString;
		} else {
			throw new IllegalArgumentException("The provided policy does not contain an odrl:uid.");
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
		return policy;
	}

	private Map<String, Object> getPermission(Map<String, Object> policy) {
		Object permissionObject = policy.get(PERMISSION_KEY);
		if (permissionObject instanceof Map permissionMap) {
			return permissionMap;
		}
		throw new IllegalArgumentException("The policy needs to contain a permission.");
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
		throw new IllegalArgumentException("The policy does not contain a valid assignee.");
	}

	private Map<String, Object> getIdConstraint(String id) {
		return Map.of(LEFT_OPERAND_KEY, VC_CURRENT_PARTY_OPERAND, OPERATOR_KEY, EQ_OPERATOR, RIGHT_OPERAND_KEY, id);
	}

	private Map<String, Object> getAndConstraint(Map<String, Object> customerConstraint, Map<String, Object> originalConstraint) {
		return Map.of(TYPE_KEY, LOGICAL_CONSTRAINT_TYPE, AND_KEY, List.of(customerConstraint, originalConstraint));
	}
}
