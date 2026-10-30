package com.ventrax.installation.integration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class UnavailableIntegrationsTest {
    @Test
    void disabledTelemetryNeverConfirmsMetadataOrBufferDrain() {
        var gateway = UnavailableIntegrations.telemetry();
        UUID device = UUID.randomUUID();
        assertThrows(
                DependencyFailure.class,
                () ->
                        gateway.synchronize(
                                new TelemetryGateway.Association(
                                        device,
                                        1,
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        Instant.now(),
                                        null)));
        assertThrows(DependencyFailure.class, () -> gateway.drain(device, 1));
        assertThrows(
                DependencyFailure.class,
                () -> gateway.operationalStatus(UUID.randomUUID(), List.of(device)));
    }

    @Test
    void disabledBrokerCannotConfirmActivationOrRevocation() {
        var gateway = UnavailableIntegrations.broker();
        UUID device = UUID.randomUUID();
        assertThrows(DependencyFailure.class, () -> gateway.stage(device, "fixture"));
        assertThrows(DependencyFailure.class, () -> gateway.activate(device));
        assertThrows(DependencyFailure.class, () -> gateway.revoke(device));
    }
}
