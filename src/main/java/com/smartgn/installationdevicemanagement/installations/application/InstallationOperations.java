package com.smartgn.installationdevicemanagement.installations.application;

import com.smartgn.installationdevicemanagement.devices.application.DeviceOperations;
import com.smartgn.installationdevicemanagement.installations.domain.InstallationRules;
import com.smartgn.installationdevicemanagement.shared.application.port.out.InstallationStore;
import com.smartgn.installationdevicemanagement.shared.application.port.out.TransactionRunner;
import com.smartgn.installationdevicemanagement.shared.domain.Actor;
import com.smartgn.installationdevicemanagement.shared.domain.DomainException;
import com.smartgn.installationdevicemanagement.shared.domain.InstallationModels.*;
import com.smartgn.installationdevicemanagement.shared.domain.ListWindow;
import com.smartgn.installationdevicemanagement.shared.domain.Role;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

public final class InstallationOperations {
    private final InstallationStore store;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final DeviceOperations devices;

    public InstallationOperations(
            InstallationStore store, TransactionRunner transactions, Clock clock) {
        this(store, transactions, clock, null);
    }

    public InstallationOperations(
            InstallationStore store,
            TransactionRunner transactions,
            Clock clock,
            DeviceOperations devices) {
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
        this.devices = devices;
    }

    public Installation create(Actor actor, UUID propertyId, UUID pointId) {
        return create(actor, propertyId, pointId, UUID.randomUUID());
    }

    public Installation create(Actor actor, UUID propertyId, UUID pointId, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        return transactions.run(
                () -> {
                    Property property =
                            store.lockProperty(propertyId).orElseThrow(() -> missing("Property"));
                    SupplyPoint point =
                            store.lockSupplyPoint(pointId)
                                    .orElseThrow(() -> missing("Supply point"));
                    if (!property.active()
                            || !point.active()
                            || !point.propertyId().equals(propertyId)) {
                        throw new DomainException(
                                "INVALID_STATE",
                                "Property and supply point must form an active association");
                    }
                    Installation value =
                            new Installation(
                                    UUID.randomUUID(),
                                    property.accountId(),
                                    propertyId,
                                    pointId,
                                    null,
                                    InstallationStatus.PENDING,
                                    null,
                                    clock.instant()
                                            .truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                                    null,
                                    0);
                    store.saveInstallation(value, -1);
                    audit(
                            actor,
                            "CREATE",
                            value.id(),
                            correlationId,
                            "Administrative installation created");
                    return value;
                });
    }

    public List<Installation> list(Actor actor) {
        actor.requireRole(Role.SUPERADMIN);
        return store.listInstallations();
    }

    public Installation get(Actor actor, UUID id) {
        Installation value = store.findInstallation(id).orElseThrow(() -> missing("Installation"));
        if (actor.role() != Role.SUPERADMIN) actor.requireOwner(value.accountId());
        return value;
    }

    public List<Installation> list(Actor actor, ListWindow window, InstallationStatus status) {
        return actor.role() == Role.SUPERADMIN
                ? store.listInstallations(window, status)
                : store.listInstallations().stream()
                        .filter(i -> actor.accountId().equals(i.accountId()))
                        .filter(i -> status == null || status == i.status())
                        .skip(window.offset())
                        .limit(window.limit())
                        .toList();
    }

    public Installation assign(Actor actor, UUID id, UUID installerId) {
        return assign(actor, id, installerId, UUID.randomUUID());
    }

    public Installation assign(Actor actor, UUID id, UUID installerId, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        return transactions.run(
                () -> {
                    Installation before =
                            store.lockInstallation(id).orElseThrow(() -> missing("Installation"));
                    Installer installer =
                            store.lockInstaller(installerId)
                                    .orElseThrow(() -> missing("Installer"));
                    if (!installer.enabled()
                            || !installer.available()
                            || installer.accreditationCode() == null
                            || installer.accreditationCode().isBlank())
                        throw new DomainException("INVALID_STATE", "Installer is not eligible");
                    if (before.status() == InstallationStatus.ASSIGNED
                            && installerId.equals(before.installerId())) return before;
                    if (!store.paymentVerified(id))
                        throw new DomainException(
                                "INVALID_STATE", "Verified purchase is required before assignment");
                    if (before.pointId() == null
                            || before.propertyId() == null
                            || before.accountId() == null)
                        throw new DomainException(
                                "INVALID_STATE", "Installation target is not linked");
                    if (store.installerBusy(installerId))
                        throw new DomainException(
                                "CONFLICT", "Installer already has an active assignment");
                    if (before.status() != InstallationStatus.PENDING) {
                        throw new DomainException(
                                "INVALID_STATE", "Only a pending installation can be assigned");
                    }
                    Installation after =
                            new Installation(
                                    before.id(),
                                    before.accountId(),
                                    before.propertyId(),
                                    before.pointId(),
                                    installerId,
                                    InstallationStatus.ASSIGNED,
                                    before.deviceId(),
                                    before.createdAt(),
                                    null,
                                    before.version() + 1);
                    store.saveInstallation(after, before.version());
                    store.assignment(id, installerId);
                    store.publish(
                            "InstallerAssigned",
                            id,
                            "{\"accountId\":\""
                                    + after.accountId()
                                    + "\",\"installerId\":\""
                                    + installerId
                                    + "\"}");
                    audit(actor, "ASSIGN", id, correlationId, "Assigned installer=" + installerId);
                    return after;
                });
    }

    public Installation transition(Actor actor, UUID id, InstallationStatus target) {
        return transition(actor, id, target, UUID.randomUUID());
    }

    public Installation transition(
            Actor actor, UUID id, InstallationStatus target, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        if (target != InstallationStatus.IN_PROGRESS && target != InstallationStatus.COMPLETED) {
            throw new DomainException(
                    "INVALID_STATE",
                    "Use the assignment operation; status accepts IN_PROGRESS or COMPLETED");
        }
        Installation snapshot =
                store.findInstallation(id).orElseThrow(() -> missing("Installation"));
        Long connectedVersion =
                target == InstallationStatus.COMPLETED
                                && snapshot.status() != target
                                && devices != null
                        ? devices.confirmConnectivity(snapshot.deviceId())
                        : null;
        return transactions.run(
                () -> {
                    Installation before =
                            store.lockInstallation(id).orElseThrow(() -> missing("Installation"));
                    if (before.status() == target) return before;
                    InstallationRules.requireTransition(before.status(), target);
                    if (target == InstallationStatus.IN_PROGRESS) {
                        Installer installer =
                                store.findInstaller(before.installerId())
                                        .orElseThrow(() -> missing("Installer"));
                        if (!installer.enabled())
                            throw new DomainException(
                                    "INVALID_STATE", "Assigned installer is disabled");
                    } else {
                        if (before.deviceId() == null)
                            throw new DomainException(
                                    "INVALID_STATE", "Installation has no registered device");
                        Device device =
                                store.lockDevice(before.deviceId())
                                        .orElseThrow(() -> missing("Device"));
                        InstallationRules.requireClosable(before, device);
                        if (connectedVersion != null
                                && (device.version() != connectedVersion
                                        || !device.id().equals(snapshot.deviceId())))
                            throw new DomainException(
                                    "CONFLICT", "Device changed after connectivity confirmation");
                        Association association =
                                store.findAssociation(device.id(), device.associationVersion())
                                        .orElseThrow(() -> missing("Device association"));
                        if (!association.confirmed() || association.validTo() != null) {
                            throw new DomainException(
                                    "INVALID_STATE", "Current association is not confirmed");
                        }
                    }
                    Installation after =
                            new Installation(
                                    before.id(),
                                    before.accountId(),
                                    before.propertyId(),
                                    before.pointId(),
                                    before.installerId(),
                                    target,
                                    before.deviceId(),
                                    before.createdAt(),
                                    target == InstallationStatus.COMPLETED
                                            ? clock.instant()
                                                    .truncatedTo(
                                                            java.time.temporal.ChronoUnit.MICROS)
                                            : null,
                                    before.version() + 1);
                    store.saveInstallation(after, before.version());
                    if (target == InstallationStatus.COMPLETED) {
                        store.releaseAssignment(id);
                        store.publish(
                                "InstallationCompleted",
                                id,
                                "{\"accountId\":\""
                                        + after.accountId()
                                        + "\",\"deviceId\":\""
                                        + after.deviceId()
                                        + "\",\"completedAt\":\""
                                        + after.completedAt()
                                        + "\"}");
                    }
                    audit(
                            actor,
                            "TRANSITION_" + target,
                            id,
                            correlationId,
                            "Administrative installation transition");
                    return after;
                });
    }

    public Installation reassign(
            Actor actor,
            UUID id,
            UUID installerId,
            long version,
            String reason,
            UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        reason(reason);
        return transactions.run(
                () -> {
                    Installation before =
                            store.lockInstallation(id).orElseThrow(() -> missing("Installation"));
                    if (before.version() != version)
                        throw new DomainException(
                                "CONFLICT", "Installation changed; reload before reassigning");
                    if (before.status() != InstallationStatus.ASSIGNED)
                        throw new DomainException(
                                "INVALID_STATE", "Reassignment is allowed only before work starts");
                    Installer installer =
                            store.lockInstaller(installerId)
                                    .orElseThrow(() -> missing("Installer"));
                    if (!installer.enabled()
                            || !installer.available()
                            || installer.accreditationCode() == null
                            || installer.accreditationCode().isBlank())
                        throw new DomainException("INVALID_STATE", "Installer is not eligible");
                    if (installerId.equals(before.installerId())) return before;
                    if (store.installerBusy(installerId))
                        throw new DomainException("CONFLICT", "Installer already assigned");
                    store.assignment(id, installerId);
                    Installation after =
                            new Installation(
                                    before.id(),
                                    before.accountId(),
                                    before.propertyId(),
                                    before.pointId(),
                                    installerId,
                                    before.status(),
                                    before.deviceId(),
                                    before.createdAt(),
                                    null,
                                    before.version() + 1);
                    store.saveInstallation(after, before.version());
                    store.publish(
                            "InstallerAssigned",
                            id,
                            "{\"accountId\":\""
                                    + after.accountId()
                                    + "\",\"installerId\":\""
                                    + installerId
                                    + "\"}");
                    audit(
                            actor,
                            "REASSIGN",
                            id,
                            correlationId,
                            "before="
                                    + before.installerId()
                                    + ";after="
                                    + installerId
                                    + ";reason="
                                    + reason.trim());
                    return after;
                });
    }

    public Installation cancel(
            Actor actor, UUID id, long version, String reason, UUID correlationId) {
        actor.requireRole(Role.SUPERADMIN);
        reason(reason);
        return transactions.run(
                () -> {
                    Installation before =
                            store.lockInstallation(id).orElseThrow(() -> missing("Installation"));
                    if (before.status() == InstallationStatus.CANCELLED) return before;
                    if (before.version() != version)
                        throw new DomainException(
                                "CONFLICT", "Installation changed; reload before cancelling");
                    if (before.status() == InstallationStatus.COMPLETED)
                        throw new DomainException(
                                "INVALID_STATE", "A completed installation cannot be cancelled");
                    if (before.deviceId() != null)
                        java.util.Objects.requireNonNull(
                                        devices, "Device lifecycle operations must be configured")
                                .endForCancellation(actor, before, correlationId);
                    Installation after =
                            new Installation(
                                    before.id(),
                                    before.accountId(),
                                    before.propertyId(),
                                    before.pointId(),
                                    before.installerId(),
                                    InstallationStatus.CANCELLED,
                                    before.deviceId(),
                                    before.createdAt(),
                                    null,
                                    before.version() + 1);
                    store.saveInstallation(after, before.version());
                    store.releaseAssignment(id);
                    audit(
                            actor,
                            "CANCEL",
                            id,
                            correlationId,
                            "previousStatus=" + before.status() + ";reason=" + reason.trim());
                    return after;
                });
    }

    public void assignAvailable() {
        Actor system = new Actor(new UUID(0, 0), Role.SUPERADMIN);
        for (Installation i : store.listInstallations())
            if (i.status() == InstallationStatus.PENDING
                    && i.pointId() != null
                    && store.paymentVerified(i.id())) {
                for (Installer installer :
                        store.listInstallers().stream()
                                .sorted(java.util.Comparator.comparing(x -> x.id().toString()))
                                .toList()) {
                    if (!installer.enabled()
                            || !installer.available()
                            || installer.accreditationCode() == null
                            || store.installerBusy(installer.id())) continue;
                    try {
                        assign(system, i.id(), installer.id());
                        break;
                    } catch (DomainException concurrent) {
                        if (!java.util.Set.of("CONFLICT", "INVALID_STATE")
                                .contains(concurrent.code())) throw concurrent;
                    }
                }
            }
    }

    private static void reason(String value) {
        if (value == null || value.isBlank() || value.length() > 1000)
            throw new DomainException(
                    "INVALID_INPUT", "An administrative reason of 1..1000 characters is required");
    }

    private void audit(Actor actor, String action, UUID id, UUID correlationId, String details) {
        store.audit(
                new Audit(
                        UUID.randomUUID(),
                        actor.accountId(),
                        action,
                        "INSTALLATION",
                        id,
                        correlationId,
                        details,
                        clock.instant()));
    }

    private static DomainException missing(String resource) {
        return new DomainException("NOT_FOUND", resource + " was not found");
    }
}
