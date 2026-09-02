package org.fiware.iam.tmforum;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.iam.tmforum.productcatalog.model.BundledProductSpecificationVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationVO;
import org.fiware.iam.tmforum.productcatalog.model.ServiceSpecificationRefVO;
import org.fiware.iam.tmforum.servicecatalog.api.ServiceSpecificationApiClient;
import org.fiware.iam.tmforum.servicecatalog.model.ServiceSpecificationVO;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Resolves a {@code ProductSpecification} into the specifications that carry its configuration.
 * <p>
 * A product can be composed: it may reference {@code ServiceSpecification}s that hold their own
 * policy configuration, and it may bundle other {@code ProductSpecification}s. This resolver walks
 * that graph once per order item and returns a flat list of nodes, so the callers can read the
 * configuration of a composed product exactly as they read the configuration of a flat one.
 * <p>
 * Three properties of the walk are deliberate:
 * <ul>
 *     <li><b>It is off by default.</b> Without
 *     {@code general.enableSpecificationComposition} the graph is the ordered specification and
 *     nothing else, so the behaviour is bit-for-bit the one before composition existed.</li>
 *     <li><b>It terminates.</b> {@code bundledProductSpecification} is not cycle-checked by the
 *     TMForum API, so every visited specification is remembered and the depth is limited by
 *     {@code general.specificationCompositionMaxDepth}. Hitting either guard <i>truncates</i> the
 *     walk and logs a warning naming the specification, the references that were skipped and the
 *     limit - a truncated grant is recoverable, a failed order is not, but a silent truncation would
 *     be missing access control nobody can see.</li>
 *     <li><b>A broken reference fails.</b> A referenced specification that cannot be read is a
 *     broken catalog rather than an empty configuration, and is raised as a
 *     {@link TMForumException}.</li>
 * </ul>
 * {@code ResourceSpecification}s are deliberately not traversed - they carry no connector
 * configuration.
 *
 * @see <a href="https://github.com/FIWARE/data-space-connector/blob/main/doc/tmforum/service-policy.md">Composed
 * ProductSpecifications in the FIWARE Data Space Connector</a>
 */
@Requires(condition = GeneralProperties.TmForumCondition.class)
@Singleton
@Slf4j
@RequiredArgsConstructor
public class SpecificationGraphResolver {

	private static final String SPECIFICATION_NOT_RESOLVABLE = "The %s specification %s referenced by specification %s could not be resolved.";
	private static final String KIND_SERVICE = "service";
	private static final String KIND_PRODUCT = "product";
	private static final String TRUNCATION_WARNING =
			"The composition of specification {} is deeper than the configured limit of {} levels. "
					+ "The following references are NOT resolved and their configuration is NOT applied: "
					+ "services {}, bundled products {}.";

	private final GeneralProperties generalProperties;
	private final ProductSpecificationApiClient productSpecificationApiClient;
	private final ServiceSpecificationApiClient serviceSpecificationApiClient;

	/**
	 * The kind of specification a node was resolved from.
	 */
	public enum SpecificationKind {
		PRODUCT, SERVICE
	}

	/**
	 * A party referenced by a specification, normalized over the per-API {@code RelatedParty} types.
	 *
	 * @param id   the TMForum id of the party
	 * @param role the role the party has on the specification, may be {@code null}
	 */
	public record PartyReference(String id, String role) {
	}

	/**
	 * One specification of the resolved graph.
	 *
	 * @param id              the TMForum id of the specification
	 * @param kind            whether it is the product or one of its services
	 * @param characteristics its normalized characteristics
	 * @param relatedParties  the parties it references
	 */
	public record SpecificationNode(String id, SpecificationKind kind,
			List<CharacteristicValues.Characteristic> characteristics, List<PartyReference> relatedParties) {
	}

	/**
	 * The resolved composition of one ordered product.
	 *
	 * @param nodes     the ordered specification first, then the specifications it is composed of
	 * @param truncated whether the walk stopped at the depth limit and configuration was left out
	 */
	public record SpecificationGraph(List<SpecificationNode> nodes, boolean truncated) {

		/**
		 * The characteristics of every node, in traversal order.
		 *
		 * @return the aggregated characteristics
		 */
		public List<CharacteristicValues.Characteristic> characteristics() {
			return nodes.stream().map(SpecificationNode::characteristics).flatMap(List::stream).toList();
		}
	}

	/**
	 * Resolve the given specification and everything it is composed of.
	 *
	 * @param productSpecification the ordered specification, already read
	 * @return the resolved graph, holding at least the given specification
	 * @throws TMForumException if a referenced specification cannot be resolved
	 */
	public Mono<SpecificationGraph> resolve(ProductSpecificationVO productSpecification) {
		SpecificationNode rootNode = toNode(productSpecification);
		if (!generalProperties.isEnableSpecificationComposition()) {
			log.debug("Specification composition is disabled, only {} is resolved.", productSpecification.getId());
			return Mono.just(new SpecificationGraph(List.of(rootNode), false));
		}
		Set<String> visited = ConcurrentHashMap.newKeySet();
		Optional.ofNullable(productSpecification.getId()).ifPresent(visited::add);
		AtomicBoolean truncated = new AtomicBoolean(false);

		return expand(productSpecification, visited, generalProperties.getSpecificationCompositionMaxDepth(), truncated)
				.map(composedNodes -> {
					List<SpecificationNode> nodes = new ArrayList<>();
					nodes.add(rootNode);
					nodes.addAll(composedNodes);
					return new SpecificationGraph(List.copyOf(nodes), truncated.get());
				});
	}

	private Mono<List<SpecificationNode>> expand(ProductSpecificationVO productSpecification, Set<String> visited,
			int remainingDepth, AtomicBoolean truncated) {
		List<String> serviceIds = unvisitedReferences(productSpecification.getServiceSpecification(),
				ServiceSpecificationRefVO::getId, visited);
		List<String> bundledIds = unvisitedReferences(productSpecification.getBundledProductSpecification(),
				BundledProductSpecificationVO::getId, visited);

		if (serviceIds.isEmpty() && bundledIds.isEmpty()) {
			return Mono.just(List.of());
		}
		if (remainingDepth <= 0) {
			truncated.set(true);
			log.warn(TRUNCATION_WARNING, productSpecification.getId(),
					generalProperties.getSpecificationCompositionMaxDepth(), serviceIds, bundledIds);
			return Mono.just(List.of());
		}

		Mono<List<SpecificationNode>> services = Flux.fromIterable(serviceIds)
				.flatMap(serviceId -> resolveServiceSpecification(serviceId, productSpecification.getId()))
				.collectList();
		Mono<List<SpecificationNode>> bundled = Flux.fromIterable(bundledIds)
				.flatMap(bundledId -> resolveProductSpecification(bundledId, productSpecification.getId())
						.flatMap(bundledSpecification -> expand(bundledSpecification, visited, remainingDepth - 1,
								truncated)
								.map(children -> withParentFirst(toNode(bundledSpecification), children))))
				.collectList()
				.map(nodesPerBundle -> nodesPerBundle.stream().flatMap(List::stream).toList());

		return Mono.zip(services, bundled, (serviceNodes, bundledNodes) -> {
			List<SpecificationNode> nodes = new ArrayList<>(serviceNodes);
			nodes.addAll(bundledNodes);
			return List.copyOf(nodes);
		});
	}

	private Mono<SpecificationNode> resolveServiceSpecification(String serviceId, String referencedBy) {
		return serviceSpecificationApiClient.retrieveServiceSpecification(serviceId, null)
				.flatMap(response -> {
					ServiceSpecificationVO serviceSpecification = response.body();
					if (serviceSpecification == null) {
						return Mono.error(unresolvableReference(KIND_SERVICE, serviceId, referencedBy));
					}
					return Mono.just(toNode(serviceSpecification));
				})
				.switchIfEmpty(Mono.error(() -> unresolvableReference(KIND_SERVICE, serviceId, referencedBy)));
	}

	private Mono<ProductSpecificationVO> resolveProductSpecification(String specificationId, String referencedBy) {
		return productSpecificationApiClient.retrieveProductSpecification(specificationId, null)
				.flatMap(response -> {
					ProductSpecificationVO productSpecification = response.body();
					if (productSpecification == null) {
						return Mono.error(unresolvableReference(KIND_PRODUCT, specificationId, referencedBy));
					}
					return Mono.just(productSpecification);
				})
				.switchIfEmpty(Mono.error(() -> unresolvableReference(KIND_PRODUCT, specificationId, referencedBy)));
	}

	private SpecificationNode toNode(ProductSpecificationVO productSpecification) {
		return new SpecificationNode(productSpecification.getId(), SpecificationKind.PRODUCT,
				CharacteristicValues.ofProductSpecification(productSpecification.getProductSpecCharacteristic()),
				parties(productSpecification.getRelatedParty(),
						party -> new PartyReference(party.getId(), party.getRole())));
	}

	private SpecificationNode toNode(ServiceSpecificationVO serviceSpecification) {
		return new SpecificationNode(serviceSpecification.getId(), SpecificationKind.SERVICE,
				CharacteristicValues.ofServiceSpecification(serviceSpecification.getSpecCharacteristic()),
				parties(serviceSpecification.getRelatedParty(),
						party -> new PartyReference(party.getId(), party.getRole())));
	}

	private static <P> List<PartyReference> parties(List<P> relatedParties, Function<P, PartyReference> mapper) {
		return Optional.ofNullable(relatedParties)
				.orElseGet(List::of)
				.stream()
				.filter(Objects::nonNull)
				.map(mapper)
				.filter(party -> party.id() != null)
				.toList();
	}

	/**
	 * Collect the ids of references that have not been seen yet, marking them as seen.
	 * <p>
	 * Marking happens here rather than at resolution time, so a specification referenced twice within
	 * the same graph - and a cycle of bundled specifications - is resolved exactly once.
	 */
	private static <R> List<String> unvisitedReferences(List<R> references, Function<R, String> idAccessor,
			Set<String> visited) {
		return Optional.ofNullable(references)
				.orElseGet(List::of)
				.stream()
				.filter(Objects::nonNull)
				.map(idAccessor)
				.filter(Objects::nonNull)
				.filter(visited::add)
				.toList();
	}

	/**
	 * Log and build the exception for a specification that is referenced but cannot be read.
	 * <p>
	 * Must be invoked lazily from {@code switchIfEmpty} (via
	 * {@link Mono#error(java.util.function.Supplier)}), since the arguments of {@code switchIfEmpty}
	 * are evaluated when the pipeline is assembled, not when it fails.
	 */
	private static TMForumException unresolvableReference(String kind, String specificationId, String referencedBy) {
		String message = SPECIFICATION_NOT_RESOLVABLE.formatted(kind, specificationId, referencedBy);
		log.error(message);
		return new TMForumException(message);
	}

	private static List<SpecificationNode> withParentFirst(SpecificationNode parent, List<SpecificationNode> children) {
		List<SpecificationNode> nodes = new ArrayList<>();
		nodes.add(parent);
		nodes.addAll(children);
		return List.copyOf(nodes);
	}
}
