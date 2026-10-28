package com.smartgn.installationdevicemanagement.devices.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.smartgn.installationdevicemanagement.integration.CredentialVault;
import com.smartgn.installationdevicemanagement.integration.DependencyFailure;
import com.smartgn.installationdevicemanagement.integration.TelemetryGateway;
import com.smartgn.installationdevicemanagement.shared.application.port.out.InstallationStore;
import com.smartgn.installationdevicemanagement.shared.application.port.out.TransactionRunner;
import com.smartgn.installationdevicemanagement.shared.domain.Actor;
import com.smartgn.installationdevicemanagement.shared.domain.DomainException;
import com.smartgn.installationdevicemanagement.shared.domain.Role;

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
