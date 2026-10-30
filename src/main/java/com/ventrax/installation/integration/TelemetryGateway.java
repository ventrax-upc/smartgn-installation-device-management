package com.ventrax.installation.integration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Replaceable device-operation port. It does not own consumption, alerts or reports. */
public interface TelemetryGateway {
    record Association(
            UUID deviceId,
            long version,
            UUID accountId,
            UUID propertyId,
            UUID pointId,
            Instant validFrom,
            Instant validTo) {}

    record DrainResult(boolean drained, int pendingReadings, String evidenceId) {}

    record OperationalStatus(UUID deviceId, String connectionState, Instant lastContact) {}

    void synchronize(Association association);

    DrainResult drain(UUID deviceId, long associationVersion);

    default List<OperationalStatus> operationalStatus(UUID accountId, List<UUID> deviceIds) {
        return List.of();
    }
}
