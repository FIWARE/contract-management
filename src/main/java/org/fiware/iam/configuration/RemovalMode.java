package org.fiware.iam.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;
import java.util.Locale;

/**
 * What a participant does with an identifier a publisher announces as removed. Only REPORT writes nothing to the TIL.
 */
public enum RemovalMode {
    REPORT,
    DATASPACE,
    GRANTED,
    FULL;

    /**
     * Value of the metric's mode tag for an identifier on keep or the own id, for which no mode applies.
     */
    public static final String KEEP_TAG_VALUE = "keep";

    /**
     * Case-insensitive parsing, so the documented lower-case values bind; publisher entries are converted by Jackson.
     */
    @JsonCreator
    public static RemovalMode fromValue(String value) {
        return Arrays.stream(values())
                .filter(mode -> mode.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown removal mode: " + value));
    }

    /**
     * Value of the metric's mode tag, the lower-case name as configured.
     */
    public String tagValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * True if the mode writes to the trusted issuers list.
     */
    public boolean writes() {
        return this != REPORT;
    }
}
