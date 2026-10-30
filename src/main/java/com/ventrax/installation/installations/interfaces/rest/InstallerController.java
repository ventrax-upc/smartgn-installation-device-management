package com.ventrax.installation.installations.interfaces.rest;

import com.ventrax.installation.shared.application.port.out.*;
import com.ventrax.installation.shared.domain.*;
import com.ventrax.installation.shared.domain.InstallationModels.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/installers")
@Tag(name = "External IoT installers")
public class InstallerController {
    private final InstallationStore store;
    private final TransactionRunner tx;

    public InstallerController(InstallationStore store, TransactionRunner tx) {
        this.store = store;
        this.tx = tx;
    }

    public record Input(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(max = 50) String identification,
            @NotBlank @Size(max = 30) String phone,
            @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 100) String accreditationCode,
            boolean available) {}

    public record Update(@NotNull @Valid Input installer, @NotNull @PositiveOrZero Long version) {}

    public record Status(boolean enabled, @NotNull @PositiveOrZero Long version) {}

    private void admin(Actor a) {
        a.requireRole(Role.SUPERADMIN);
    }

    private Installer get(UUID id) {
        return store.findInstaller(id)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Installer not found"));
    }

    private void audit(Actor a, String action, UUID id, UUID correlation) {
        store.audit(
                new Audit(
                        UUID.randomUUID(),
                        a.accountId(),
                        action,
                        "INSTALLER",
                        id,
                        correlation,
                        "External catalogue; no login",
                        Instant.now()));
    }

    @GetMapping
    @Operation(summary = "List external IoT installers as SuperAdmin")
    public List<Installer> list(
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestAttribute("actor")
                    Actor a,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        admin(a);
        return store.listInstallers(new ListWindow(offset, limit));
    }

    @GetMapping("/{id}")
    public Installer get(
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestAttribute("actor")
                    Actor a,
            @PathVariable UUID id) {
        admin(a);
        return get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Installer create(
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestAttribute("actor")
                    Actor a,
            @Valid @RequestBody Input x,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true)
                    @RequestAttribute("correlationId")
                    UUID correlation) {
        admin(a);
        return tx.run(
                () -> {
                    Installer value =
                            new Installer(
                                    UUID.randomUUID(),
                                    x.firstName(),
                                    x.lastName(),
                                    x.identification(),
                                    x.phone(),
                                    x.email(),
                                    true,
                                    0,
                                    x.accreditationCode(),
                                    x.available());
                    store.saveInstaller(value, -1);
                    audit(a, "INSTALLER_CREATED", value.id(), correlation);
                    return value;
                });
    }

    @PutMapping("/{id}")
    public Installer update(
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestAttribute("actor")
                    Actor a,
            @PathVariable UUID id,
            @Valid @RequestBody Update x,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true)
                    @RequestAttribute("correlationId")
                    UUID correlation) {
        admin(a);
        return tx.run(
                () -> {
                    Installer old =
                            store.lockInstaller(id)
                                    .orElseThrow(
                                            () ->
                                                    new DomainException(
                                                            "NOT_FOUND", "Installer not found"));
                    if (old.version() != x.version())
                        throw new DomainException("CONFLICT", "Installer version changed");
                    Input i = x.installer();
                    Installer value =
                            new Installer(
                                    id,
                                    i.firstName(),
                                    i.lastName(),
                                    i.identification(),
                                    i.phone(),
                                    i.email(),
                                    old.enabled(),
                                    old.version() + 1,
                                    i.accreditationCode(),
                                    i.available());
                    store.saveInstaller(value, old.version());
                    audit(a, "INSTALLER_UPDATED", id, correlation);
                    return value;
                });
    }

    @PatchMapping("/{id}/status")
    public Installer status(
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestAttribute("actor")
                    Actor a,
            @PathVariable UUID id,
            @Valid @RequestBody Status x,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true)
                    @RequestAttribute("correlationId")
                    UUID correlation) {
        admin(a);
        return tx.run(
                () -> {
                    Installer old =
                            store.lockInstaller(id)
                                    .orElseThrow(
                                            () ->
                                                    new DomainException(
                                                            "NOT_FOUND", "Installer not found"));
                    if (old.version() != x.version())
                        throw new DomainException("CONFLICT", "Installer version changed");
                    Installer value =
                            new Installer(
                                    id,
                                    old.firstName(),
                                    old.lastName(),
                                    old.identification(),
                                    old.phone(),
                                    old.email(),
                                    x.enabled(),
                                    old.version() + 1,
                                    old.accreditationCode(),
                                    old.available());
                    store.saveInstaller(value, old.version());
                    audit(a, "INSTALLER_STATUS_CHANGED", id, correlation);
                    return value;
                });
    }
}
