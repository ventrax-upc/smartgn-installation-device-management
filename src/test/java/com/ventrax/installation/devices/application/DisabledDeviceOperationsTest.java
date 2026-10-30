package com.ventrax.installation.devices.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ventrax.installation.integration.CredentialVault;
import com.ventrax.installation.integration.DependencyFailure;
import com.ventrax.installation.integration.TelemetryGateway;
import com.ventrax.installation.shared.application.port.out.InstallationStore;
import com.ventrax.installation.shared.application.port.out.TransactionRunner;
import com.ventrax.installation.shared.domain.Actor;
import com.ventrax.installation.shared.domain.DomainException;
import com.ventrax.installation.shared.domain.Role;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

class DisabledDeviceOperationsTest {
    private final InstallationStore store = mock(InstallationStore.class);
    private final TransactionRunner transactions = mock(TransactionRunner.class);
    private final TelemetryGateway telemetry = mock(TelemetryGateway.class);
    private final DeviceOperations operations =
            new DeviceOperations(
                    store,
                    transactions,
                    Clock.systemUTC(),
                    new CredentialVault(Base64.getEncoder().encodeToString(new byte[32])),
                    telemetry,
                    JsonMapper.builder().build(),
                    false);
    private final Actor admin = new Actor(UUID.randomUUID(), Role.SUPERADMIN);

    @Test
    void disabledProvisioningCannotGenerateJobsOrModifyDeviceState() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> operations.register(admin, id, "meter", "Kitchen", Instant.now()))
                .isInstanceOf(DependencyFailure.class);
        assertThatThrownBy(() -> operations.rotate(admin, id))
                .isInstanceOf(DependencyFailure.class);
        assertThatThrownBy(() -> operations.revoke(admin, id))
                .isInstanceOf(DependencyFailure.class);
        assertThatThrownBy(() -> operations.reassociate(admin, id, id, true, "Reviewed gap"))
                .isInstanceOf(DependencyFailure.class);
        verifyNoInteractions(store, transactions, telemetry);
    }

    @Test
    void permissionChecksStillPrecedeIntegrationAvailability() {
        var owner = new Actor(UUID.randomUUID(), Role.USER);
        assertThatThrownBy(() -> operations.revoke(owner, UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
        verifyNoInteractions(store, transactions, telemetry);
    }
}
