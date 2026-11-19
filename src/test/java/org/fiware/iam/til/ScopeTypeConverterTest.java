package org.fiware.iam.til;

import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.fiware.iam.til.model.ScopeTypeVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest(packages = {"org.fiware.iam"}, startApplication = false)
class ScopeTypeConverterTest {

    @Inject
    private ConversionService conversionService;

    @ParameterizedTest
    @EnumSource(ScopeTypeVO.class)
    void convertsToTheApiValue(ScopeTypeVO type) {
        assertEquals(Optional.of(type.getValue()),
                new ScopeTypeConverter().convert(type, String.class, ConversionContext.DEFAULT));
    }

    @Test
    void isRegisteredWithTheApplicationConversionService() {
        assertEquals(Optional.of(ScopeTypeVO.DATASPACE_MEMBERSHIP_VALUE),
                conversionService.convert(ScopeTypeVO.DATASPACE_MEMBERSHIP, String.class));
    }
}
