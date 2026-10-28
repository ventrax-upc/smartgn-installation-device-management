package com.smartgn.installationdevicemanagement.installations.interfaces.rest;

import com.smartgn.installationdevicemanagement.installations.application.port.in.InstallationRequests;
import com.smartgn.installationdevicemanagement.shared.domain.*;
import com.smartgn.installationdevicemanagement.shared.domain.InstallationModels.Installation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@Tag(name = "Installation requests")
public class RequestController {
    private final InstallationRequests operations;

    public RequestController(InstallationRequests operations) {
        this.operations = operations;
    }

    public record Contact(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 30) String phone) {}

    public record Create(
            UUID propertyId,
            UUID supplyPointId,
            @NotNull @Valid Contact contact,
            @NotBlank @Size(max = 500) String address) {}

    public record Payment(
            @NotNull UUID eventId,
            @NotNull UUID purchaseOrderId,
            @NotNull UUID installationId,
            @NotNull UUID customerAccountId,
            boolean confirmed) {}

    public record Target(
            @NotNull UUID propertyId,
            @NotNull UUID supplyPointId,
            @NotNull @PositiveOrZero Long version) {}

    public record Appointment(
            @NotNull Instant from, @NotNull Instant to, @NotNull @PositiveOrZero Long version) {}

    private final java.util.concurrent.ConcurrentHashMap<
                    String, java.util.concurrent.atomic.AtomicInteger>
            requests = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long minute;

    private void rate(jakarta.servlet.http.HttpServletRequest req) {
        long now = System.currentTimeMillis() / 60000;
        if (now != minute) {
            synchronized (requests) {
                if (now != minute) {
                    requests.clear();
                    minute = now;
                }
            }
        }
        if (requests.size() > 10000
                || requests.computeIfAbsent(
                                        req.getRemoteAddr(),
                                        k -> new java.util.concurrent.atomic.AtomicInteger())
                                .incrementAndGet()
                        > 30)
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Provisional request limit exceeded");
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @PostMapping("/api/v1/installations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a provisional request without creating an IAM account")
    public Installation create(
            @io.swagger.v3.oas.annotations.Parameter(hidden = true)
                    @RequestAttribute(value = "actor", required = false)
                    Actor actor,
            @Valid @RequestBody Create r,
            jakarta.servlet.http.HttpServletRequest request) {
        rate(request);
        return operations.create(
                actor,
                new InstallationRequests.Create(
                        r.propertyId(),
                        r.supplyPointId(),
                        new InstallationRequests.Contact(
                                r.contact().name(), r.contact().email(), r.contact().phone()),
                        r.address()));
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "serviceAuth")
    @PostMapping("/internal/v1/installations/payment-confirmations")
    @Operation(summary = "Apply a verified initial purchase idempotently")
    public Installation confirm(@Valid @RequestBody Payment p) {
        return operations.confirm(
                new InstallationRequests.Payment(
                        p.eventId(),
                        p.purchaseOrderId(),
                        p.installationId(),
                        p.customerAccountId(),
                        p.confirmed()));
    }

    @PutMapping("/api/v1/installations/{id}/target")
    @Operation(summary = "Link a paid pending request to its customer's active supply point")
    public Installation target(
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestAttribute("actor")
                    Actor actor,
            @PathVariable UUID id,
            @Valid @RequestBody Target r) {
        return operations.target(
                actor,
                id,
                new InstallationRequests.Target(r.propertyId(), r.supplyPointId(), r.version()));
    }

    @PutMapping("/api/v1/installations/{id}/appointment")
    @Operation(summary = "Update the assigned installation's appointment")
    public Installation appointment(
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestAttribute("actor")
                    Actor actor,
            @PathVariable UUID id,
            @Valid @RequestBody Appointment r) {
        return operations.appointment(
                actor, id, new InstallationRequests.Appointment(r.from(), r.to(), r.version()));
    }
}
