package org.fiware.iam.til;

import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.TypeConverter;
import jakarta.inject.Singleton;
import org.fiware.iam.til.model.ScopeTypeVO;

import java.util.Optional;

/**
 * Binds {@link ScopeTypeVO} query parameters by their API value: the generated enum's {@code toString()} is the Java name,
 * which the trusted-issuers-list rejects as an unknown scope type.
 */
@Singleton
public class ScopeTypeConverter implements TypeConverter<ScopeTypeVO, String> {

    @Override
    public Optional<String> convert(ScopeTypeVO value, Class<String> targetType, ConversionContext context) {
        return Optional.of(value.getValue());
    }
}
