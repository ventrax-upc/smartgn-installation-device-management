package com.ventrax.installation.installations.application.port.in;

import com.ventrax.installation.shared.domain.Actor;
import com.ventrax.installation.shared.domain.InstallationModels.Installation;

import java.time.Instant;
import java.util.UUID;

public interface InstallationRequests {
    record Contact(String name, String email, String phone) {}

    record Create(UUID propertyId, UUID supplyPointId, Contact contact, String address) {}

    record Payment(
            UUID eventId,
            UUID purchaseOrderId,
            UUID installationId,
            UUID customerAccountId,
            boolean confirmed) {}

    record Target(UUID propertyId, UUID supplyPointId, Long version) {}

    record Appointment(Instant from, Instant to, Long version) {}

    Installation create(Actor actor, Create input);

    Installation confirm(Payment payment);

    Installation target(Actor actor, UUID id, Target input);

    Installation appointment(Actor actor, UUID id, Appointment input);
}
