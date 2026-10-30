package com.ventrax.installation.adapter.out.persistence;

import com.ventrax.installation.installations.application.port.in.InstallationRequests.*;
import com.ventrax.installation.shared.application.port.out.*;
import com.ventrax.installation.shared.domain.*;
import com.ventrax.installation.shared.domain.InstallationModels.*;

import jakarta.validation.constraints.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@org.springframework.stereotype.Service
public class JdbcInstallationRequests
        implements com.ventrax.installation.installations.application.port.in.InstallationRequests {
    private final InstallationStore store;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final IdentityGateway identities;

    public JdbcInstallationRequests(
            InstallationStore store,
            JdbcTemplate db,
            TransactionTemplate tx,
            IdentityGateway identities) {
        this.store = store;
        this.db = db;
        this.tx = tx;
        this.identities = identities;
    }

    public Installation create(Actor actor, Create input) {
        if (actor != null && actor.delegated())
            throw new DomainException(
                    "FORBIDDEN", "Delegated accounts cannot create installations");
        if (actor == null && (input.propertyId() != null || input.supplyPointId() != null))
            throw new DomainException(
                    "FORBIDDEN", "Anonymous requests cannot reference private resources");
        return tx.execute(
                status -> {
                    UUID owner =
                            actor == null || actor.role() == Role.SUPERADMIN
                                    ? null
                                    : actor.accountId();
                    if (input.propertyId() != null) {
                        Property property =
                                store.lockProperty(input.propertyId())
                                        .orElseThrow(
                                                () ->
                                                        new DomainException(
                                                                "NOT_FOUND", "Property not found"));
                        if (actor.role() != Role.SUPERADMIN)
                            actor.requireOwner(property.accountId());
                        if (!property.active())
                            throw new DomainException("INVALID_STATE", "Property is inactive");
                        owner = property.accountId();
                    }
                    if (input.supplyPointId() != null) {
                        SupplyPoint point =
                                store.lockSupplyPoint(input.supplyPointId())
                                        .orElseThrow(
                                                () ->
                                                        new DomainException(
                                                                "NOT_FOUND",
                                                                "Supply point not found"));
                        if (!point.active() || !point.propertyId().equals(input.propertyId()))
                            throw new DomainException(
                                    "INVALID_STATE", "Supply point target mismatch");
                    }
                    UUID id = UUID.randomUUID();
                    Installation i =
                            new Installation(
                                    id,
                                    owner,
                                    input.propertyId(),
                                    input.supplyPointId(),
                                    null,
                                    InstallationStatus.PROVISIONAL,
                                    null,
                                    Instant.now(),
                                    null,
                                    0);
                    store.saveInstallation(i, -1);
                    db.update(
                            "INSERT INTO"
                                + " installation_requests(request_id,contact_name,contact_email,contact_phone,exact_location)"
                                + " VALUES (?,?,?,?,?)",
                            id,
                            input.contact().name(),
                            input.contact().email().trim().toLowerCase(Locale.ROOT),
                            input.contact().phone(),
                            input.address());
                    store.publish("InstallationRequested", id, "{}");
                    return i;
                });
    }

    public Installation confirm(Payment p) {
        if (!p.confirmed()) throw new DomainException("INVALID_STATE", "Payment is not verified");
        var account = identities.account(p.customerAccountId());
        if (!"PRINCIPAL".equals(account.accountKind())
                || account.role() != Role.USER
                || "DISABLED".equals(account.status()))
            throw new DomainException("FORBIDDEN", "A principal customer account is required");
        return tx.execute(
                status -> {
                    Installation before =
                            store.lockInstallation(p.installationId())
                                    .orElseThrow(
                                            () ->
                                                    new DomainException(
                                                            "NOT_FOUND", "Installation not found"));
                    String fingerprint;
                    try {
                        fingerprint =
                                HexFormat.of()
                                        .formatHex(
                                                MessageDigest.getInstance("SHA-256")
                                                        .digest(
                                                                (p.purchaseOrderId()
                                                                                + ":"
                                                                                + p.installationId()
                                                                                + ":"
                                                                                + p
                                                                                        .customerAccountId())
                                                                        .getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8)));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    var inbox =
                            db.queryForList(
                                    "SELECT payload_fingerprint FROM integration_inbox WHERE"
                                            + " event_id=?",
                                    p.eventId());
                    if (!inbox.isEmpty()) {
                        if (!fingerprint.equals(inbox.getFirst().get("payload_fingerprint")))
                            throw new DomainException(
                                    "CONFLICT", "Event identity conflicts with its payload");
                        return before;
                    }
                    var details =
                            db.queryForMap(
                                    "SELECT * FROM installation_requests WHERE request_id=? FOR"
                                            + " UPDATE",
                                    before.id());
                    if (!account.email().equalsIgnoreCase(details.get("contact_email").toString())
                            || (before.accountId() != null
                                    && !before.accountId().equals(account.accountId())))
                        throw new DomainException(
                                "FORBIDDEN", "Purchase customer does not match the request");
                    if (details.get("purchase_order_id") != null
                            && !p.purchaseOrderId().equals(details.get("purchase_order_id")))
                        throw new DomainException(
                                "CONFLICT", "Installation is linked to another purchase");
                    if (before.status() == InstallationStatus.CANCELLED)
                        throw new DomainException("INVALID_STATE", "Installation is cancelled");
                    db.update(
                            "INSERT INTO"
                                + " integration_inbox(event_id,event_type,aggregate_id,payload_fingerprint)"
                                + " VALUES (?,'PaymentConfirmed',?,?)",
                            p.eventId(),
                            before.id(),
                            fingerprint);
                    if (store.paymentVerified(before.id())) return before;
                    db.update(
                            "UPDATE installation_requests SET"
                                    + " purchase_order_id=?,payment_verified_at=now() WHERE"
                                    + " request_id=?",
                            p.purchaseOrderId(),
                            before.id());
                    Installation after =
                            new Installation(
                                    before.id(),
                                    p.customerAccountId(),
                                    before.propertyId(),
                                    before.pointId(),
                                    null,
                                    InstallationStatus.PENDING,
                                    null,
                                    before.createdAt(),
                                    null,
                                    before.version() + 1);
                    store.saveInstallation(after, before.version());
                    store.publish(
                            "InstallationPaymentVerified",
                            before.id(),
                            "{\"accountId\":\""
                                    + after.accountId()
                                    + "\",\"purchaseOrderId\":\""
                                    + p.purchaseOrderId()
                                    + "\"}");
                    return after;
                });
    }

    public Installation target(Actor actor, UUID id, Target input) {
        return tx.execute(
                status -> {
                    Installation before =
                            store.lockInstallation(id)
                                    .orElseThrow(
                                            () ->
                                                    new DomainException(
                                                            "NOT_FOUND", "Installation not found"));
                    if (actor.role() != Role.SUPERADMIN) actor.requireOwner(before.accountId());
                    if (before.version() != input.version()
                            || before.status() != InstallationStatus.PENDING
                            || !store.paymentVerified(id))
                        throw new DomainException(
                                "CONFLICT",
                                "A paid pending installation with current version is required");
                    Property property =
                            store.lockProperty(input.propertyId())
                                    .orElseThrow(
                                            () ->
                                                    new DomainException(
                                                            "NOT_FOUND", "Property not found"));
                    SupplyPoint point =
                            store.lockSupplyPoint(input.supplyPointId())
                                    .orElseThrow(
                                            () ->
                                                    new DomainException(
                                                            "NOT_FOUND", "Supply point not found"));
                    if (!property.active()
                            || !point.active()
                            || !point.propertyId().equals(property.id())
                            || !property.accountId().equals(before.accountId()))
                        throw new DomainException(
                                "FORBIDDEN", "Target does not belong to the installation customer");
                    Installation after =
                            new Installation(
                                    id,
                                    before.accountId(),
                                    property.id(),
                                    point.id(),
                                    null,
                                    before.status(),
                                    null,
                                    before.createdAt(),
                                    null,
                                    before.version() + 1);
                    store.saveInstallation(after, before.version());
                    return after;
                });
    }

    public Installation appointment(Actor actor, UUID id, Appointment input) {
        actor.requireRole(Role.SUPERADMIN);
        if (!input.to().isAfter(input.from()) || input.from().isBefore(Instant.now()))
            throw new DomainException("INVALID_INPUT", "Appointment window is invalid");
        return tx.execute(
                status -> {
                    Installation before =
                            store.lockInstallation(id)
                                    .orElseThrow(
                                            () ->
                                                    new DomainException(
                                                            "NOT_FOUND", "Installation not found"));
                    if (before.version() != input.version()
                            || before.status() != InstallationStatus.ASSIGNED)
                        throw new DomainException(
                                "CONFLICT", "Assigned installation with current version required");
                    db.update(
                            "UPDATE installation_requests SET"
                                    + " scheduled_window_start=?,scheduled_window_end=? WHERE"
                                    + " request_id=?",
                            Timestamp.from(input.from()),
                            Timestamp.from(input.to()),
                            id);
                    Installation after =
                            new Installation(
                                    id,
                                    before.accountId(),
                                    before.propertyId(),
                                    before.pointId(),
                                    before.installerId(),
                                    before.status(),
                                    before.deviceId(),
                                    before.createdAt(),
                                    before.completedAt(),
                                    before.version() + 1);
                    store.saveInstallation(after, before.version());
                    store.audit(
                            new Audit(
                                    UUID.randomUUID(),
                                    actor.accountId(),
                                    "APPOINTMENT_CHANGED",
                                    "INSTALLATION",
                                    id,
                                    UUID.randomUUID(),
                                    "Appointment window updated",
                                    Instant.now()));
                    store.publish(
                            "InstallationAppointmentChanged",
                            id,
                            "{\"accountId\":\""
                                    + after.accountId()
                                    + "\",\"installerId\":\""
                                    + after.installerId()
                                    + "\",\"from\":\""
                                    + input.from()
                                    + "\",\"to\":\""
                                    + input.to()
                                    + "\"}");
                    return after;
                });
    }
}
