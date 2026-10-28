package com.smartgn.installationdevicemanagement.installations.interfaces.rest;

import com.smartgn.installationdevicemanagement.installations.application.InstallationOperations;
import com.smartgn.installationdevicemanagement.shared.domain.Actor;
import com.smartgn.installationdevicemanagement.shared.domain.InstallationModels.Installation;
import com.smartgn.installationdevicemanagement.shared.domain.InstallationModels.InstallationStatus;
import com.smartgn.installationdevicemanagement.shared.domain.ListWindow;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/installations")
@Tag(name = "Installations", description = "SuperAdmin installation lifecycle operations")
public final class InstallationController {
    public record CreateInstallation(@NotNull UUID propertyId, @NotNull UUID pointId) {}

    public record AssignInstaller(@NotNull UUID installerId) {}

    public record ChangeStatus(@NotNull InstallationStatus status) {}

    public record ReassignInstaller(
            @NotNull UUID installerId,
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 1000) String reason) {}

    public record CancelInstallation(
            @NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 1000) String reason) {}

    private final InstallationOperations operations;
    private final org.springframework.jdbc.core.JdbcTemplate db;

    public InstallationController(
            InstallationOperations operations, org.springframework.jdbc.core.JdbcTemplate db) {
        this.operations = operations;
        this.db = db;
    }

    @GetMapping
    @Operation(summary = "List installations administered by SuperAdmin")
    public List<Installation> list(
            @Parameter(hidden = true) @RequestAttribute("actor") Actor actor,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) InstallationStatus status) {
        return operations.list(actor, new ListWindow(offset, limit), status);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an installation and its confirmed lifecycle state")
    public java.util.Map<String, Object> get(
            @Parameter(hidden = true) @RequestAttribute("actor") Actor actor,
            @PathVariable UUID id) {
        Installation value = operations.get(actor, id);
        var metadata =
                db.queryForList(
                        "SELECT"
                            + " contact_name,contact_email,contact_phone,exact_location,payment_verified_at,scheduled_window_start,scheduled_window_end"
                            + " FROM installation_requests WHERE request_id=?",
                        id);
        return java.util.Map.of(
                "installation",
                value,
                "details",
                metadata.isEmpty() ? java.util.Map.of() : metadata.getFirst(),
                "installer",
                value.installerId() == null
                        ? java.util.Map.of()
                        : operationsInstaller(value.installerId()));
    }

    private Object operationsInstaller(UUID id) {
        return db.queryForMap(
                "SELECT first_name,last_name,phone,email FROM installers WHERE id=?", id);
    }

    @PostMapping("/{id}/assign")
    @Operation(summary = "Assign an enabled external IoT installer to a pending request")
    public Installation assign(
            @Parameter(hidden = true) @RequestAttribute("actor") Actor actor,
            @PathVariable UUID id,
            @Valid @RequestBody AssignInstaller request,
            @Parameter(hidden = true) @RequestAttribute("correlationId") UUID correlation) {
        return operations.assign(actor, id, request.installerId(), correlation);
    }

    @PostMapping("/{id}/complete")
    @Operation(
            summary = "Complete a confirmed installation exactly once",
            description =
                    "Requires the exact active association, confirmed provisioning and CONNECTED"
                            + " metadata with lastContact less than 30 seconds old and at most 5"
                            + " seconds in the future. Missing or stale connectivity prevents"
                            + " completion. Replays preserve the completion date.")
    public Installation complete(
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestAttribute("actor")
                    Actor actor,
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true)
                    @RequestAttribute("correlationId")
                    UUID correlation) {
        return operations.transition(actor, id, InstallationStatus.COMPLETED, correlation);
    }

    @PatchMapping("/{id}/status")
    @Operation(
            summary = "Start or complete an installation",
            description =
                    "Accepts IN_PROGRESS or COMPLETED. Completion requires the exact broker and"
                        + " Telemetry confirmations. Repeating a completed transition preserves its"
                        + " date.")
    public Installation transition(
            @Parameter(hidden = true) @RequestAttribute("actor") Actor actor,
            @PathVariable UUID id,
            @Valid @RequestBody ChangeStatus request,
            @Parameter(hidden = true) @RequestAttribute("correlationId") UUID correlation) {
        return operations.transition(actor, id, request.status(), correlation);
    }

    @PatchMapping("/{id}/reassign")
    @Operation(
            summary = "Replace the assigned installer before work starts",
            description =
                    "SuperAdmin only. Requires the current version and an administrative reason.")
    public Installation reassign(
            @Parameter(hidden = true) @RequestAttribute("actor") Actor actor,
            @PathVariable UUID id,
            @Valid @RequestBody ReassignInstaller request,
            @Parameter(hidden = true) @RequestAttribute("correlationId") UUID correlation) {
        return operations.reassign(
                actor, id, request.installerId(), request.version(), request.reason(), correlation);
    }

    @PostMapping("/{id}/cancel")
    @Operation(
            summary = "Cancel an unfinished installation while preserving history",
            description =
                    "Requires SuperAdmin, current version and reason. If a device exists, revoke it"
                            + " first and wait for brokerConfirmed=true. Repeated cancellation is"
                            + " idempotent.")
    public Installation cancel(
            @Parameter(hidden = true) @RequestAttribute("actor") Actor actor,
            @PathVariable UUID id,
            @Valid @RequestBody CancelInstallation request,
            @Parameter(hidden = true) @RequestAttribute("correlationId") UUID correlation) {
        return operations.cancel(actor, id, request.version(), request.reason(), correlation);
    }
}
