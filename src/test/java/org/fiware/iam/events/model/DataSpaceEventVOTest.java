package org.fiware.iam.events.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataSpaceEventVOTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String EVENT_TEMPLATE =
            "{\"specversion\":\"1.0\",\"id\":\"1\",\"source\":\"did:web:p.org\",\"type\":\"t\",\"data\":%s}";

    @ParameterizedTest(name = "data {0}")
    @MethodSource("payloads")
    void acceptsAnyJsonValueAsData(String json, Object expected) throws Exception {
        DataSpaceEventVO event = OBJECT_MAPPER.readValue(EVENT_TEMPLATE.formatted(json), DataSpaceEventVO.class);

        assertEquals(expected, event.getData());
    }

    static Stream<Arguments> payloads() {
        return Stream.of(
                Arguments.of("{\"a\":1}", Map.of("a", 1)),
                Arguments.of("[1,2]", List.of(1, 2)),
                Arguments.of("\"text\"", "text"),
                Arguments.of("42", 42),
                Arguments.of("true", true),
                Arguments.of("null", null));
    }
}
