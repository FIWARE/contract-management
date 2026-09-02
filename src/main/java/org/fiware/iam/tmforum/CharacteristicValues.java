package org.fiware.iam.tmforum;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.tmforum.productcatalog.model.CharacteristicValueSpecificationVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationCharacteristicVO;
import org.fiware.iam.tmforum.servicecatalog.model.CharacteristicSpecificationVO;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Tolerant read-access to the characteristic configuration plane of a specification, normalized over
 * the shapes the different TMForum APIs use for the very same concept.
 * <p>
 * The container names differ per entity, which is enough to make a single reader impossible without
 * this normalization:
 * <table border="1">
 *     <caption>Characteristic shapes</caption>
 *     <tr><th>Entity</th><th>Characteristic list</th><th>Value list</th></tr>
 *     <tr><td>{@code ProductSpecification}</td><td>{@code productSpecCharacteristic}</td><td>{@code productSpecCharacteristicValue}</td></tr>
 *     <tr><td>{@code ServiceSpecification}</td><td>{@code specCharacteristic}</td><td>{@code characteristicValueSpecification}</td></tr>
 * </table>
 * <p>
 * Beyond the naming, TMForum does not constrain a characteristic: {@code valueType} is free text and
 * may be absent, the value list may be absent, and a value may hold either a single object or an
 * array of objects. Every writer in the data space uses a slightly different subset of that freedom,
 * so reading tolerates all of it - a characteristic that cannot be interpreted contributes nothing
 * rather than failing the caller.
 *
 * @see <a href="https://github.com/FIWARE/data-space-connector/blob/main/doc/tmforum/extensions.md">The
 * extension registry of the FIWARE Data Space Connector</a>
 */
@Slf4j
public final class CharacteristicValues {

	private CharacteristicValues() {
		// utility class
	}

	/**
	 * One characteristic, reduced to the two things the connector cares about.
	 *
	 * @param valueType the semantic tag the connector discriminates on, may be {@code null}
	 * @param values    the raw values of the characteristic, never {@code null}
	 */
	public record Characteristic(String valueType, List<Object> values) {
	}

	/**
	 * Normalize the characteristics of a {@code ProductSpecification}.
	 *
	 * @param characteristics the {@code productSpecCharacteristic} list, may be {@code null}
	 * @return the normalized characteristics, never {@code null}
	 */
	public static List<Characteristic> ofProductSpecification(
			List<ProductSpecificationCharacteristicVO> characteristics) {
		return Optional.ofNullable(characteristics)
				.orElseGet(List::of)
				.stream()
				.filter(Objects::nonNull)
				.map(characteristic -> new Characteristic(characteristic.getValueType(),
						rawValues(characteristic.getProductSpecCharacteristicValue(),
								CharacteristicValueSpecificationVO::getValue)))
				.toList();
	}

	/**
	 * Normalize the characteristics of a {@code ServiceSpecification}.
	 *
	 * @param characteristics the {@code specCharacteristic} list, may be {@code null}
	 * @return the normalized characteristics, never {@code null}
	 */
	public static List<Characteristic> ofServiceSpecification(List<CharacteristicSpecificationVO> characteristics) {
		return Optional.ofNullable(characteristics)
				.orElseGet(List::of)
				.stream()
				.filter(Objects::nonNull)
				.map(characteristic -> new Characteristic(characteristic.getValueType(),
						rawValues(characteristic.getCharacteristicValueSpecification(),
								org.fiware.iam.tmforum.servicecatalog.model.CharacteristicValueSpecificationVO::getValue)))
				.toList();
	}

	/**
	 * Find the first characteristic declaring the given {@code valueType}.
	 * <p>
	 * The comparison is null-safe in both directions: a {@code null} list yields an empty result and a
	 * characteristic without a {@code valueType} is skipped instead of raising a
	 * {@link NullPointerException}.
	 *
	 * @param characteristics the normalized characteristics, may be {@code null}
	 * @param valueType       the {@code valueType} to look for
	 * @return the first matching characteristic, or {@link Optional#empty()} if none matches
	 */
	public static Optional<Characteristic> byValueType(List<Characteristic> characteristics, String valueType) {
		return Optional.ofNullable(characteristics)
				.orElseGet(List::of)
				.stream()
				.filter(Objects::nonNull)
				.filter(characteristic -> valueType.equals(characteristic.valueType()))
				.findFirst();
	}

	/**
	 * Read every value of the given characteristic as a flat list of {@code elementType}.
	 * <p>
	 * A value holding an array contributes all of its elements, a value holding a single object
	 * contributes that object - both shapes occur in deployed specifications. Values that cannot be
	 * converted are logged and skipped, so one malformed entry does not hide the well-formed ones.
	 *
	 * @param objectMapper   mapper to convert the untyped characteristic values with
	 * @param characteristic the characteristic to read, may be {@code null}
	 * @param elementType    the type a single value is expected to have
	 * @param <T>            the type a single value is expected to have
	 * @return every readable value, never {@code null}
	 */
	public static <T> List<T> flatten(ObjectMapper objectMapper, Characteristic characteristic,
			TypeReference<T> elementType) {
		if (characteristic == null) {
			return List.of();
		}
		return characteristic.values()
				.stream()
				.flatMap(value -> toElements(objectMapper, value, elementType).stream())
				.toList();
	}

	private static <V> List<Object> rawValues(List<V> values, java.util.function.Function<V, Object> valueAccessor) {
		return Optional.ofNullable(values)
				.orElseGet(List::of)
				.stream()
				.filter(Objects::nonNull)
				.map(valueAccessor)
				.filter(Objects::nonNull)
				.toList();
	}

	private static <T> List<T> toElements(ObjectMapper objectMapper, Object value, TypeReference<T> elementType) {
		if (value instanceof Collection<?> collection) {
			return collection.stream()
					.filter(Objects::nonNull)
					.map(element -> convert(objectMapper, element, elementType))
					.filter(Objects::nonNull)
					.toList();
		}
		return Optional.ofNullable(convert(objectMapper, value, elementType))
				.map(List::of)
				.orElseGet(List::of);
	}

	private static <T> T convert(ObjectMapper objectMapper, Object value, TypeReference<T> elementType) {
		try {
			return objectMapper.convertValue(value, elementType);
		} catch (IllegalArgumentException iae) {
			log.warn("The characteristic value {} is invalid and will be skipped.", value, iae);
			return null;
		}
	}
}
