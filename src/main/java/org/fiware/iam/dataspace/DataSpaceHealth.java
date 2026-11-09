package org.fiware.iam.dataspace;

import io.micronaut.health.HealthStatus;

/**
 * Health status shared by the data space eventing indicators, so the aggregate never sees two different DEGRADED values.
 */
public final class DataSpaceHealth {

    /**
     * Without operational flag and severity it ranks between UP and DOWN, and the health endpoints keep answering 200.
     */
    public static final HealthStatus DEGRADED =
            new HealthStatus("DEGRADED", "A data space eventing component is degraded; see its details.", null, null);

    private DataSpaceHealth() {
    }
}
