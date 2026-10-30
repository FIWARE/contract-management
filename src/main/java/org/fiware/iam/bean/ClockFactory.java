package org.fiware.iam.bean;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.time.Clock;

/**
 * Provides the system UTC clock, so time-dependent beans can be tested with a fixed one.
 */
@Factory
public class ClockFactory {

    @Singleton
    public Clock clock() {
        return Clock.systemUTC();
    }
}
