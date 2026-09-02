package org.fiware.iam.tmforum;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.tmforum.productcatalog.model.CharacteristicValueSpecificationVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationCharacteristicVO;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Tolerant read-access to the {@code productSpecCharacteristic} configuration plane of a
 * {@code ProductSpecification}.
 * <p>
 * TMForum does not constrain the shape of a characteristic beyond its container: {@code valueType} is
 * free text and may be absent, the value list may be absent, and a characteristic value may hold
 * either a single object or an array of objects. Every writer in the data space uses a slightly
 * different subset of that freedom, so reading has to tolerate all of it - a specification that
 * cannot be interpreted must contribute nothing rather than fail the caller.
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
	 * Find the first characteristic declaring the given {@code valueType}.
	 * <p>
	 * The comparison is null-safe in both directions: a {@code null} list yields an empty result and a
	 * characteristic without a {@code valueType} is skipped instead of raising a
	 * {@link NullPointerException}.
	 *
	 * @param characteristics the specification's characteristics, may be {@code null}
	 * @param valueType       the {@code valueType} to look for
	 * @return the first matching characteristic, or {@link Optional#empty()} if none matches
	 */
	public static Optional<ProductSpecificationCharacteristicVO> byValueType(
			List<ProductSpecificationCharacteristicVO> characteristics, String valueType) {
		return Optional.ofNullable(characteristics)
				.orElseGet(List::of)
				.stream()
				.filter(Objects::nonNull)
				.filter(characteristic -> valueType.equals(characteristic.getValueType()))
				.findFirst();
	}

	/**
	 * Read every value of the given characteristic as a flat list of {@code elementType}.
	 * <p>
	 * A characteristic value holding an array contributes all of its elements, a characteristic value
	 * holding a single object contributes that object - both shapes occur in deployed specifications.
	 * Values that cannot be converted are logged and skipped, so one malformed entry does not hide the
	 * well-formed ones.
	 *
	 * @param objectMapper   mapper to convert the untyped characteristic values with
	 * @param characteristic the characteristic to read, may be {@code null}
	 * @param elementType    the type a single value is expected to have
	 * @param <T>            the type a single value is expected to have
	 * @return every readable value, never {@code null}
	 */
	public static <T> List<T> flatten(ObjectMapper objectMapper,
			ProductSpecificationCharacteristicVO characteristic, TypeReference<T> elementType) {
		if (characteristic == null) {
			return List.of();
		}
		return Optional.ofNullable(characteristic.getProductSpecCharacteristicValue())
				.orElseGet(List::of)
				.stream()
				.filter(Objects::nonNull)
				.map(CharacteristicValueSpecificationVO::getValue)
				.filter(Objects::nonNull)
				.flatMap(value -> toElements(objectMapper, value, elementType).stream())
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
